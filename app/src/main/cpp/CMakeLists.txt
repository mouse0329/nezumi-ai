cmake_minimum_required(VERSION 3.22.1)

project(nezumi_rnllama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)

# --- rnllama sources (vendored from llama.rn 0.12.0-rc.9) ---
set(RNLLAMA_DIR ${CMAKE_SOURCE_DIR}/llama_rn)

# llama_rn のヘッダは rnllama ターゲットのみに付与する（stable-diffusion.cpp の ggml と混ざるのを防ぐ）
set(RNLLAMA_INCLUDE_DIRS
        ${RNLLAMA_DIR}
        ${RNLLAMA_DIR}/common
        ${RNLLAMA_DIR}/common/jinja
        ${RNLLAMA_DIR}/ggml-cpu
        ${RNLLAMA_DIR}/tools/mtmd
)

# Use the same file grouping style as llama.rn's rnllama/CMakeLists.txt, but CPU-only.
file(GLOB MODEL_FILES           ${RNLLAMA_DIR}/models/*.cpp)
file(GLOB GGML_CPU_C_FILES      ${RNLLAMA_DIR}/ggml-cpu/*.c)
file(GLOB GGML_CPU_CPP_FILES    ${RNLLAMA_DIR}/ggml-cpu/*.cpp)
file(GLOB GGML_CPU_AMX_FILES    ${RNLLAMA_DIR}/ggml-cpu/amx/*.cpp)
file(GLOB LLAMA_FILES           ${RNLLAMA_DIR}/llama*.cpp)
file(GLOB MTMD_FILES            ${RNLLAMA_DIR}/tools/mtmd/*.cpp)
file(GLOB MTMD_MODEL_FILES      ${RNLLAMA_DIR}/tools/mtmd/models/*.cpp)
file(GLOB COMMON_FILES          ${RNLLAMA_DIR}/common/*.cpp)
file(GLOB JINJA_FILES           ${RNLLAMA_DIR}/common/jinja/*.cpp)

set(RNLLAMA_SOURCE_FILES
        ${RNLLAMA_DIR}/ggml.c
        ${RNLLAMA_DIR}/ggml-alloc.c
        ${RNLLAMA_DIR}/ggml-backend.cpp
        ${RNLLAMA_DIR}/ggml-backend-dl.cpp
        ${RNLLAMA_DIR}/ggml-backend-meta.cpp
        ${RNLLAMA_DIR}/ggml-backend-reg.cpp
        ${RNLLAMA_DIR}/ggml-opt.cpp
        ${RNLLAMA_DIR}/ggml-threading.cpp
        ${RNLLAMA_DIR}/ggml-quants.c
        ${RNLLAMA_DIR}/gguf.cpp

        ${GGML_CPU_C_FILES}
        ${GGML_CPU_CPP_FILES}
        ${GGML_CPU_AMX_FILES}

        ${LLAMA_FILES}
        ${RNLLAMA_DIR}/unicode-data.cpp
        ${RNLLAMA_DIR}/unicode.cpp
        ${RNLLAMA_DIR}/anyascii.c

        ${COMMON_FILES}
        ${JINJA_FILES}

        ${MTMD_MODEL_FILES}
        ${MTMD_FILES}

        ${RNLLAMA_DIR}/rn-llama.cpp
        ${RNLLAMA_DIR}/rn-completion.cpp
        ${RNLLAMA_DIR}/rn-tts.cpp
        ${RNLLAMA_DIR}/rn-slot.cpp
        ${RNLLAMA_DIR}/rn-slot-manager.cpp

        ${MODEL_FILES}
)

set(RNLLAMA_ARCH_SOURCE_FILES "")
set(RNLLAMA_CPU_FLAGS "")

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Enable AArch64 optimized quants/repack implementations
    list(APPEND RNLLAMA_ARCH_SOURCE_FILES
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/quants.c
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/repack.cpp
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/cpu-feats.cpp
    )
    set(RNLLAMA_CPU_FLAGS "-march=armv8.2-a")
else()
    # Fallback generic build
    add_compile_definitions(LM_GGML_CPU_GENERIC)
endif()

add_library(rnllama_core SHARED ${RNLLAMA_SOURCE_FILES} ${RNLLAMA_ARCH_SOURCE_FILES})
target_include_directories(rnllama_core PRIVATE ${RNLLAMA_INCLUDE_DIRS})
target_link_libraries(rnllama_core PRIVATE ${LOG_LIB} android)
target_compile_options(rnllama_core PRIVATE
        -DLM_GGML_USE_CPU
        -DLM_GGML_USE_CPU_REPACK
        -pthread
        -fvectorize
        -ffp-model=fast
        -fno-finite-math-only
        -flto
        -D_GNU_SOURCE
        ${RNLLAMA_CPU_FLAGS}
        -O3
        -DNDEBUG
)
target_link_options(rnllama_core PRIVATE
        # 16KB page-size devices (Android 15+) compatibility
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)

# --- Our minimal JNI bridge (no React / no JSI) ---
add_library(nezumi_rnllama_jni SHARED NezumiRnLlamaJni.cpp)
target_include_directories(nezumi_rnllama_jni PRIVATE ${RNLLAMA_INCLUDE_DIRS})
target_link_libraries(nezumi_rnllama_jni PRIVATE rnllama_core ${LOG_LIB} android)
target_compile_options(nezumi_rnllama_jni PRIVATE -O3 -DNDEBUG -pthread)
target_link_options(nezumi_rnllama_jni PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)

# --- stable-diffusion.cpp (optional submodule at cpp/stable-diffusion.cpp) ---
set(NEZUMI_SD_SRC_DIR ${CMAKE_SOURCE_DIR}/stable-diffusion.cpp)
if (EXISTS "${NEZUMI_SD_SRC_DIR}/CMakeLists.txt")
    set(SD_BUILD_EXAMPLES OFF CACHE BOOL "sd: examples" FORCE)
    set(SD_WEBP OFF CACHE BOOL "sd: webp" FORCE)
    set(SD_WEBM OFF CACHE BOOL "sd: webm" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "ggml: openmp" FORCE)
    # Vulkanオフロードはクロスコンパイル時に問題があるため無効化
    # set(GGML_VULKAN ON CACHE BOOL "ggml: vulkan" FORCE)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    add_subdirectory(${NEZUMI_SD_SRC_DIR} nezumi_sd_subbuild)
    add_library(nezumi_sd SHARED sd_jni.cpp)
    target_include_directories(nezumi_sd PRIVATE ${NEZUMI_SD_SRC_DIR}/include)
    target_link_libraries(nezumi_sd PRIVATE stable-diffusion ${LOG_LIB} android)
    target_compile_options(nezumi_sd PRIVATE -O3 -DNDEBUG)
    target_link_options(nezumi_sd PRIVATE
            -Wl,-z,max-page-size=16384
            -Wl,-z,common-page-size=16384
    )
else()
    message(WARNING "stable-diffusion.cpp missing under ${NEZUMI_SD_SRC_DIR}; building nezumi_sd stub only.")
    add_library(nezumi_sd SHARED sd_jni_stub.cpp)
    target_link_libraries(nezumi_sd PRIVATE ${LOG_LIB} android)
endif()

