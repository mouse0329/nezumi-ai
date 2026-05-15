#!/bin/bash
# llama.cpp自動セットアップスクリプト

set -e

echo "=== nezumi-ai Desktop - llama.cpp Setup ==="
echo ""

# OSを検出
OS="unknown"
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="linux"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    OS="macos"
elif [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]]; then
    OS="windows"
fi

echo "Detected OS: $OS"
echo ""

# llama.cppのバージョン
LLAMA_VERSION="b4313"
LLAMA_REPO="https://github.com/ggml-org/llama.cpp"

# ビルドディレクトリ
BUILD_DIR="$(pwd)/desktop/libs"
mkdir -p "$BUILD_DIR"

echo "Building llama.cpp from source..."
echo ""

# 一時ディレクトリ
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"

# llama.cppをクローン
echo "Cloning llama.cpp..."
git clone --depth 1 "$LLAMA_REPO" llama.cpp
cd llama.cpp

# ビルド
echo "Building llama.cpp..."
mkdir build
cd build

if [[ "$OS" == "windows" ]]; then
    cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
    cmake --build . --config Release
    cp Release/llama.dll "$BUILD_DIR/"
    echo "✓ llama.dll copied to $BUILD_DIR"
elif [[ "$OS" == "linux" ]]; then
    cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
    cmake --build . --config Release
    cp libllama.so "$BUILD_DIR/"
    echo "✓ libllama.so copied to $BUILD_DIR"
elif [[ "$OS" == "macos" ]]; then
    cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=ON
    cmake --build . --config Release
    cp libllama.dylib "$BUILD_DIR/"
    echo "✓ libllama.dylib copied to $BUILD_DIR"
fi

# クリーンアップ
cd ../../..
rm -rf "$TEMP_DIR"

echo ""
echo "=== Setup Complete ==="
echo "llama.cpp library installed to: $BUILD_DIR"
echo ""
echo "Next steps:"
echo "1. Download a GGUF model (e.g., gemma-2b-it-q4_k_m.gguf)"
echo "2. Run: ./gradlew :desktop:run"
echo "3. Set model path in Settings"
