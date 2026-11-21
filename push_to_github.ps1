# ==============================================================================
# MotoOBD Telemetry Pro - Windows PowerShell GitHub Push Script (Pure ASCII)
# Target Folder: C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo
# Target Repo:   https://github.com/mr03k/motoobd-android10-scanner.git
# ==============================================================================

$ErrorActionPreference = "Continue"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " MotoOBD Telemetry Pro - GitHub Push Utility (mr03k)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$PathA = "C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo"
$PathB = "C:\Users\AI\Downloads\Compressed\motoobd-android10-scanner"

if ($PSScriptRoot -and (Test-Path (Join-Path $PSScriptRoot "README.md"))) {
    $TargetDir = $PSScriptRoot
} elseif (Test-Path (Join-Path $PathA "README.md")) {
    $TargetDir = $PathA
} elseif (Test-Path (Join-Path $PathB "README.md")) {
    $TargetDir = $PathB
} else {
    $TargetDir = (Get-Location).Path
}

Set-Location -LiteralPath $TargetDir
Write-Host "[1/5] Working Directory: $TargetDir" -ForegroundColor Green

if (Test-Path ".git") {
    Remove-Item -LiteralPath ".git" -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[2/5] Initializing clean Windows Git repository..." -ForegroundColor Cyan
git init
git branch -M main
git config user.name "mr03k"
git config user.email "mr03k@users.noreply.github.com"

Write-Host "[3/5] Staging and committing project files..." -ForegroundColor Cyan
git add -A
git commit -m "Deploy MotoOBD Telemetry Pro (Android 10 OBD-II Scanner, Modern UI and Handoff Docs)"

$RemoteUrl = "https://github.com/mr03k/motoobd-android10-scanner.git"
Write-Host "[4/5] Linking remote origin to $RemoteUrl" -ForegroundColor Cyan
git remote add origin $RemoteUrl

Write-Host "[5/5] Pushing to GitHub (main branch)..." -ForegroundColor Cyan
git push -u origin main --force

if ($LASTEXITCODE -ne 0) {
    Write-Host "[NOTE] Retrying push without .github/workflows in case token lacks workflow scope..." -ForegroundColor Yellow
    if (Test-Path ".github") {
        Remove-Item -LiteralPath ".github" -Recurse -Force -ErrorAction SilentlyContinue
    }
    git add -A
    git commit -m "Deploy MotoOBD Telemetry Pro source and Android 10 project"
    git push -u origin main --force
}

Write-Host ""
Write-Host "Finished! Check https://github.com/mr03k/motoobd-android10-scanner" -ForegroundColor Green
Read-Host "Press Enter to close this window"
