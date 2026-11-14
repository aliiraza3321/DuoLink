@echo off
setlocal
title VORTEX - Desktop
echo =======================================================
echo          Starting VORTEX Desktop Application
echo =======================================================
echo.

cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 goto nonode

if exist "node_modules\electron\package.json" goto launch
echo [1/2] Installing dependencies - first run only, please wait...
call npm install
if errorlevel 1 goto npmfail

:launch
echo [2/2] Launching VORTEX Electron Desktop App...
echo Server will run on port 3000 over your local Wi-Fi.
echo.
call npx electron .
echo.
echo App closed. Exit code: %errorlevel%
echo If it closed right away, scroll up for the error message.
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
