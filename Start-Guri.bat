@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-guri.ps1"
if errorlevel 1 (
  echo.
  echo Guri could not start. Read the error above.
  pause
)
endlocal
