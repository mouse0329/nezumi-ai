cmake_minimum_required(VERSION 3.22.1)

project(nezumi_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)

option(LLAMA_BRIDGE_VULKAN "Enable Vulkan backend for llama_bridge (vendor/llama.cpp)" OFF)

# Debug APK でも推論コードを軽く最適化する。大規模なモデル実装へ -O3 を
# 一括適用すると NDK clang のコンパイルメモリを圧迫するため、-O1 に留める。
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O1 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O1 -DNDEBUG")
# =========================
# 本家 llama.cpp (vendor) — llama_bridge 用
# =========================
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
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)    # マルチモーダル(mtmd)ライブラリを生成

    # --- バックエンド ---
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ${LLAMA_BRIDGE_VULKAN} CACHE BOOL "" FORCE)

    # llama_bridge と vendor ライブラリを同じプロセスで安全に扱うため STATIC 指定
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

    add_subdirectory(${LLAMA_CPP_UPSTREAM_DIR} ${CMAKE_BINARY_DIR}/llama_cpp_upstream)

    # Keep the Android Debug build usable while optimizing the hot inference
    # targets. Applying -O3 to every model translation unit can exhaust NDK
    # compiler memory; -O2 gives the core kernels a useful optimization level.
    foreach(LLAMA_HOT_TARGET llama ggml ggml-base ggml-cpu mtmd llama-common)
        if(TARGET ${LLAMA_HOT_TARGET})
            target_compile_options(${LLAMA_HOT_TARGET} PRIVATE -O2)
        endif()
    endforeach()
else()
    message(WARNING "vendor/llama.cpp が見つかりません。llama_bridge はビルドされません: ${LLAMA_CPP_UPSTREAM_DIR}")
endif()

if(TARGET llama)
    add_library(llama_bridge SHARED llama_bridge.cpp)

    # chat.h は common_chat_msg の to_json_oaicompat 等で C++17 を要求する
    target_compile_features(llama_bridge PRIVATE cxx_std_17)

    set(LLAMA_BRIDGE_LINK_LIBS llama ${LOG_LIB} android)
    # llama-common: チャットテンプレート(minja/Jinja)実装で後続フェーズにて使用。
    # 今はリンクのみ通しておき、llama_bridge.cpp からは未参照でも問題ない。
    if(TARGET llama-common)
        list(APPEND LLAMA_BRIDGE_LINK_LIBS llama-common)
    endif()
    # mtmd: マルチモーダル(画像/音声)統合
    if(TARGET mtmd)
        list(APPEND LLAMA_BRIDGE_LINK_LIBS mtmd)
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