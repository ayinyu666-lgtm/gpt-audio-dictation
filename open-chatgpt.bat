@echo off
rem Open ChatGPT. If the extension is not loaded yet, this says so and opens the
rem install page instead of silently doing nothing.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\launch.ps1" %*
