<#
.SYNOPSIS
    把已有的 APK 安装到当前设备 / 模拟器（不重新构建）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\install-apk.ps1
    powershell -ExecutionPolicy Bypass -File scripts\install-apk.ps1 -ApkPath apk\JMH-v1.0-debug.apk
#>
param(
    [string]$ApkPath = "",
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Adb = Get-AdbPath -ProjectRoot $ProjectRoot

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $candidates = @(
        (Join-Path $ProjectRoot "apk\JMH-v1.0-debug.apk"),
        (Join-Path $ProjectRoot "apk\JMH-v1.0.apk"),
        (Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk")
    )
    $ApkPath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
    throw "未找到 APK，请先运行 scripts\build-apk.ps1 或 gradlew assembleDebug。"
}

$devices = & $Adb devices | Select-String -Pattern "device$"
if (-not $devices) {
    throw "没有已连接的设备。请先启动模拟器（scripts\start-emulator.ps1）。"
}

Write-Host "[..] 正在安装 $ApkPath ..." -ForegroundColor Cyan
& $Adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw "adb install 失败。" }

if (-not $NoLaunch) {
    Write-Host "[..] 正在启动 JMH ..." -ForegroundColor Cyan
    & $Adb shell am start -n "com.jmh.app/.MainActivity" | Out-Null
}

Write-Host "[OK] 完成。" -ForegroundColor Green
