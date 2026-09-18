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

# Grading-only subset. All four are STL-only (`#include <omp.h>` +
# `half_buffer.h`, the latter header-only and aarch64-guarded), so they compile
# cleanly in isolation without LibRaw / libtiff / turbojpeg / libexif / ONNX
# (those belong to the decode + NN demosaic paths we deliberately skip).
add_library(rawalchemy_grading STATIC
  "${RAWALCHEMY_SRC}/src/grading_fused.cpp"
  "${RAWALCHEMY_SRC}/src/log_transform.cpp"
  "${RAWALCHEMY_SRC}/src/lut_applier.cpp"
  "${RAWALCHEMY_SRC}/src/metering.cpp"
)
target_include_directories(rawalchemy_grading PUBLIC "${RA_INC}")
target_compile_options(rawalchemy_grading PRIVATE -fopenmp -Wall)
# No RA_BUILDING_SHARED defined -> RA_API expands to nothing (static-friendly).
target_link_options(rawalchemy_grading INTERFACE -fopenmp)

install(TARGETS rawalchemy_grading ARCHIVE DESTINATION lib)
