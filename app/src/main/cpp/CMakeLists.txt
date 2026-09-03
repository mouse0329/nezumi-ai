cmake_minimum_required(VERSION 3.22.1)

project(nezumi_rnllama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)

# =========================
# GPU option (Vulkan)
# =========================
# rnllama_core (旧方式) 用フラグ。名前は GGML_VULKAN のままにすると
# 本家 vendor/llama.cpp の同名キャッシュ変数と衝突し、add_subdirectory 実行時に
# 意図せず上書きされるため、専用の変数名に分離している。
option(GGML_VULKAN_RNLLAMA "Enable Vulkan backend (rnllama_core)" ON)

# 本家 vendor/llama.cpp 側の Vulkan は SPIRV-Headers / SPIRV-Tools という
# 外部依存が別途必要で、rnllama_core の簡易 Vulkan 実装とは要件が異なる。
# 依存が揃うまでは独立して OFF にできるようにする。
option(LLAMA_BRIDGE_VULKAN "Enable Vulkan backend for llama_bridge (vendor/llama.cpp)" OFF)

# =========================
# rnllama sources
# =========================
set(RNLLAMA_DIR ${CMAKE_SOURCE_DIR}/llama_rn)

set(RNLLAMA_INCLUDE_DIRS
        ${RNLLAMA_DIR}
        ${RNLLAMA_DIR}/common
        ${RNLLAMA_DIR}/common/jinja
        ${RNLLAMA_DIR}/ggml-cpu
        ${RNLLAMA_DIR}/tools/mtmd
)

# ⚠️ 存在しない自動検出（GLOB）で余計なゴミを拾わないよう、安全にフィルタリングするか明示的に指定します
file(GLOB MODEL_FILES           ${RNLLAMA_DIR}/models/*.cpp)
file(GLOB GGML_CPU_C_FILES      ${RNLLAMA_DIR}/ggml-cpu/*.c)
file(GLOB GGML_CPU_CPP_FILES    ${RNLLAMA_DIR}/ggml-cpu/*.cpp)
file(GLOB GGML_CPU_AMX_FILES    ${RNLLAMA_DIR}/ggml-cpu/amx/*.cpp)
file(GLOB LLAMA_FILES           ${RNLLAMA_DIR}/llama*.cpp)
file(GLOB MTMD_FILES            ${RNLLAMA_DIR}/tools/mtmd/*.cpp)
file(GLOB MTMD_MODEL_FILES      ${RNLLAMA_DIR}/tools/mtmd/models/*.cpp)
file(GLOB COMMON_FILES          ${RNLLAMA_DIR}/common/*.cpp)
file(GLOB JINJA_FILES           ${RNLLAMA_DIR}/common/jinja/*.cpp)

# もし不要なファイルが混ざるならここで明示的に除外
list(REMOVE_ITEM COMMON_FILES
        ${RNLLAMA_DIR}/common/json-partial.cpp
        ${RNLLAMA_DIR}/common/regex-partial.cpp
)

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

if (ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND RNLLAMA_ARCH_SOURCE_FILES
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/quants.c
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/repack.cpp
            ${RNLLAMA_DIR}/ggml-cpu/arch/arm/cpu-feats.cpp
    )
    set(RNLLAMA_CPU_FLAGS "-march=armv8.2-a")
else()
    add_compile_definitions(LM_GGML_CPU_GENERIC)
endif()

# =========================
# Core library
# =========================
add_library(rnllama_core SHARED
        ${RNLLAMA_SOURCE_FILES}
        ${RNLLAMA_ARCH_SOURCE_FILES}
)

target_include_directories(rnllama_core PRIVATE ${RNLLAMA_INCLUDE_DIRS})

# CPU ALWAYS ON + optional GPU flag
target_compile_options(rnllama_core PRIVATE
        -DLM_GGML_USE_CPU
        -pthread
        -fvectorize
        -ffp-model=fast
        -fno-finite-math-only
        -D_GNU_SOURCE
        ${RNLLAMA_CPU_FLAGS}
        -O3
        -DNDEBUG
)

# =========================
# Vulkan (only if exists)
# =========================
if(GGML_VULKAN_RNLLAMA)
    target_compile_definitions(rnllama_core PRIVATE GGML_USE_VULKAN)
    find_library(VULKAN_LIB vulkan)
    if(VULKAN_LIB)
        target_link_libraries(rnllama_core PRIVATE ${VULKAN_LIB})
    endif()
endif()

target_link_libraries(rnllama_core PRIVATE ${LOG_LIB} android)

target_link_options(rnllama_core PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)

# =========================
# JNI bridges
# =========================
add_library(nezumi_rnllama_jni SHARED NezumiRnLlamaJni.cpp)
target_include_directories(nezumi_rnllama_jni PRIVATE ${RNLLAMA_INCLUDE_DIRS})
target_link_libraries(nezumi_rnllama_jni PRIVATE rnllama_core ${LOG_LIB} android)


# =========================
# 本家 llama.cpp (vendor) — llama_bridge 用
# =========================
# rnllama_core / nezumi_rnllama_jni とは完全に独立したビルド。
# llama_bridge.cpp はこちらの llama.h / ggml.h のみを参照する。
set(LLAMA_CPP_UPSTREAM_DIR ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp)

if(EXISTS ${LLAMA_CPP_UPSTREAM_DIR}/CMakeLists.txt)
    # --- クロスコンパイル必須設定 ---
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)     # ホストCPUではなくAndroidターゲット向けにビルド
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)     # NDKにOpenMPパッケージが無い
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)  # Android未対応
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)   # NDK stable API に無い

    # --- 不要な成果物を作らない（ライブラリのみ欲しい） ---
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE) # チャットテンプレート等で後々必要

    # --- バックエンド ---
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ${LLAMA_BRIDGE_VULKAN} CACHE BOOL "" FORCE)

    # llama.cpp 側は SHARED/STATIC を自動判定するため、
    # rnllama_core と衝突しないよう明示的に STATIC 指定
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

    add_subdirectory(${LLAMA_CPP_UPSTREAM_DIR} ${CMAKE_BINARY_DIR}/llama_cpp_upstream)
else()
    message(WARNING "vendor/llama.cpp が見つかりません。llama_bridge はビルドされません: ${LLAMA_CPP_UPSTREAM_DIR}")
endif()

if(TARGET llama)
    add_library(llama_bridge SHARED llama_bridge.cpp)

    set(LLAMA_BRIDGE_LINK_LIBS llama ${LOG_LIB} android)
    # llama-common: チャットテンプレート(minja/Jinja)実装で後続フェーズにて使用。
    # 今はリンクのみ通しておき、llama_bridge.cpp からは未参照でも問題ない。
    if(TARGET llama-common)
        list(APPEND LLAMA_BRIDGE_LINK_LIBS llama-common)
    endif()

    target_link_libraries(llama_bridge PRIVATE ${LLAMA_BRIDGE_LINK_LIBS})
    target_link_options(llama_bridge PRIVATE
            -Wl,-z,max-page-size=16384
            -Wl,-z,common-page-size=16384
    )
endif()