#!/usr/bin/env bash
# 同じ vendor/llama.cpp から llama-mtmd-cli をビルドする (JNI との比較用)。
#
# 使い方:
#   scripts/build_mtmd_cli.sh                      # ホスト (x86_64/aarch64 Linux) 向け CPU ビルド
#   TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#   ANDROID_ABI=arm64-v8a ANDROID_PLATFORM=android-31 \
#       scripts/build_mtmd_cli.sh                  # Android arm64 向け (端末で実行可能)
#
# 実行:
#   NEZUMI_DIAG=1 build/mtmd-cli/bin/llama-mtmd-cli \
#     -m model.gguf --mmproj mmproj.gguf --image test.jpg \
#     -p "これを見てください" --image-max-tokens 1024 -ngl 0 -c 4096 --temp 0.7 -n 16
#   (DIAG| 行は stderr に出る)
#
# 注意: JNI と同じ数値条件にするには、llamaInit の既定値 (flash_attn, kv_unified 等)
#       を DIAG|llama_cfg 行と見比べて合わせる。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/vendor/llama.cpp"
BUILD="${BUILD_DIR:-$ROOT/build/mtmd-cli}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

if [ ! -f "$SRC/CMakeLists.txt" ]; then
    echo "vendor/llama.cpp が空です。先に: git submodule update --init --recursive" >&2
    exit 1
fi

CMAKE_ARGS=(
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE:-Release}"
    -DLLAMA_BUILD_TOOLS=ON
    -DLLAMA_BUILD_COMMON=ON
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=OFF
    -DLLAMA_CURL=OFF
    -DLLAMA_OPENSSL=OFF
    -DGGML_OPENMP="${GGML_OPENMP:-ON}"
    # 診断ヘッダ (llama_diag.h) を JNI と共有する
    -DCMAKE_CXX_FLAGS="-I$ROOT/app/src/main/cpp"
    -DCMAKE_C_FLAGS="-I$ROOT/app/src/main/cpp"
)

if [ -n "${TOOLCHAIN_FILE:-}" ]; then
    CMAKE_ARGS+=(
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"
        -DANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
        -DANDROID_PLATFORM="${ANDROID_PLATFORM:-android-31}"
    )
fi

cmake -S "$SRC" -B "$BUILD" "${CMAKE_ARGS[@]}"
cmake --build "$BUILD" --target llama-mtmd-cli -j "$JOBS"

echo "built: $BUILD/bin/llama-mtmd-cli"
