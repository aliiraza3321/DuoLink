@echo off
setlocal
title VORTEX Build Windows Installer
echo =======================================================
echo          Building VORTEX Windows Installer
echo =======================================================
echo.

cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 goto nonode

if exist "node_modules\electron-builder\package.json" goto build
echo [1/2] Installing dependencies - first run only, please wait...
call npm install
if errorlevel 1 goto npmfail

:build
echo [2/2] Packaging with electron-builder...
call npx electron-builder --win nsis
if errorlevel 1 goto buildfail
echo.
echo SUCCESS - installer created in: %~dp0dist\
dir /b dist\*.exe
goto end

:buildfail
echo [ERROR] Build failed. Read the error above.
goto end

:nonode
echo [ERROR] Node.js is not installed or not in PATH.
echo Install Node.js LTS from https://nodejs.org/ then run this again.
goto end

:npmfail
echo [ERROR] npm install failed. Check your internet connection and read the error above.
goto end

:end
echo.
pause
