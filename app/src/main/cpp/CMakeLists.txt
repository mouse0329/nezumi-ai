cmake_minimum_required(VERSION 3.22.1)

project(nezumi_rnllama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)

# --- rnllama sources (vendored from llama.rn 0.12.0-rc.9) ---
set(RNLLAMA_DIR ${CMAKE_SOURCE_DIR}/llama_rn)

include_directories(
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
target_link_libraries(nezumi_rnllama_jni PRIVATE rnllama_core ${LOG_LIB} android)
target_compile_options(nezumi_rnllama_jni PRIVATE -O3 -DNDEBUG -pthread)
target_link_options(nezumi_rnllama_jni PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)

