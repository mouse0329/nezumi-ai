# Run mnn_sd_generate_cli.exe under a memory watchdog.
# Kills the process the moment its working set exceeds -MaxMB, logging memory
# per poll interval to a CSV so we can see whether growth is a flat one-time
# peak (safe) or a linear per-step leak (needs an engine fix).
#
# Usage:
#   .\watchdog_run.ps1 -ModelDir C:\models\CuteYukiMix -Steps 4 -Width 512 -Height 512 -MaxMB 4000

param(
    [Parameter(Mandatory = $true)][string]$ModelDir,
    [string]$Prompt = "1girl, cute, cat ears, masterpiece",
    [string]$Negative = "worst quality, blurry",
    [int]$Steps = 4,
    [int]$Width = 512,
    [int]$Height = 512,
    [int]$Seed = 12345,
    [string]$Backend = "cpu",
    [int]$MaxMB = 4000,
    [int]$PollMs = 500,
    [string]$Exe = ".\mnn-sd-engine\build\mnn_sd_generate_cli.exe",
    [string]$Out = "watchdog_out.ppm"
)

$ErrorActionPreference = "Stop"
$logPath = "watchdog_memlog.csv"
"elapsed_sec,mem_mb" | Out-File -FilePath $logPath -Encoding utf8

function Quote-Arg([string]$s) {
    if ($s -match '[\s,"]') {
        return '"' + ($s -replace '"', '\"') + '"'
    }
    return $s
}

$argList = @(
    (Quote-Arg $ModelDir), (Quote-Arg $Prompt),
    "--negative", (Quote-Arg $Negative),
    "--steps", $Steps,
    "--width", $Width,
    "--height", $Height,
    "--seed", $Seed,
    "--backend", $Backend,
    "--out", $Out
)

Write-Host "Starting: $Exe $($argList -join ' ')" -ForegroundColor Cyan
$proc = Start-Process -FilePath $Exe -ArgumentList $argList `
    -RedirectStandardOutput "watchdog_stdout.log" `
    -RedirectStandardError "watchdog_stderr.log" `
    -PassThru

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$killed = $false
$maxSeen = 0

while (-not $proc.HasExited) {
    Start-Sleep -Milliseconds $PollMs
    try {
        $p = Get-Process -Id $proc.Id -ErrorAction Stop
        $memMB = [math]::Round($p.WorkingSet64 / 1MB, 1)
    }
    catch {
        break  # process already gone
    }
    $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    "$elapsed,$memMB" | Out-File -FilePath $logPath -Append -Encoding utf8
    if ($memMB -gt $maxSeen) { $maxSeen = $memMB }
    Write-Host "[$elapsed s] mem = $memMB MB" -ForegroundColor $(if ($memMB -gt $MaxMB * 0.8) { "Yellow" } else { "Gray" })

    if ($memMB -gt $MaxMB) {
        Write-Host "!!! Exceeded $MaxMB MB -> killing process $($proc.Id) !!!" -ForegroundColor Red
        Stop-Process -Id $proc.Id -Force
        $killed = $true
        break
    }
}

if (-not $killed -and $proc.HasExited) {
    Write-Host "Process exited on its own (exit code $($proc.ExitCode)). Peak mem seen: $maxSeen MB" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== stdout (last 20 lines) ===" -ForegroundColor DarkGray
Get-Content "watchdog_stdout.log" -Tail 20
Write-Host ""
Write-Host "=== stderr (last 20 lines) ===" -ForegroundColor DarkGray
Get-Content "watchdog_stderr.log" -Tail 20
Write-Host ""
Write-Host "Memory log written to $logPath (elapsed_sec,mem_mb) -- check for linear growth vs flat plateau." -ForegroundColor Cyan