<#
.SYNOPSIS
    共享工具：自动定位 Android SDK / adb / emulator。
    查找顺序：环境变量 → local.properties → 常见默认安装位置。
#>

function Get-AndroidSdkPath {
    [CmdletBinding()]
    param(
        [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
    )

    # 1) 环境变量优先
    foreach ($name in @("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value -and (Test-Path $value)) { return $value }
    }

    # 2) local.properties 里的 sdk.dir
    $localProps = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProps) {
        try {
            $text = [System.IO.File]::ReadAllText($localProps, [System.Text.Encoding]::UTF8)
            $match = [regex]::Match($text, '(?m)^\s*sdk\.dir\s*=\s*(.+?)\s*$')
            if ($match.Success) {
                $path = $match.Groups[1].Value.Replace('\:', ':').Replace('\\', '\')
                if (Test-Path $path) { return $path }
            }
        } catch {
            # 忽略解析异常，继续尝试其他方式
        }
    }

    # 3) 常见默认位置
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
        (Join-Path $env:USERPROFILE "Android\Sdk")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }

    throw @"
未找到 Android SDK。请任选一种方式配置后重试：
  1. 设置环境变量 ANDROID_SDK_ROOT 指向 SDK 目录
  2. 在项目根目录创建 local.properties，写入  sdk.dir=<你的SDK路径>
     （可参考仓库内的 local.properties.example）
"@
}

function Get-AdbPath {
    param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))
    return Join-Path (Get-AndroidSdkPath -ProjectRoot $ProjectRoot) "platform-tools\adb.exe"
}

function Get-EmulatorPath {
    param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))
    return Join-Path (Get-AndroidSdkPath -ProjectRoot $ProjectRoot) "emulator\emulator.exe"
}
