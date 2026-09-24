<#
.SYNOPSIS
    启动 Android 模拟器并等待系统完全就绪。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-emulator.ps1
    powershell -ExecutionPolicy Bypass -File scripts\start-emulator.ps1 -AvdName Pixel_9 -ColdBoot
#>
param(
    [string]$AvdName = "",
    [switch]$ColdBoot
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Emulator = Get-EmulatorPath -ProjectRoot $ProjectRoot
$Adb = Get-AdbPath -ProjectRoot $ProjectRoot

if (-not (Test-Path $Emulator)) { throw "找不到模拟器程序：$Emulator" }
if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

# 未指定虚拟机时的选择顺序：
#   1. 项目根目录的 .avd 文件（本地偏好，已被 .gitignore 忽略）
#   2. 第一个可用 AVD
if ([string]::IsNullOrWhiteSpace($AvdName)) {
    $avdFile = Join-Path $ProjectRoot ".avd"
    if (Test-Path $avdFile) {
        $preferred = ([System.IO.File]::ReadAllText($avdFile, [System.Text.Encoding]::UTF8)).Trim()
        if (-not [string]::IsNullOrWhiteSpace($preferred)) { $AvdName = $preferred }
    }
}

if ([string]::IsNullOrWhiteSpace($AvdName)) {
    $avds = @(& $Emulator -list-avds 2>$null | Where-Object { $_ -and $_ -notmatch '^\s*$' })
    if ($avds.Count -eq 0) {
        throw "没有可用的虚拟机，请先用 avdmanager 或 Android Studio 创建一个。"
    }
    $AvdName = $avds[0]
    Write-Host "[..] 未指定 AVD，自动选用：$AvdName" -ForegroundColor Yellow
} else {
    Write-Host "[..] 使用虚拟机：$AvdName" -ForegroundColor Cyan
}

# 若已有设备在线则直接复用
$online = & $Adb devices | Select-String -Pattern "emulator-\d+\s+device"
if ($online) {
    Write-Host "[OK] 已有模拟器在运行：" -ForegroundColor Green
    $online | ForEach-Object { Write-Host "     $_" }
    exit 0
}

$emuArgs = @(
    "-avd", $AvdName,
    "-gpu", "auto",
    "-netdelay", "none",
    "-netspeed", "full",
    "-no-boot-anim"
)
if ($ColdBoot) { $emuArgs += "-no-snapshot-load" }

Write-Host "[..] 正在启动模拟器 '$AvdName' ..." -ForegroundColor Cyan
Start-Process -FilePath $Emulator -ArgumentList $emuArgs | Out-Null

Write-Host "[..] 等待设备连接 ..." -ForegroundColor Cyan
& $Adb wait-for-device

Write-Host "[..] 等待系统启动完成 ..." -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(6)
$booted = ""
while ($booted.Trim() -ne "1") {
    if ((Get-Date) -gt $deadline) { throw "等待模拟器启动超时（6 分钟）。" }
    Start-Sleep -Seconds 2
    $booted = (& $Adb shell getprop sys.boot_completed 2>$null) -join ""
}

& $Adb shell input keyevent 82 | Out-Null   # 解锁屏幕

Write-Host "[OK] 模拟器已就绪。" -ForegroundColor Green
& $Adb devices
