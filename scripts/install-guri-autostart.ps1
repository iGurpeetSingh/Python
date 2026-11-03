$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$startup = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startup "Guri Voice Assistant.lnk"
$backendPython = Join-Path $root "backend\.venv\Scripts\python.exe"
$frontendModules = Join-Path $root "frontend\node_modules"

if (-not (Test-Path $backendPython) -or -not (Test-Path $frontendModules)) {
    Write-Host "Pehle Start-Guri.bat ko ek baar normal tareeke se chala kar setup complete karein." -ForegroundColor Yellow
    Read-Host "Press Enter to close"
    exit 1
}

$scriptPath = Join-Path $root "scripts\start-guri.ps1"
$powershell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
$arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $scriptPath + '" -NoInstall -Quiet'

$wsh = New-Object -ComObject WScript.Shell
$shortcut = $wsh.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $powershell
$shortcut.Arguments = $arguments
$shortcut.WorkingDirectory = $root
$shortcut.Description = "Start Guri Voice Assistant when Windows signs in"
$shortcut.WindowStyle = 7
$shortcut.Save()

Write-Host ""
Write-Host "Guri auto-start is enabled." -ForegroundColor Green
Write-Host "Windows sign-in par Guri backend aur voice page automatically start honge."
Write-Host "Disable karne ke liye Remove-Guri-Autostart.bat chalayein."
Read-Host "Press Enter to close"
