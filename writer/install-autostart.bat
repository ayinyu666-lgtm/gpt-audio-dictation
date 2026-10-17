@echo off
rem Put the writer into the Windows Startup folder so it runs quietly from login.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$startup=[Environment]::GetFolderPath('Startup'); $link=Join-Path $startup 'GPT-SaveWriter.lnk'; $ws=New-Object -ComObject WScript.Shell; $s=$ws.CreateShortcut($link); $s.TargetPath='%~dp0start-writer.bat'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Description='GPT auto-dictation local save service'; $s.Save(); Write-Host ('  Startup entry created: ' + $link); Write-Host '  It will start automatically next time you log in.'"
echo.
pause
