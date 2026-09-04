cmake_minimum_required(VERSION 3.22.1)

project(nezumi_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

find_library(LOG_LIB log)

option(LLAMA_BRIDGE_OPENCL "Enable OpenCL backend for llama_bridge (vendor/llama.cpp)" ON)
option(LLAMA_BRIDGE_VULKAN "Enable Vulkan backend for llama_bridge (vendor/llama.cpp)" ON)

# ICD loader / llama.cpp を SHARED にしない。APK に libOpenCL.so を同梱すると
# 端末側の vendor OpenCL と名前が衝突するため、静的リンクする。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Debug APK でも推論コードを軽く最適化する。大規模なモデル実装へ -O3 を
# 一括適用すると NDK clang のコンパイルメモリを圧迫するため、-O1 に留める。
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O1 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O1 -DNDEBUG")

# =========================
# 本家 llama.cpp (vendor) — llama_bridge 用
# =========================
# llama_bridge.cpp はこちらの llama.h / ggml.h のみを参照する。
set(LLAMA_CPP_UPSTREAM_DIR ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp)
set(NEZUMI_CMAKE_DIR ${CMAKE_SOURCE_DIR}/cmake)
list(PREPEND CMAKE_MODULE_PATH ${NEZUMI_CMAKE_DIR})

function(nezumi_setup_opencl)
    set(NEZUMI_OPENCL_READY OFF PARENT_SCOPE)
    if(NOT LLAMA_BRIDGE_OPENCL)
        return()
    endif()

    find_package(Python3 COMPONENTS Interpreter QUIET)
    if(NOT Python3_FOUND)
        find_program(Python3_EXECUTABLE NAMES python3 python py)
        if(Python3_EXECUTABLE)
            set(Python3_FOUND TRUE)
        endif()
    endif()
    if(NOT Python3_FOUND)
        message(WARNING "Python3 が見つからないため OpenCL カーネル埋め込みができません。GGML_OPENCL を無効化します。")
        return()
    endif()
    set(Python3_EXECUTABLE "${Python3_EXECUTABLE}" CACHE FILEPATH "" FORCE)

    set(_opencl_headers "${CMAKE_SOURCE_DIR}/../../../../third_party/MNN/3rd_party/OpenCLHeaders")
    if(NOT EXISTS "${_opencl_headers}/CL/cl.h" OR NOT EXISTS "${_opencl_headers}/CL/cl_icd.h")
        FetchContent_Declare(opencl_headers
            GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
            GIT_TAG v2024.10.24
            GIT_SHALLOW TRUE
        )
        FetchContent_MakeAvailable(opencl_headers)
        set(_opencl_headers "${opencl_headers_SOURCE_DIR}")
    endif()
    if(NOT EXISTS "${_opencl_headers}/CL/cl.h")
        message(WARNING "OpenCL headers が見つからないため GGML_OPENCL を無効化します。")
        return()
    endif()

    set(OPENCL_HEADERS_DIR "${_opencl_headers}" CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${_opencl_headers}" CACHE PATH "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)

    if(NOT TARGET OpenCL)
        FetchContent_Declare(opencl_icd_loader
            GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
            GIT_TAG v2024.10.24
            GIT_SHALLOW TRUE
        )
        FetchContent_MakeAvailable(opencl_icd_loader)
    endif()

    if(NOT TARGET OpenCL)
        message(WARNING "OpenCL ICD loader を構築できないため GGML_OPENCL を無効化します。")
        return()
    endif()

    set_target_properties(OpenCL PROPERTIES POSITION_INDEPENDENT_CODE ON)
    if(ANDROID)
        target_link_libraries(OpenCL PRIVATE dl log)
    endif()
    set(OpenCL_INCLUDE_DIR "${_opencl_headers}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    set(NEZUMI_OPENCL_READY ON PARENT_SCOPE)
    message(STATUS "llama_bridge: OpenCL backend enabled (headers=${_opencl_headers})")
endfunction()

function(nezumi_setup_vulkan)
    set(NEZUMI_VULKAN_READY OFF PARENT_SCOPE)
    if(NOT LLAMA_BRIDGE_VULKAN)
        return()
    endif()

    if(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}")
        list(APPEND CMAKE_PREFIX_PATH "$ENV{VULKAN_SDK}")
        set(CMAKE_PREFIX_PATH "${CMAKE_PREFIX_PATH}" PARENT_SCOPE)
    endif()

    set(_glslc "")
    if(DEFINED ENV{VULKAN_SDK})
        if(EXISTS "$ENV{VULKAN_SDK}/Bin/glslc.exe")
            set(_glslc "$ENV{VULKAN_SDK}/Bin/glslc.exe")
        elseif(EXISTS "$ENV{VULKAN_SDK}/bin/glslc")
            set(_glslc "$ENV{VULKAN_SDK}/bin/glslc")
        endif()
    endif()
    if(NOT _glslc)
        file(GLOB _sdk_glslc "C:/VulkanSDK/*/Bin/glslc.exe")
        if(_sdk_glslc)
            list(GET _sdk_glslc -1 _glslc)
        endif()
    endif()
    if(NOT _glslc)
        find_program(_glslc NAMES glslc glslc.exe)
    endif()
    if(NOT _glslc)
        message(WARNING "glslc が見つからないため GGML_VULKAN を無効化します。Vulkan SDK を入れてください。")
        return()
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${_glslc}" CACHE FILEPATH "" FORCE)

    set(_spirv_inc "")
    if(DEFINED ENV{VULKAN_SDK})
        if(EXISTS "$ENV{VULKAN_SDK}/Include/spirv/unified1/spirv.hpp")
            set(_spirv_inc "$ENV{VULKAN_SDK}/Include")
        elseif(EXISTS "$ENV{VULKAN_SDK}/include/spirv/unified1/spirv.hpp")
            set(_spirv_inc "$ENV{VULKAN_SDK}/include")
        endif()
    endif()
    if(NOT _spirv_inc)
        FetchContent_Declare(spirv_headers
            GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
            GIT_TAG vulkan-sdk-1.4.309.0
            GIT_SHALLOW TRUE
        )
        FetchContent_MakeAvailable(spirv_headers)
        if(DEFINED spirv_headers_SOURCE_DIR AND EXISTS "${spirv_headers_SOURCE_DIR}/include/spirv/unified1/spirv.hpp")
            set(_spirv_inc "${spirv_headers_SOURCE_DIR}/include")
        endif()
    endif()
    if(NOT _spirv_inc)
        message(WARNING "SPIRV-Headers が見つからないため GGML_VULKAN を無効化します。")
        return()
    endif()
    set(SPIRV_HEADERS_INCLUDE_DIR "${_spirv_inc}" CACHE PATH "" FORCE)
    set(SPIRV-Headers_DIR "${NEZUMI_CMAKE_DIR}" CACHE PATH "" FORCE)

    if(CMAKE_CROSSCOMPILING AND NOT GGML_VULKAN_SHADERS_GEN_TOOLCHAIN)
        unset(_host_cc CACHE)
        unset(_host_cxx CACHE)
        if(CMAKE_HOST_WIN32)
            find_program(_vswhere NAMES vswhere.exe
                PATHS "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer"
                NO_CMAKE_FIND_ROOT_PATH)
            if(_vswhere)
                execute_process(
                    COMMAND "${_vswhere}" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
                    OUTPUT_VARIABLE _vs_root
                    OUTPUT_STRIP_TRAILING_WHITESPACE
                    ERROR_QUIET)
                if(_vs_root)
                    file(GLOB _cl_candidates "${_vs_root}/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe")
                    if(_cl_candidates)
                        list(GET _cl_candidates -1 _host_cxx)
                        set(_host_cc "${_host_cxx}")
                    endif()
                endif()
            endif()
        endif()
        if(NOT _host_cc)
            find_program(_host_cc NAMES cl gcc clang NO_CMAKE_FIND_ROOT_PATH)
            find_program(_host_cxx NAMES cl g++ clang++ NO_CMAKE_FIND_ROOT_PATH)
        endif()
        if(NOT _host_cc OR NOT _host_cxx)
            message(WARNING "Vulkan shaders-gen 用のホストコンパイラが見つからないため GGML_VULKAN を無効化します。")
            return()
        endif()
        set(HOST_C_COMPILER "${_host_cc}")
        set(HOST_CXX_COMPILER "${_host_cxx}")
        configure_file(
            ${LLAMA_CPP_UPSTREAM_DIR}/ggml/src/ggml-vulkan/cmake/host-toolchain.cmake.in
            ${CMAKE_BINARY_DIR}/nezumi-vulkan-host-toolchain.cmake
            @ONLY)
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/nezumi-vulkan-host-toolchain.cmake" CACHE FILEPATH "" FORCE)
    endif()

    set(NEZUMI_VULKAN_READY ON PARENT_SCOPE)
    message(STATUS "llama_bridge: Vulkan backend enabled (glslc=${_glslc})")
endfunction()

if(EXISTS ${LLAMA_CPP_UPSTREAM_DIR}/CMakeLists.txt)
    nezumi_setup_opencl()
    nezumi_setup_vulkan()

    # --- クロスコンパイル必須設定 ---
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.7a" CACHE STRING "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # NDK に OpenMP パッケージが無い
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)   # Android 未対応
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)    # NDK stable API に無い

    # --- 不要な成果物を作らない（ライブラリのみ欲しい） ---
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE) # チャットテンプレート等で後々必要
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)   # マルチモーダル (mtmd) ライブラリを生成

    # --- バックエンド ---
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)   # 量子化 GEMM の速度を優先
    set(GGML_OPENCL ${NEZUMI_OPENCL_READY} CACHE BOOL "" FORCE)
    set(GGML_VULKAN ${NEZUMI_VULKAN_READY} CACHE BOOL "" FORCE)
    if(NEZUMI_OPENCL_READY)
        set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    endif()

    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    endif()

    add_subdirectory(${LLAMA_CPP_UPSTREAM_DIR} ${CMAKE_BINARY_DIR}/llama_cpp_upstream)

        # Keep the app itself in Debug while compiling the llama.cpp inference
        # targets with Release-level optimization in every app build variant.
    foreach(LLAMA_HOT_TARGET llama ggml ggml-base ggml-cpu ggml-opencl ggml-vulkan mtmd llama-common kleidiai)
        if(TARGET ${LLAMA_HOT_TARGET})
            target_compile_options(${LLAMA_HOT_TARGET} PRIVATE
                -O3)
            target_compile_definitions(${LLAMA_HOT_TARGET} PRIVATE NDEBUG)
            # Android ABI 固有の最適化
            if(ANDROID_ABI STREQUAL "arm64-v8a")
                target_compile_options(${LLAMA_HOT_TARGET} PRIVATE
                    -march=armv8.7a
                )
            endif()
        endif()
    endforeach()
