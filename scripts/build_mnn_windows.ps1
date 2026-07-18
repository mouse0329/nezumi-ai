# Build MNN + mnn-sd-engine for Windows host (x64), for local txt2img testing
# without deploying to Android every time.
#
# Run from "Developer PowerShell for VS 2022" (or any shell where cl.exe / ninja
# are on PATH), from the repo root or anywhere -- paths are resolved relative to
# this script's location.
#
# Usage:
#   .\scripts\build_mnn_windows.ps1
#   .\scripts\build_mnn_windows.ps1 -SkipMnn          # skip MNN rebuild if already built
#   .\scripts\build_mnn_windows.ps1 -Opencl            # also enable MNN_OPENCL
#   .\scripts\build_mnn_windows.ps1 -Config Debug -Jobs 4

param(
    [string]$Config = "Release",
    [int]$Jobs = 8,
    [switch]$SkipMnn,   # Skip MNN rebuild if libMNN / MNN.dll already exists
    [switch]$Opencl     # Build MNN with MNN_OPENCL=ON (needs an OpenCL-capable GPU/driver)
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$MnnRoot = Join-Path $Root "third_party\MNN"
$EngineRoot = Join-Path $Root "mnn-sd-engine"

$vsRoot = "C:\Program Files\Microsoft Visual Studio\18\Community"
$cmakeCandidates = @(
    "$vsRoot\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe",
    "$env:ProgramFiles\CMake\bin\cmake.exe",
    "cmake.exe"
)
$cmakeExe = $null
foreach ($candidate in $cmakeCandidates) {
    if ($candidate -and (Test-Path $candidate)) { $cmakeExe = $candidate; break }
}
if (-not $cmakeExe) {
    $cmd = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($cmd) { $cmakeExe = $cmd.Source }
}
if (-not $cmakeExe) { throw "cmake.exe not found. Install Visual Studio C++ / CMake and retry." }

$ninjaCandidates = @(
    "$vsRoot\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe",
    "$env:ProgramFiles\CMake\bin\ninja.exe",
    "ninja.exe"
)
$ninjaExe = $null
foreach ($candidate in $ninjaCandidates) {
    if ($candidate -and (Test-Path $candidate)) { $ninjaExe = $candidate; break }
}
if (-not $ninjaExe) {
    $cmd = Get-Command ninja.exe -ErrorAction SilentlyContinue
    if ($cmd) { $ninjaExe = $cmd.Source }
}
if (-not $ninjaExe) { throw "ninja.exe not found. Install Ninja or use a Developer PowerShell environment." }

$clExe = $null
$clCmd = Get-Command cl.exe -ErrorAction SilentlyContinue
if ($clCmd) { $clExe = $clCmd.Source }
if (-not $clExe) {
    $clMatches = Get-ChildItem -Path "$vsRoot\VC\Tools\MSVC" -Filter cl.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($clMatches) { $clExe = $clMatches.FullName }
}
if (-not $clExe) { $clExe = "cl.exe" }

$clDir = if ($clExe -and (Test-Path $clExe)) { Split-Path -Parent $clExe } else { $null }
$rcExe = $null
$rcCandidates = @(
    "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\rc.exe",
    "C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64\rc.exe",
    "C:\Program Files (x86)\Windows Kits\10\bin\x64\rc.exe"
)
foreach ($candidate in $rcCandidates) {
    if ($candidate -and (Test-Path $candidate)) { $rcExe = $candidate; break }
}

$sdkBin = if ($rcExe) { Split-Path -Parent $rcExe } else { $null }
$prefixPath = @((Split-Path -Parent $cmakeExe), (Split-Path -Parent $ninjaExe))
if ($clDir) { $prefixPath += $clDir }
if ($sdkBin) { $prefixPath += $sdkBin }
$env:PATH = ($prefixPath + $env:PATH.Split([IO.Path]::PathSeparator, [StringSplitOptions]::RemoveEmptyEntries) | Select-Object -Unique) -join [IO.Path]::PathSeparator
$env:CC = $clExe
$env:CXX = $clExe
$env:ASM = $clExe
$env:RC = $rcExe

$vcLib = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.51.36231\lib\x64"
$vcInclude = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.51.36231\include"
$sdkLib = "C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\um\x64"
$sdkUcrt = "C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\ucrt\x64"
$sdkInclude = "C:\Program Files (x86)\Windows Kits\10\Include\10.0.26100.0\ucrt"
$sdkUmInclude = "C:\Program Files (x86)\Windows Kits\10\Include\10.0.26100.0\um"
$sdkSharedInclude = "C:\Program Files (x86)\Windows Kits\10\Include\10.0.26100.0\shared"
$env:LIB = "$vcLib;$sdkLib;$sdkUcrt"
$env:LIBPATH = "$vcLib;$sdkLib;$sdkUcrt"
$env:INCLUDE = "$vcInclude;$sdkInclude;$sdkUmInclude;$sdkSharedInclude"
$env:INCLUDEPATH = "$vcInclude;$sdkInclude;$sdkUmInclude;$sdkSharedInclude"

function Assert-Tool($name, $pathHint) {
    if ($name -eq 'cmake' -and $cmakeExe) { return }
    if ($name -eq 'ninja' -and $ninjaExe) { return }
    if ($name -eq 'cl' -and $clExe) { return }
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "'$name' not found on PATH. Install Visual Studio C++ / CMake or use Developer PowerShell."
    }
}

