<#
.SYNOPSIS
    Build the release APK (minified + resource shrunk) and copy it to the apk/ folder.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1
#>
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradlew     = Join-Path $ProjectRoot "gradlew.bat"
$OutDir      = Join-Path $ProjectRoot "apk"

Write-Host "[..] Building release APK ..." -ForegroundColor Cyan
Push-Location $ProjectRoot
try {
    & $Gradlew assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradlew assembleRelease failed." }

    Write-Host "[..] Building debug APK ..." -ForegroundColor Cyan
    & $Gradlew assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug failed." }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$releaseSrc = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
$debugSrc   = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"

Copy-Item $releaseSrc (Join-Path $OutDir "JMH-v1.0.apk") -Force
Copy-Item $debugSrc   (Join-Path $OutDir "JMH-v1.0-debug.apk") -Force

$relSize = [math]::Round((Get-Item (Join-Path $OutDir "JMH-v1.0.apk")).Length / 1MB, 2)
$dbgSize = [math]::Round((Get-Item (Join-Path $OutDir "JMH-v1.0-debug.apk")).Length / 1MB, 2)

Write-Host "[OK] apk\JMH-v1.0.apk       ($relSize MB)  <- recommended" -ForegroundColor Green
Write-Host "[OK] apk\JMH-v1.0-debug.apk ($dbgSize MB)" -ForegroundColor Green
