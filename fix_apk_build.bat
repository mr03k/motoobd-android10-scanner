@echo off
title MotoOBD Telemetry Pro - Fix GitHub Actions APK Build
color 0B
echo ============================================================================
echo  Fixing GitHub Actions Android 10 APK Workflow (Removes broken setup-android)
echo ============================================================================
echo.

cd /d "%~dp0"
if exist "C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo\README.md" (
    cd /d "C:\Users\AI\Downloads\Compressed\motoobd_android10_github_repo"
)
if exist "C:\Users\AI\Downloads\Compressed\motoobd-android10-scanner\README.md" (
    cd /d "C:\Users\AI\Downloads\Compressed\motoobd-android10-scanner"
)

echo [1/4] Working Directory: %CD%

if not exist ".github\workflows" mkdir ".github\workflows"
if not exist "app\src\main\java\com\motoobd\scanner" mkdir "app\src\main\java\com\motoobd\scanner"

echo [2/4] Writing fixed AndroidManifest.xml, app/build.gradle, and build-apk.yml...

(
echo ^<?xml version="1.0" encoding="utf-8"?^>
echo ^<manifest xmlns:android="http://schemas.android.com/apk/res/android"^>
echo.
echo     ^<uses-permission android:name="android.permission.BLUETOOTH" /^>
echo     ^<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" /^>
echo     ^<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /^>
echo     ^<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" /^>
echo     ^<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /^>
echo     ^<uses-permission android:name="android.permission.INTERNET" /^>
echo.
echo     ^<uses-feature android:name="android.hardware.bluetooth" android:required="true" /^>
echo     ^<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" /^>
echo.
echo     ^<application
echo         android:allowBackup="true"
echo         android:label="MotoOBD Pro (Android 10)"
echo         android:supportsRtl="true"
echo         android:theme="@android:style/Theme.DeviceDefault.NoActionBar"^>
echo         ^<activity
echo             android:name=".MainActivity"
echo             android:exported="true"
echo             android:configChanges="orientation|screenSize|keyboardHidden"^>
echo             ^<intent-filter^>
echo                 ^<action android:name="android.intent.action.MAIN" /^>
echo                 ^<category android:name="android.intent.category.LAUNCHER" /^>
echo             ^</intent-filter^>
echo         ^</activity^>
echo     ^</application^>
echo ^</manifest^>
) > "app\src\main\AndroidManifest.xml"

(
echo plugins {
echo     id 'com.android.application'
echo     id 'org.jetbrains.kotlin.android'
echo }
echo.
echo android {
echo     namespace 'com.motoobd.scanner'
echo     compileSdk 34
echo.
echo     defaultConfig {
echo         applicationId "com.motoobd.scanner"
echo         minSdk 26
echo         targetSdk 29
echo         versionCode 1
echo         versionName "1.0.0"
echo     }
echo     compileOptions {
echo         sourceCompatibility JavaVersion.VERSION_17
echo         targetCompatibility JavaVersion.VERSION_17
echo     }
echo     kotlinOptions {
echo         jvmTarget = '17'
echo     }
echo }
) > "app\build.gradle"

(
echo org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
echo android.useAndroidX=true
) > "gradle.properties"

(
echo name: Build Android 10 OBD-II APK
echo.
echo on:
echo   push:
echo     branches: [ "main" ]
echo   workflow_dispatch:
echo.
echo jobs:
echo   build-android10-apk:
echo     runs-on: ubuntu-latest
echo     steps:
echo       - name: Checkout repository
echo         uses: actions/checkout@v4
echo.
echo       - name: Set up JDK 17
echo         uses: actions/setup-java@v4
echo         with:
echo           java-version: '17'
echo           distribution: 'temurin'
echo.
echo       - name: Install Android SDK Platform 29 and 34
echo         run: ^|
echo           yes ^| $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses ^|^| true
echo           $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-29" "platforms;android-34" "build-tools;34.0.0"
echo.
echo       - name: Setup Gradle 8.4
echo         uses: gradle/actions/setup-gradle@v3
echo         with:
echo           gradle-version: '8.4'
echo.
echo       - name: Ensure WebView Asset Exists
echo         run: ^|
echo           mkdir -p app/src/main/assets
echo           if [ -f "web/obd2_diagnostic_app.html" ]; then
echo             cp web/obd2_diagnostic_app.html app/src/main/assets/index.html
echo           fi
echo.
echo       - name: Build Debug APK ^(targetSdk 29 / Android 10^)
echo         run: ^|
echo           gradle assembleDebug --no-daemon --stacktrace
echo           cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/motoobd-android10-debug.apk
echo.
echo       - name: Upload Android 10 APK Artifact
echo         uses: actions/upload-artifact@v4
echo         with:
echo           name: motoobd-android10-debug-apk
echo           path: app/build/outputs/apk/debug/motoobd-android10-debug.apk
) > ".github\workflows\build-apk.yml"

echo [3/4] Committing fixed APK build configuration...
git add -A
git commit -m "Fix GitHub Actions Android SDK setup (remove deprecated tools package) and AGP 8 JVM 17 target"

echo [4/4] Pushing fix to https://github.com/mr03k/motoobd-android10-scanner.git ...
git push -u origin main --force

echo.
echo ============================================================================
echo  SUCCESS! Open https://github.com/mr03k/motoobd-android10-scanner/actions
echo  to watch the green APK build and download motoobd-android10-debug-apk!
echo ============================================================================
pause
