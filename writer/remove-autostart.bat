@echo off
rem Remove the startup entry.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$link=Join-Path ([Environment]::GetFolderPath('Startup')) 'GPT-SaveWriter.lnk'; if (Test-Path $link) { Remove-Item $link -Force; Write-Host '  Removed.' } else { Write-Host '  Not present.' }"
timeout /t 2 >nul
