@echo off
setlocal enabledelayedexpansion
title VORTEX - One-Click USB Installer

echo =======================================================
echo     VORTEX Pair - One-Click USB Installer
echo =======================================================
echo.

:: 1. Check if ADB is available in PATH
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] ADB ^(Android Debug Bridge^) was not found in your PATH.
    echo.
    echo Please make sure Android SDK platform-tools or Android Studio is installed,
    echo or add platform-tools to your system PATH:
    echo   Default path: %%LOCALAPPDATA%%\Android\Sdk\platform-tools
    echo.
    pause
    exit /b 1
)

:: 2. Check for connected Android device via USB
echo [1/4] Checking for connected Android phone via USB...
adb devices > adb_devices.txt
set DEVICE_FOUND=0
for /f "skip=1 tokens=1,2" %%i in (adb_devices.txt) do (
    if "%%j"=="device" (
        set DEVICE_ID=%%i
        set DEVICE_FOUND=1
    )
)
del adb_devices.txt

if %DEVICE_FOUND% NEQ 1 (
    echo.
    echo [!] No authorized phone detected!
    echo.
    echo Please check the following:
    echo   1. Plug your phone into your PC with a USB cable.
    echo   2. Enable Developer Options: Settings ^> About Phone ^> Tap 'Build Number' 7 times.
    echo   3. Enable USB Debugging: Settings ^> Developer Options ^> Turn ON 'USB Debugging'.
    echo   4. Unlock your phone screen and tap 'Always allow from this computer'.
    echo.
    pause
    exit /b 1
)

echo [✓] Connected device detected: %DEVICE_ID%
echo.

:: 3. Build Debug APK
echo [2/4] Building Android APK with 3GB RAM allocated...
call gradlew.bat --stop >nul 2>nul
call gradlew.bat assembleDebug -Dorg.gradle.jvmargs="-Xmx3072m -XX:MaxMetaspaceSize=768m" --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Gradle build failed. Check that JDK 17 is installed.
    pause
    exit /b 1
)

set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at %APK_PATH%
    pause
    exit /b 1
)

echo [✓] APK built successfully!
echo.

:: 4. Install APK onto connected phone
echo [3/4] Installing VORTEX APK onto your phone over USB...
adb -s %DEVICE_ID% install -r -d "%APK_PATH%"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Failed to install APK on phone.
    echo If your phone shows a prompt asking 'Allow install via USB?', tap Allow.
    pause
    exit /b 1
)

echo [✓] App installed successfully!
echo.

:: 5. Launch app directly on phone
echo [4/4] Launching VORTEX Pair on your phone...
adb -s %DEVICE_ID% shell am start -n com.phonepc.pairing/.MainActivity >nul 2>nul

echo.
echo =======================================================
echo  SUCCESS! VORTEX Pair is now running on your phone!
echo =======================================================
echo.
echo Next steps:
echo   1. Point your phone camera at the QR code on your PC.
echo   2. Verify the 6-digit confirmation code.
echo   3. Click 'Approve Pairing' on your PC desktop window.
echo.
pause
