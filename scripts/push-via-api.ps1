<#
.SYNOPSIS
    通过 GitHub Git Data API 推送本地提交。

.DESCRIPTION
    适用场景：国内网络下 github.com:443 不可达（git push 失败），
    但 api.github.com 可用（gh 命令正常）。

    注意：多个本地提交会被压缩为远端的一个提交，之后需要重新对齐本地历史。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\push-via-api.ps1 -Message "feat: xxx"
#>
param(
    [string]$Message = "chore(release): v2.0.0`n`n加密通信与检查更新（详见 CHANGELOG）"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$Repo = "123456789-Liu1/JMH-Android"
$Branch = "master"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Step($text) { Write-Host $text -ForegroundColor Cyan }
function Save-Json($obj, $path) {
    [System.IO.File]::WriteAllText($path, ($obj | ConvertTo-Json -Depth 6 -Compress), $Utf8NoBom)
}

# ---------------------------------------------------------------- 1. 远程状态
Write-Step "[1/5] 读取远程状态 ..."
$refJson = gh api "repos/$Repo/git/ref/heads/$Branch" | ConvertFrom-Json
$parentSha = $refJson.object.sha
Write-Host "      远程 HEAD  : $parentSha"

$commitJson = gh api "repos/$Repo/git/commits/$parentSha" | ConvertFrom-Json
$baseTreeSha = $commitJson.tree.sha
Write-Host "      base tree : $baseTreeSha"

# ---------------------------------------------------------------- 2. 变更清单
Write-Step "[2/5] 计算本地相对远程的变更 ..."
$diffOutput = @(git diff --name-status "$parentSha..HEAD")
if ($diffOutput.Count -eq 0 -or [string]::IsNullOrWhiteSpace($diffOutput[0])) {
    Write-Host "      没有需要推送的变更。" -ForegroundColor Green
    exit 0
}

$changes = @()
foreach ($line in $diffOutput) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split "`t"
    if ($parts.Count -lt 2) { continue }
    $changes += [pscustomobject]@{
        Status = $parts[0].Trim()
        Path   = $parts[1].Trim()
    }
}
Write-Host "      共 $($changes.Count) 项变更"

# ---------------------------------------------------------------- 3. 创建 blob
Write-Step "[3/5] 上传文件内容 ..."
$blobFile = Join-Path $env:TEMP "gh-blob.json"
$treeEntries = @()

foreach ($change in $changes) {
    if ($change.Status -eq "D") {
        $treeEntries += @{ path = $change.Path; mode = "100644"; type = "blob"; sha = $null }
        Write-Host "      - 删除 $($change.Path)"
        continue
    }

    $fullPath = Join-Path $ProjectRoot $change.Path
    if (-not (Test-Path $fullPath)) { continue }

    $base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($fullPath))
    Save-Json @{ content = $base64; encoding = "base64" } $blobFile

    $blob = gh api --method POST "repos/$Repo/git/blobs" --input $blobFile | ConvertFrom-Json
    $mode = if ($change.Path -match "gradlew$" -or $change.Path -match "\.sh$") { "100755" } else { "100644" }
    $treeEntries += @{ path = $change.Path; mode = $mode; type = "blob"; sha = $blob.sha }
    Write-Host "      + $($change.Path)"
}

# ---------------------------------------------------------------- 4. 创建 tree / commit
Write-Step "[4/5] 创建目录树与提交 ..."
$treeFile = Join-Path $env:TEMP "gh-tree.json"
Save-Json @{ base_tree = $baseTreeSha; tree = $treeEntries } $treeFile
$newTree = gh api --method POST "repos/$Repo/git/trees" --input $treeFile | ConvertFrom-Json
Write-Host "      tree : $($newTree.sha)"

$commitFile = Join-Path $env:TEMP "gh-commit.json"
Save-Json @{
    message = $Message
    tree    = $newTree.sha
    parents = @($parentSha)
} $commitFile
$newCommit = gh api --method POST "repos/$Repo/git/commits" --input $commitFile | ConvertFrom-Json
Write-Host "      commit : $($newCommit.sha)"

# ---------------------------------------------------------------- 5. 更新分支
Write-Step "[5/5] 更新分支引用 ..."
$refFile = Join-Path $env:TEMP "gh-ref.json"
Save-Json @{ sha = $newCommit.sha; force = $false } $refFile
gh api --method PATCH "repos/$Repo/git/refs/heads/$Branch" --input $refFile | Out-Null

Write-Host "`n[OK] 已推送到远端。" -ForegroundColor Green
Write-Host "     https://github.com/$Repo/commits/$Branch"
Write-Host ""
Write-Host "提示：远端历史被压缩为单个提交，本地历史与远端不一致。" -ForegroundColor Yellow
Write-Host "     下次网络正常时执行以下命令对齐（不会丢代码）：" -ForegroundColor Yellow
Write-Host "       git fetch origin"
Write-Host "       git reset --soft origin/$Branch"
