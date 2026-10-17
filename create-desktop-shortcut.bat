@echo off
rem Create both desktop shortcuts (ASCII names on purpose):
rem   GPT-AutoDictation - launcher: checks the extension, starts the save service,
rem                       opens ChatGPT
rem   GPT-SaveWriter    - the local save service on its own, for when you want to
rem                       see its log or stop it
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$d=[Environment]::GetFolderPath('Desktop'); $w=New-Object -ComObject WScript.Shell;" ^
  "$a=$w.CreateShortcut((Join-Path $d 'GPT-AutoDictation.lnk')); $a.TargetPath=(Get-Command powershell.exe).Source; $a.Arguments=('-NoProfile -ExecutionPolicy Bypass -File \"' + '%~dp0tools\launch.ps1' + '\"'); $a.WorkingDirectory='%~dp0'; $a.Description='GPT audio dictation - open ChatGPT and choose an archive folder'; $a.IconLocation='%SystemRoot%\System32\shell32.dll,138'; $a.WindowStyle=1; $a.Save();" ^
  "$b=$w.CreateShortcut((Join-Path $d 'GPT-SaveWriter.lnk')); $b.TargetPath='%~dp0writer\start-writer.bat'; $b.WorkingDirectory='%~dp0writer'; $b.Description='GPT audio dictation - local transcript archive service'; $b.IconLocation='%SystemRoot%\System32\shell32.dll,70'; $b.WindowStyle=7; $b.Save();" ^
  "Write-Host ('  Created: ' + (Join-Path $d 'GPT-AutoDictation.lnk')); Write-Host ('  Created: ' + (Join-Path $d 'GPT-SaveWriter.lnk'))"
echo.
pause
