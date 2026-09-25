<#
.SYNOPSIS
    把本地提交推送到 GitHub。
    自动探测 HTTPS / SSH 通道，优先使用当前可用的方式（国内网络下 github.com:443 时常不可达，
    而 ssh.github.com:443 通常可用）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\push-to-github.ps1
#>
$ErrorActionPreference = "Continue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$Repo = "123456789-Liu1/JMH-Android"

Write-Host "=== 探测通道 ===" -ForegroundColor Cyan
$httpsOk = Test-NetConnection -ComputerName github.com -Port 443 `
    -InformationLevel Quiet -WarningAction SilentlyContinue
$sshOk = Test-NetConnection -ComputerName ssh.github.com -Port 443 `
    -InformationLevel Quiet -WarningAction SilentlyContinue

Write-Host "  HTTPS (github.com:443)    : $(if ($httpsOk) { '可用' } else { '不可用' })"
Write-Host "  SSH   (ssh.github.com:443): $(if ($sshOk) { '可用' } else { '不可用' })"

if (-not $httpsOk -and -not $sshOk) {
    Write-Host "`n两种通道都不可用。请检查网络，或为 Git 配置代理后重试：" -ForegroundColor Red
    Write-Host "  git config --global http.proxy http://127.0.0.1:端口"
    exit 1
}

# 优先 HTTPS；不可用时切到 SSH
if ($httpsOk) {
    Write-Host "`n使用 HTTPS 推送 ..." -ForegroundColor Cyan
    git remote set-url origin "https://github.com/$Repo.git"
} else {
    Write-Host "`nHTTPS 不可用，改用 SSH 推送 ..." -ForegroundColor Yellow
    git remote set-url origin "git@github.com:$Repo.git"
}

git push origin master
if ($LASTEXITCODE -eq 0) {
    Write-Host "`n推送成功。" -ForegroundColor Green
} else {
    Write-Host "`n推送失败。若使用 SSH，请确认公钥已添加到 GitHub 账号。" -ForegroundColor Red
    Write-Host "可用以下命令查看/添加公钥："
    Write-Host "  gh auth refresh -h github.com -s admin:public_key"
    Write-Host "  gh ssh-key add `$env:USERPROFILE\.ssh\id_ed25519.pub --title 'my machine'"
    exit 1
}
