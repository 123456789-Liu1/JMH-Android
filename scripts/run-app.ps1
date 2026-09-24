<#
.SYNOPSIS
    一键流程：启动模拟器 → 构建 → 安装 → 启动 JMH。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-app.ps1
    powershell -ExecutionPolicy Bypass -File scripts\run-app.ps1 -SkipBuild
#>
param(
    [switch]$ColdBoot,
    [switch]$SkipBuild,
    [string]$AvdName = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Adb = Get-AdbPath -ProjectRoot $ProjectRoot
$Gradlew = Join-Path $ProjectRoot "gradlew.bat"

# 1) 确保模拟器在线
$startArgs = @(
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "start-emulator.ps1")
)
if (-not [string]::IsNullOrWhiteSpace($AvdName)) {
    $startArgs += @("-AvdName", $AvdName)
}
if ($ColdBoot) { $startArgs += "-ColdBoot" }

& powershell @startArgs
if ($LASTEXITCODE -ne 0) { throw "模拟器启动失败。" }

# 2) 构建并安装
if (-not $SkipBuild) {
    Write-Host "[..] 正在构建并安装（installDebug）..." -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & $Gradlew installDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradlew installDebug 失败。" }
    } finally {
        Pop-Location
    }
}

# 3) 启动应用
Write-Host "[..] 正在启动 JMH ..." -ForegroundColor Cyan
& $Adb shell am start -n "com.jmh.app/.MainActivity" | Out-Null

Write-Host "[OK] JMH 已在模拟器上运行。" -ForegroundColor Green
