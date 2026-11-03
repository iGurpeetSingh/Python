$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pythonCommand = Get-Command py -ErrorAction SilentlyContinue
$python = Get-Command python -ErrorAction SilentlyContinue
$venvPython = Join-Path $root "agents\.venv\Scripts\python.exe"

if (-not (Test-Path $venvPython)) {
    if ($pythonCommand) {
        & py -3 -m venv (Join-Path $root "agents\.venv")
    } elseif ($python) {
        & python -m venv (Join-Path $root "agents\.venv")
    } else {
        throw "Python 3.11+ is required for the Windows device agent."
    }
    if ($LASTEXITCODE -ne 0) { throw "Could not create the Guri desktop-agent environment." }
}

Write-Host "Installing Guri desktop-agent packages..."
& $venvPython -m pip install -r (Join-Path $root "backend\requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "Python package installation failed." }
& $venvPython -m playwright install chromium
if ($LASTEXITCODE -ne 0) { throw "Playwright browser installation failed." }

$cloudUrl = Read-Host "Guri Cloud URL (example: https://guri-cloud.onrender.com)"
$secureCode = Read-Host "Render GURI_PAIRING_CODE" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureCode)
try {
    $pairingCode = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}

if ([string]::IsNullOrWhiteSpace($cloudUrl) -or [string]::IsNullOrWhiteSpace($pairingCode)) {
    throw "Cloud URL and pairing code are required."
}

$env:GURI_CLOUD_URL = $cloudUrl.TrimEnd("/")
$env:GURI_DEVICE_TOKEN = $pairingCode
Write-Host ""
Write-Host "Connecting this PC to Guri Cloud. Keep this window open." -ForegroundColor Green
& $venvPython (Join-Path $root "agents\windows_agent.py")
