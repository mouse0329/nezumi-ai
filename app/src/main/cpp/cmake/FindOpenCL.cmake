# Supplies OpenCL to llama.cpp's ggml-opencl when we build the Khronos ICD loader.

if(OpenCL_FOUND)
    return()
endif()

if(NOT TARGET OpenCL)
    return()
endif()

if(NOT OPENCL_HEADERS_DIR)
    message(FATAL_ERROR "OPENCL_HEADERS_DIR is not set")
endif()

set(OpenCL_INCLUDE_DIR "${OPENCL_HEADERS_DIR}")
set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}")
set(OpenCL_LIBRARY OpenCL)
set(OpenCL_LIBRARIES OpenCL)
set(OpenCL_FOUND TRUE)
set(OpenCL_VERSION_STRING "3.0")

if(NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL ALIAS OpenCL)
endif()
