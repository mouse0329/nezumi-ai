# Minimal CMake package so ggml-vulkan can find SPIR-V headers without a full SDK install.

if(NOT SPIRV_HEADERS_INCLUDE_DIR)
    message(FATAL_ERROR "SPIRV_HEADERS_INCLUDE_DIR is not set")
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_INCLUDE_DIR}"
    )
endif()

set(SPIRV-Headers_FOUND TRUE)
