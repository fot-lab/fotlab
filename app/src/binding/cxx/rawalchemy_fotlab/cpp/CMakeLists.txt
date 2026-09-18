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
#
# OpenMP is deliberately NOT enabled. Upstream turns it on only as
# `target_compile_definitions(raw_alchemy_core PUBLIC RA_USE_OPENMP)` *after* a
# successful find_package(OpenMP) — it is not derived from `_OPENMP` anywhere. Since
# every `#pragma omp` in these files sits inside `#ifdef RA_USE_OPENMP`, passing
# `-fopenmp` without that definition would parallelise nothing while still making the
# final link demand a libgomp/libomp runtime — which is exactly the kind of
# cross-compile dependency we do not want under the NDK. So: no OpenMP here, and
# `RA_USE_OPENMP` stays undefined, so these files build as plain single-threaded
# loops. Enabling it later means defining RA_USE_OPENMP *and* proving the runtime
# links for every Android ABI.
add_library(rawalchemy_grading STATIC
  "${RAWALCHEMY_SRC}/src/grading_fused.cpp"
  "${RAWALCHEMY_SRC}/src/log_transform.cpp"
  "${RAWALCHEMY_SRC}/src/lut_applier.cpp"
  "${RAWALCHEMY_SRC}/src/metering.cpp"
)
target_include_directories(rawalchemy_grading PUBLIC "${RA_INC}")

# No RA_BUILDING_SHARED defined -> RA_API expands to nothing (static-friendly).

install(TARGETS rawalchemy_grading ARCHIVE DESTINATION lib)
