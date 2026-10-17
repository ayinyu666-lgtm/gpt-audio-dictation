@echo off
rem Local save service: receives text from the extension and writes it to disk.
chcp 65001 >nul
title GPT Save Writer
cd /d "%~dp0"
set "NODE_EXE="
where node >nul 2>nul
if not errorlevel 1 set "NODE_EXE=node"
if not defined NODE_EXE if exist "E:\AI\node-v24.12.0-win-x64\node.exe" set "NODE_EXE=E:\AI\node-v24.12.0-win-x64\node.exe"
if not defined NODE_EXE (
  echo [ERROR] Node.js not found. Install Node.js 22+ first.
  pause
  exit /b 1
)
"%NODE_EXE%" "%~dp0writer.mjs"
set "NODE_EXIT=%ERRORLEVEL%"
if not "%NODE_EXIT%"=="0" echo [ERROR] Writer exited with code %NODE_EXIT%.
echo.
echo Writer process exited. Read the message above; port 8799 may already be served by another writer instance.
pause
