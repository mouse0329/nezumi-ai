# CuteYukiMix（または任意 SD モデル）を実機で MNN I/O プローブする
param(
    [string]$ModelDir,
    [string]$DeviceDir = "/sdcard/nezumi_probe/CuteYukiMix",
    [switch]$SkipPush,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ModelDir)) {
    $ModelDir = Join-Path $Root "models\CuteYukiMix"
}
if (-not (Test-Path $ModelDir)) {
    throw "Model dir not found: $ModelDir"
}

Write-Host "=== Device ===" -ForegroundColor Cyan
& adb devices -l

if (-not $SkipBuild) {
    Write-Host "=== Build + install (debug + androidTest) ===" -ForegroundColor Cyan
    Push-Location $Root
    try {
        & .\gradlew.bat :app:installDebug :app:installDebugAndroidTest --no-daemon
    } finally {
        Pop-Location
    }
}

if (-not $SkipPush) {
    Write-Host "=== Push model -> $DeviceDir (large, may take several minutes) ===" -ForegroundColor Yellow
    $parent = Split-Path $DeviceDir -Parent
    & adb shell "mkdir -p $parent"
    & adb push "$ModelDir/." $DeviceDir
}

Write-Host "=== Run instrumented probe ===" -ForegroundColor Cyan
Push-Location $Root
try {
    & .\gradlew.bat :app:connectedDebugAndroidTest `
        "-Pandroid.testInstrumentationRunnerArguments.modelPath=$DeviceDir" `
        --no-daemon
} finally {
    Pop-Location
}

Write-Host "=== Pull report ===" -ForegroundColor Cyan
$Out = Join-Path $Root "build\mnn_sd_probe_device.txt"
foreach ($pkg in @("com.nezumi_ai.open", "com.nezumi_ai")) {
    $remote = "/data/data/$pkg/files/mnn_sd_probe_instrumented.txt"
    & adb exec-out run-as $pkg cat $remote 2>$null | Out-File -Encoding utf8 $Out
    $item = Get-Item $Out -ErrorAction SilentlyContinue
    if ($null -ne $item -and $item.Length -gt 0) {
        Write-Host "Report: $Out (package=$pkg)" -ForegroundColor Green
        Get-Content $Out -Head 40
        break
    }
}

Write-Host "Done." -ForegroundColor Green
