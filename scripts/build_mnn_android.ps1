# Build MNN + mnn-sd-engine for Android arm64 (Windows / PowerShell)
param(
    [string]$Abi = "arm64-v8a",
    [int]$Jobs = 8,
    [switch]$SkipMnn  # Skip MNN rebuild if libMNN.so already exists
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$MnnRoot = Join-Path $Root "third_party\MNN"
$EngineRoot = Join-Path $Root "mnn-sd-engine"
$JniDest = Join-Path $Root "app\src\main\jniLibs\arm64-v8a"

$cmake = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.31.6\bin\cmake.exe"
$ninja = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.31.6\bin\ninja.exe"
$ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\30.0.14904198"

if (-not (Test-Path $cmake)) { throw "cmake not found: $cmake" }
if (-not (Test-Path $ndk)) { throw "NDK not found: $ndk" }

Write-Host "=== 1/3 Build MNN (arm64) ===" -ForegroundColor Cyan
$MnnBuild = Join-Path $MnnRoot "build-android-arm64"
$LibMnn = Join-Path $MnnBuild "libMNN.so"

if ($SkipMnn -and (Test-Path $LibMnn)) {
    Write-Host "Skipping MNN build (libMNN.so exists, use -SkipMnn:$false to rebuild)" -ForegroundColor Yellow
} else {
    if (Test-Path $MnnBuild) {
        Remove-Item $MnnBuild -Recurse -Force
    }
    & $cmake -S $MnnRoot -B $MnnBuild -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a `
        -DANDROID_PLATFORM=android-30 `
        -DCMAKE_BUILD_TYPE=Release `
        -DANDROID_STL=c++_static `
        -DMNN_USE_LOGCAT=ON `
        -DMNN_OPENCL=ON `
        -DMNN_SEP_BUILD=OFF `
        -DMNN_BUILD_FOR_ANDROID_COMMAND=ON `
        -DMNN_LOW_MEMORY=ON `
        -DMNN_BUILD_TEST=OFF `
        -DMNN_BUILD_BENCHMARK=OFF `
        -DMNN_BUILD_TOOLS=OFF `
        -DMNN_BUILD_QUANTOOLS=OFF `
        -DMNN_BUILD_CONVERTER=OFF `
        -DMNN_BUILD_TRAIN=OFF `
        -DMNN_BUILD_DEMO=OFF `
        -DMNN_BUILD_LLM=OFF `
        -DMNN_BUILD_DIFFUSION=OFF

    & $cmake --build $MnnBuild -j $Jobs
}

if (-not (Test-Path $LibMnn)) { throw "libMNN.so missing: $LibMnn" }
Write-Host "OK: $LibMnn" -ForegroundColor Green

Write-Host "=== 2/3 Build mnn-sd-engine ===" -ForegroundColor Cyan
$EngineBuild = Join-Path $Root "build\mnn-sd-android"
if (Test-Path $EngineBuild) {
    Remove-Item $EngineBuild -Recurse -Force
}
& $cmake -S $EngineRoot -B $EngineBuild -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    -DANDROID_ABI=arm64-v8a `
    -DANDROID_PLATFORM=android-30 `
    -DCMAKE_BUILD_TYPE=Release `
    -DANDROID_STL=c++_static `
    "-DMNN_ROOT=$MnnRoot" `
    "-DMNN_ANDROID_LIB=$LibMnn" `
    -DMNN_SD_BUILD_PROBE_CLI=OFF

& $cmake --build $EngineBuild -j $Jobs

Write-Host "=== 3/3 Deploy to jniLibs ===" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $JniDest | Out-Null
Copy-Item $LibMnn $JniDest -Force
Copy-Item (Join-Path $EngineBuild "libmnn_sd_engine.so") $JniDest -Force
Copy-Item (Join-Path $EngineBuild "libmnn_sd_jni.so") $JniDest -Force

Get-ChildItem $JniDest -Filter "lib*.so" | Format-Table Name, Length
Write-Host "Done." -ForegroundColor Green
