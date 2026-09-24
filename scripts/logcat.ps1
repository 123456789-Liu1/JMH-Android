<#
.SYNOPSIS
    查看 JMH 的运行日志（默认只显示崩溃与 JMH 相关输出）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\logcat.ps1
    powershell -ExecutionPolicy Bypass -File scripts\logcat.ps1 -All
#>
param(
    [switch]$All
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Adb = Get-AdbPath -ProjectRoot $ProjectRoot

$devices = & $Adb devices | Select-String -Pattern "device$"
if (-not $devices) {
    throw "没有已连接的设备。请先启动模拟器（scripts\start-emulator.ps1）。"
}

Write-Host "[..] 按 Ctrl+C 退出日志查看" -ForegroundColor Cyan

if ($All) {
    & $Adb logcat -v time
} else {
    & $Adb logcat -v time AndroidRuntime:E JMH:D System.err:W *:S
}