Assert-Tool cmake $cmakeExe
Assert-Tool ninja $ninjaExe
Assert-Tool cl $clExe

Write-Host "=== 1/3 Build MNN (Windows x64, $Config) ===" -ForegroundColor Cyan
$MnnBuild = Join-Path $MnnRoot "build"
$LibMnn = Join-Path $MnnBuild "MNN.dll"

if ($SkipMnn -and (Test-Path $LibMnn)) {
    Write-Host "Skipping MNN build (MNN.dll exists, omit -SkipMnn to rebuild)" -ForegroundColor Yellow
} else {
    $openclFlag = if ($Opencl) { "ON" } else { "OFF" }

    $mnnArgs = @(
        '-S', $MnnRoot,
        '-B', $MnnBuild,
        '-G', 'Ninja',
        "-DCMAKE_BUILD_TYPE=$Config",
        '-DMNN_BUILD_CONVERTER=OFF',
        '-DMNN_BUILD_SHARED_LIBS=ON',
        '-DMNN_LOW_MEMORY=ON',
        "-DMNN_OPENCL=$openclFlag",
        '-DMNN_BUILD_TEST=OFF',
        '-DMNN_BUILD_BENCHMARK=OFF',
        '-DMNN_BUILD_TOOLS=OFF',
        '-DMNN_BUILD_DEMO=OFF',
        '-DMNN_BUILD_TRAIN=OFF',
        '-DMNN_BUILD_QUANTOOLS=OFF'
    )
    & $cmakeExe @mnnArgs

    & $cmakeExe --build $MnnBuild -j $Jobs
}

if (-not (Test-Path $LibMnn)) { throw "MNN.dll missing: $LibMnn" }
Write-Host "OK: $LibMnn" -ForegroundColor Green

Write-Host "=== 2/3 Build mnn-sd-engine (host CLI tools) ===" -ForegroundColor Cyan
$EngineBuild = Join-Path $EngineRoot "build"
if (Test-Path $EngineBuild) {
    Remove-Item -Recurse -Force $EngineBuild
}
New-Item -ItemType Directory -Path $EngineBuild -Force | Out-Null

& $cmakeExe -S $EngineRoot -B $EngineBuild -G Ninja `
    "-DCMAKE_BUILD_TYPE=$Config" `
    "-DCMAKE_MAKE_PROGRAM=$ninjaExe" `
    "-DMNN_ROOT=$MnnRoot" `
    '-DMNN_SD_BUILD_PROBE_CLI=ON'

& $cmakeExe --build $EngineBuild -j $Jobs

$Cli = Join-Path $EngineBuild "mnn_sd_generate_cli.exe"
if (-not (Test-Path $Cli)) { throw "mnn_sd_generate_cli.exe missing: $Cli" }
Write-Host "OK: $Cli" -ForegroundColor Green

Write-Host "=== 3/3 Copy MNN.dll next to the CLI tools ===" -ForegroundColor Cyan
Copy-Item $LibMnn $EngineBuild -Force

Get-ChildItem $EngineBuild -Filter "*.exe" | Format-Table Name, Length
Write-Host "Done." -ForegroundColor Green
Write-Host ""
Write-Host "Example run:" -ForegroundColor DarkGray
Write-Host "  $Cli C:\path\to\out\CuteYukiMix \"1girl, cute, cat ears\" --steps 20 --backend cpu --out out.ppm" -ForegroundColor DarkGray
