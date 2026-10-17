@echo off
rem One-time install: load the unpacked extension into Edge.
chcp 65001 >nul
title GPT Auto Dictation - install
echo.
echo   GPT Auto Dictation - one-time install
echo   =====================================
echo.
echo   A window will pop up with the steps; the extension folder path is
echo   copied to your clipboard, and the Edge extensions page will open.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\launch.ps1" -Install
echo.
echo   After loading the extension, open ChatGPT and the floating panel appears.
echo.
pause
