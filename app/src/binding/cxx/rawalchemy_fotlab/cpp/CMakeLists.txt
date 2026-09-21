cmake_minimum_required(VERSION 3.16)
project(rawalchemy_grading CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# The static lib is linked into a cdylib, so it must be relocatable.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Injected by build.rs.
set(RAWALCHEMY_SRC "" CACHE PATH "absolute path to external/RawAlchemyCpp")
if(NOT RAWALCHEMY_SRC)
  message(FATAL_ERROR "RAWALCHEMY_SRC is not set — point it at external/RawAlchemyCpp")
endif()

set(RA_INC "${RAWALCHEMY_SRC}/include")

# Grading-only subset. All four are STL-only, so they compile cleanly in isolation
# without LibRaw / libtiff / turbojpeg / libexif / ONNX (those belong to the decode
# + NN demosaic paths we deliberately skip).
add_library(rawalchemy_grading STATIC
  "${RAWALCHEMY_SRC}/src/grading_fused.cpp"
  "${RAWALCHEMY_SRC}/src/log_transform.cpp"
  "${RAWALCHEMY_SRC}/src/lut_applier.cpp"
  "${RAWALCHEMY_SRC}/src/metering.cpp"
)
target_include_directories(rawalchemy_grading PUBLIC "${RA_INC}")

# No RA_BUILDING_SHARED defined -> RA_API expands to nothing (static-friendly).

# ---------------------------------------------------------------------------
# OpenMP — ENABLED (was deliberately off before this change).
#
# Why: upstream gates every `#pragma omp` behind `RA_USE_OPENMP`, which upstream
# itself only defines after a successful `find_package(OpenMP)`. With the macro
# undefined each parallel region compiles out and the grading loops degrade to
# sequential scalar code — a 50 MP grade then pays its full tri-linear LUT (8
# gathers per pixel) on one core. See
# `rules/REVIEW/detail/ACTION-PERFOR-000007.md`.
#
# Two constraints make this more than a one-line toggle:
#
# 1. `RA_USE_OPENMP` must be DEFINED at compile time, not merely implied by
#    `-fopenmp`: upstream derives nothing from `_OPENMP`. Passing `-fopenmp`
#    without the definition would parallelise nothing while still making the link
#    demand a runtime. Hence the explicit `target_compile_definitions` below.
# 2. This target is a STATIC library, so nothing is linked here — the final link
#    is driven by rustc. Whatever form the OpenMP runtime takes therefore has to
#    be handed BACK to build.rs, which re-emits it as cargo link directives. It
#    is written to `openmp-link.txt` in this project's binary dir, one cargo
#    directive per line (see the end of this file).
#
# The runtime is preferred STATIC: our only consumer is a single cdylib, so a
# statically linked runtime needs no `libomp.so` inside the APK and can never
# fail at `dlopen` time — which removes the runtime-packaging half of the risk
# (`ACTION-PERFOR-000001` C3) entirely. A shared runtime is accepted when that is
# all the toolchain ships, and is then reported for bundling.
#
# Escape hatch: set the environment variable RA_ENABLE_OPENMP=OFF to build
# single-threaded again without touching this file.
# ---------------------------------------------------------------------------
set(RA_OPENMP_REQUESTED ON)
if(DEFINED ENV{RA_ENABLE_OPENMP} AND "$ENV{RA_ENABLE_OPENMP}" STREQUAL "OFF")
  set(RA_OPENMP_REQUESTED OFF)
endif()

set(RA_OPENMP OFF)
set(RA_OPENMP_LINK_INFO "")

if(RA_OPENMP_REQUESTED)
  # Probe the COMPILE side only. Under the Android NDK toolchain
  # `CMAKE_TRY_COMPILE_TARGET_TYPE` is STATIC_LIBRARY, so this does not attempt to
  # link — which is exactly what we want: availability of the runtime is decided
  # separately below, where we can pick static over shared.
  include(CheckCXXSourceCompiles)
  set(CMAKE_REQUIRED_FLAGS "-fopenmp")
  check_cxx_source_compiles("
    #include <omp.h>
    int main() {
      int n = 0;
      #pragma omp parallel reduction(+:n)
      { n += omp_get_num_threads(); }
      return n > 0 ? 0 : 1;
    }
    " RA_HAVE_FOPENMP)
  unset(CMAKE_REQUIRED_FLAGS)

  if(NOT RA_HAVE_FOPENMP)
    message(STATUS "rawalchemy_grading: compiler has no working -fopenmp; grading builds single-threaded")
  endif()
endif()

if(RA_OPENMP_REQUESTED AND RA_HAVE_FOPENMP)
  # where the runtime can plausibly live, per toolchain flavour
  set(_ra_omp_hints "")
  if(ANDROID_ABI)
    # clang names its per-arch runtime dirs differently from Android ABI names
    if(ANDROID_ABI STREQUAL "arm64-v8a")
      set(_ra_clang_arch "aarch64")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
      set(_ra_clang_arch "arm")
    elseif(ANDROID_ABI STREQUAL "x86")
      set(_ra_clang_arch "i386")
    elseif(ANDROID_ABI STREQUAL "x86_64")
      set(_ra_clang_arch "x86_64")
    else()
      set(_ra_clang_arch "")
    endif()
    # Both layouts exist across NDK revisions: lib64/clang/<ver>/... and lib/clang/<ver>/...
    file(GLOB _ra_clang_dirs
      "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/*/lib64/clang/*/lib/linux/${_ra_clang_arch}"
      "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/${_ra_clang_arch}"
      "$ENV{ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/*/lib64/clang/*/lib/linux/${_ra_clang_arch}"
      "$ENV{ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/${_ra_clang_arch}"
    )
    list(APPEND _ra_omp_hints ${_ra_clang_dirs})
    # Only when the toolchain actually told us the LLVM triple — an empty component would
    # turn the pattern into `<ndk>/.../usr/lib/`, which (if the NDK vars were unset) can
    # degenerate into the HOST /usr/lib and pick up a host-architecture runtime.
    if(ANDROID_LLVM_TRIPLE)
      file(GLOB _ra_sysroot_dirs
        "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/${ANDROID_LLVM_TRIPLE}"
        "$ENV{ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/${ANDROID_LLVM_TRIPLE}"
      )
      list(APPEND _ra_omp_hints ${_ra_sysroot_dirs})
    endif()
  endif()

  # --- first choice: static runtime (nothing to ship at runtime) ---
  set(CMAKE_FIND_LIBRARY_SUFFIXES_SAVED "${CMAKE_FIND_LIBRARY_SUFFIXES}")
  set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")
  find_library(RA_LIBOMP_STATIC NAMES omp HINTS ${_ra_omp_hints})
  set(CMAKE_FIND_LIBRARY_SUFFIXES "${CMAKE_FIND_LIBRARY_SUFFIXES_SAVED}")

  if(RA_LIBOMP_STATIC)
    get_filename_component(_ra_omp_dir "${RA_LIBOMP_STATIC}" DIRECTORY)
    set(RA_OPENMP ON)
    set(RA_OPENMP_LINK_INFO
      "rustc-link-search=native=${_ra_omp_dir}\nrustc-link-lib=static=omp\n")
    message(STATUS "rawalchemy_grading: OpenMP ENABLED (static runtime) <- ${RA_LIBOMP_STATIC}")
  else()
    # --- fallback: shared runtime (must be bundled next to our .so) ---
    find_library(RA_LIBOMP_SHARED NAMES omp HINTS ${_ra_omp_hints})
    if(RA_LIBOMP_SHARED)
      get_filename_component(_ra_omp_dir "${RA_LIBOMP_SHARED}" DIRECTORY)
      set(RA_OPENMP ON)
      set(RA_OPENMP_LINK_INFO
        "rustc-link-search=native=${_ra_omp_dir}\nrustc-link-lib=dylib=omp\nbundle=${RA_LIBOMP_SHARED}\n")
      message(STATUS
        "rawalchemy_grading: OpenMP ENABLED (shared runtime) <- ${RA_LIBOMP_SHARED} "
        "(this file must be bundled into jniLibs; see build_rust.yaml)")
    else()
      message(WARNING
        "rawalchemy_grading: -fopenmp compiles but no libomp runtime was found under "
        "the toolchain (searched the NDK clang runtime and sysroot dirs), so grading "
        "is left single-threaded instead of producing an unlinkable cdylib. Point "
        "hints at the runtime or check the NDK layout to enable it.")
    endif()
  endif()
endif()

if(RA_OPENMP)
  target_compile_options(rawalchemy_grading PUBLIC -fopenmp)
  target_compile_definitions(rawalchemy_grading PUBLIC RA_USE_OPENMP)
else()
  message(STATUS "rawalchemy_grading: OpenMP DISABLED — parallel grading regions compile out")
endif()

# Hand whatever link requirement we resolved back to build.rs. Always written, so
# build.rs can read it unconditionally; empty when OpenMP is off.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/openmp-link.txt" "${RA_OPENMP_LINK_INFO}")

install(TARGETS rawalchemy_grading ARCHIVE DESTINATION lib)
