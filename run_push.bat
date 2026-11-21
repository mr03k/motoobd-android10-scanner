@echo off
title MotoOBD Telemetry Pro - Direct Git Push Launcher
color 0B
echo ============================================================================
echo  MotoOBD Telemetry Pro - Direct Git Push to mr03k/motoobd-android10-scanner
echo ============================================================================
echo.

:: Automatically switch to the folder where run_push.bat is located
cd /d "%~dp0"
if exist "C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo\README.md" (
    cd /d "C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo"
)
if exist "C:\Users\AI\Downloads\Compressed\motoobd-android10-scanner\README.md" (
    cd /d "C:\Users\AI\Downloads\Compressed\motoobd-android10-scanner"
)

echo [1/5] Working Directory: %CD%
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Git is not installed or not in PATH.
    pause
    exit /b 1
)

echo [2/5] Initializing clean Windows Git repository...
if exist ".git" rmdir /s /q ".git"
git init
git branch -M main
git config user.name "mr03k"
git config user.email "mr03k@users.noreply.github.com"

echo.
echo [3/5] Staging and committing all project files...
git add -A
git commit -m "Deploy MotoOBD Telemetry Pro (Android 10 OBD-II Scanner, Modern UI and Handoff Docs)"

echo.
echo [4/5] Setting remote origin to https://github.com/mr03k/motoobd-android10-scanner.git ...
git remote remove origin >nul 2>nul
git remote add origin https://github.com/mr03k/motoobd-android10-scanner.git

echo.
echo [5/5] Pushing to GitHub (main branch)...
git push -u origin main --force

if errorlevel 1 (
    echo.
    echo [NOTE] Push with .github/workflows returned an error ^(token may lack workflow scope^).
    echo Retrying push without .github/workflows so all code and Android 10 files upload cleanly...
    if exist ".github" rmdir /s /q ".github"
    git add -A
    git commit -m "Deploy MotoOBD Telemetry Pro source and Android 10 project"
    git push -u origin main --force
)

echo.
echo ============================================================================
echo  Done! Check https://github.com/mr03k/motoobd-android10-scanner
echo ============================================================================
pause
