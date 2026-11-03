$ErrorActionPreference = "Stop"

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $connected = $result.AsyncWaitHandle.WaitOne(250)
        if (-not $connected) { return $false }
        $client.EndConnect($result)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

try {
    $root = Split-Path -Parent $PSScriptRoot
    $backend = Join-Path $root "backend"
    $frontend = Join-Path $root "frontend"
    $venvPython = Join-Path $backend ".venv\Scripts\python.exe"

    Write-Host ""
    Write-Host "=================================" -ForegroundColor Cyan
    Write-Host "       GURI VOICE ASSISTANT      " -ForegroundColor Cyan
    Write-Host "=================================" -ForegroundColor Cyan

    if (-not (Get-Command py -ErrorAction SilentlyContinue) -and
        -not (Get-Command python -ErrorAction SilentlyContinue)) {
        throw "Python 3 is not installed. Install Python 3.11 or newer, then double-click Start-Guri.bat again."
    }

    if (-not (Get-Command node -ErrorAction SilentlyContinue) -or
        -not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw "Node.js LTS is not installed. Install Node.js LTS, then double-click Start-Guri.bat again."
    }

    if (-not (Test-Path $venvPython)) {
        Write-Host "[1/5] Creating Python environment..."
        if (Get-Command py -ErrorAction SilentlyContinue) {
            & py -3 -m venv (Join-Path $backend ".venv")
        } else {
            & python -m venv (Join-Path $backend ".venv")
        }
        if ($LASTEXITCODE -ne 0) { throw "Could not create the Python virtual environment." }
    }

    Write-Host "[2/5] Installing/checking Python packages..."
    & $venvPython -m pip install -r (Join-Path $backend "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "Python dependency installation failed. Check your internet connection and backend requirements." }

    Write-Host "[3/5] Checking the browser automation engine..."
    & $venvPython -m playwright install chromium
    if ($LASTEXITCODE -ne 0) { throw "Playwright Chromium installation failed." }

    if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
        Write-Host "[4/5] Installing React packages..."
        & npm install --prefix $frontend
        if ($LASTEXITCODE -ne 0) { throw "React package installation failed." }
    } else {
        Write-Host "[4/5] React packages already installed."
    }

    $backendLog = Join-Path $env:TEMP "guri-backend.log"
    $backendError = Join-Path $env:TEMP "guri-backend-error.log"
    if (-not (Test-LocalPort 8000)) {
        Write-Host "[5/5] Starting Python backend..."
        Start-Process -FilePath $venvPython -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000") -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $backendLog -RedirectStandardError $backendError | Out-Null
    } else {
        Write-Host "[5/5] Python backend port is already active."
    }

    $frontendLog = Join-Path $env:TEMP "guri-frontend.log"
    $frontendError = Join-Path $env:TEMP "guri-frontend-error.log"
    if (-not (Test-LocalPort 5173)) {
        Start-Process -FilePath $env:ComSpec -ArgumentList @("/c", "npm run dev -- --host 127.0.0.1") -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendError | Out-Null
    }

    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:8000/api/health" -TimeoutSec 2
            if ($health.ok) {
                $ready = $true
                break
            }
        } catch {}
    }

    if (-not $ready) {
        $details = ""
        if (Test-Path $backendError) { $details = (Get-Content $backendError -Tail 12 | Out-String) }
        throw ("Python backend did not become ready. Backend log: " + $backendError + [Environment]::NewLine + $details)
    }

    $frontendReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Test-LocalPort 5173) { $frontendReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $frontendReady) {
        throw "Guri voice page did not start. Check frontend log: $frontendError"
    }

    Start-Process "http://127.0.0.1:5173"
    Write-Host ""
    Write-Host "Guri is ready. Press the microphone once and then speak." -ForegroundColor Green
    Write-Host ("Backend log: " + $backendLog)
    Write-Host ("Frontend log: " + $frontendLog)
} catch {
    Write-Host ""
    Write-Host ("GURI START ERROR: " + $_.Exception.Message) -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}
