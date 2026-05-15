@echo off
REM llama.cpp自動セットアップスクリプト (Windows)

echo === nezumi-ai Desktop - llama.cpp Setup ===
echo.

set LLAMA_VERSION=b4313
set BUILD_DIR=%cd%\desktop\libs
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

echo Building llama.cpp from source...
echo.

REM 一時ディレクトリ
set TEMP_DIR=%TEMP%\llama-build-%RANDOM%
mkdir "%TEMP_DIR%"
cd /d "%TEMP_DIR%"

REM llama.cppをクローン
echo Cloning llama.cpp...
git clone --depth 1 https://github.com/ggml-org/llama.cpp llama.cpp
cd llama.cpp

REM ビルド
echo Building llama.cpp...
mkdir build
cd build

cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release

copy Release\llama.dll "%BUILD_DIR%\"
echo ✓ llama.dll copied to %BUILD_DIR%

REM クリーンアップ
cd /d "%BUILD_DIR%\.."
rmdir /s /q "%TEMP_DIR%"

echo.
echo === Setup Complete ===
echo llama.cpp library installed to: %BUILD_DIR%
echo.
echo Next steps:
echo 1. Download a GGUF model (e.g., gemma-2b-it-q4_k_m.gguf)
echo 2. Run: gradlew.bat :desktop:run
echo 3. Set model path in Settings
pause