else()
    message(WARNING "vendor/llama.cpp が見つかりません。llama_bridge はビルドされません：${LLAMA_CPP_UPSTREAM_DIR}")
endif()

if(TARGET llama)
    add_library(llama_bridge SHARED llama_bridge.cpp)

    # chat.h は common_chat_msg の to_json_oaicompat 等で C++17 を要求する
    target_compile_features(llama_bridge PRIVATE cxx_std_17)

    set(LLAMA_BRIDGE_LINK_LIBS llama ${LOG_LIB} android)
    # llama-common: チャットテンプレート (minja/Jinja) 実装で後続フェーズにて使用。
    # 今はリンクのみ通しておき、llama_bridge.cpp からは未参照でも問題ない。
    if(TARGET llama-common)
        list(APPEND LLAMA_BRIDGE_LINK_LIBS llama-common)
    endif()
    # mtmd: マルチモーダル (画像/音声) 統合
    if(TARGET mtmd)
        list(APPEND LLAMA_BRIDGE_LINK_LIBS mtmd)
    endif()
    if(TARGET ggml-opencl)
        target_compile_definitions(llama_bridge PRIVATE NEZUMI_LLAMA_OPENCL=1)
    endif()
    if(TARGET ggml-vulkan)
        target_compile_definitions(llama_bridge PRIVATE NEZUMI_LLAMA_VULKAN=1)
    endif()

    # llama-common / mtmd のヘッダはインクルード dir が伝播しないため明示指定
    target_include_directories(llama_bridge PRIVATE
            ${LLAMA_CPP_UPSTREAM_DIR}/include
            ${LLAMA_CPP_UPSTREAM_DIR}/ggml/include
            ${LLAMA_CPP_UPSTREAM_DIR}/common
            ${LLAMA_CPP_UPSTREAM_DIR}/vendor
            ${LLAMA_CPP_UPSTREAM_DIR}/vendor/nlohmann
            ${LLAMA_CPP_UPSTREAM_DIR}/tools/mtmd
    )

    target_link_libraries(llama_bridge PRIVATE ${LLAMA_BRIDGE_LINK_LIBS})
    target_link_options(llama_bridge PRIVATE
            -Wl,-z,max-page-size=16384
            -Wl,-z,common-page-size=16384
    )
endif()
