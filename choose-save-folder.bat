@echo off
rem Open a visible Windows folder picker without opening the browser.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\launch.ps1" -ChooseRootOnly
if errorlevel 1 pause
