# 実機で SD モデルの MNN I/O をプローブし、レポートを pull する
param(
    [string]$Package = "com.nezumi_ai",
    [string]$Tag = "MnnSdModule"
)

$ErrorActionPreference = "Stop"

Write-Host "1. アプリで 設定 > 画像生成モデル > 展開 > 「MNN I/O プローブ」を実行" -ForegroundColor Yellow
Write-Host "2. logcat を監視します (Ctrl+C で終了)" -ForegroundColor Cyan
Write-Host ""

adb logcat -c
adb logcat -s "$Tag`:I" "MnnSdNative`:I" "MnnSdJni`:I"
