<#
.SYNOPSIS
    关闭当前正在运行的模拟器。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\stop-emulator.ps1
#>
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Adb = Get-AdbPath -ProjectRoot $ProjectRoot

$devices = & $Adb devices | Select-String -Pattern "emulator-\d+\s+device"
if (-not $devices) {
    Write-Host "[OK] 当前没有正在运行的模拟器。" -ForegroundColor Green
    exit 0
}

Write-Host "[..] 正在关闭模拟器 ..." -ForegroundColor Cyan
& $Adb emu kill | Out-Null
Start-Sleep -Seconds 3

Write-Host "[OK] 已发送关闭指令。" -ForegroundColor Green
