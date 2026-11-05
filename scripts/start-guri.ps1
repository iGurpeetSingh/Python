param([switch]$NoInstall, [switch]$Quiet)

$ErrorActionPreference = "Stop"

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(350)) { return $false }
        $client.EndConnect($result)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortOwner([int]$Port) {
    try {
        $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($connection) {
            $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
            if ($process) { return "$($process.ProcessName) (PID $($process.Id))" }
        }
    } catch {}
    return "unknown process"
}

function Test-GuriApi {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8000/api/health" -TimeoutSec 3
        return ($health.ok -eq $true -and $health.agent -eq "Guri")
    } catch { return $false }
}

function Test-GuriWeb {
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:5173/" -TimeoutSec 3 -UseBasicParsing
        return ($response.StatusCode -eq 200 -and $response.Content -match "Guri")
    } catch { return $false }
}

function Write-StartupLog([string]$Message) {
    Add-Content -Path (Join-Path $env:TEMP "guri-startup.log") -Value "$(Get-Date -Format o) $Message"
}

function Ensure-Adb {
    if (Get-Command adb -ErrorAction SilentlyContinue) { return $true }

    $toolsRoot = Join-Path (Split-Path -Parent $PSScriptRoot) ".tools"
    $platformTools = Join-Path $toolsRoot "platform-tools"
    $adbExe = Join-Path $platformTools "adb.exe"
    if (-not (Test-Path $adbExe)) {
        New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
        $zip = Join-Path $env:TEMP "guri-platform-tools.zip"
        $extract = Join-Path $env:TEMP "guri-platform-tools-extract"
        try {
            Write-Host "Installing Android Platform Tools for automatic phone control..." -ForegroundColor Cyan
            Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing
            if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
            Expand-Archive -Path $zip -DestinationPath $extract -Force
            Copy-Item (Join-Path $extract "platform-tools") $toolsRoot -Recurse -Force
        } catch {
            Write-StartupLog ("ADB auto-install warning: " + $_.Exception.Message)
            return $false
        } finally {
            Remove-Item $zip -Force -ErrorAction SilentlyContinue
            Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    if (Test-Path $adbExe) {
        $env:PATH = "$platformTools;$env:PATH"
        return $true
    }
    return $false
}

$backendError = Join-Path $env:TEMP "guri-backend-error.log"
$frontendError = Join-Path $env:TEMP "guri-frontend-error.log"

try {
    $root = Split-Path -Parent $PSScriptRoot
    $backend = Join-Path $root "backend"
    $frontend = Join-Path $root "frontend"
    $venvPython = Join-Path $backend ".venv\Scripts\python.exe"

    if (-not (Test-Path (Join-Path $backend "app\main.py"))) {
        throw "Guri source files are missing. Extract the complete repository ZIP before running this launcher."
    }
    if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw "Node.js LTS is missing or not in PATH. Install Node.js LTS once, then run Start-Guri.bat again."
    }

    if (Ensure-Adb) {
        Write-StartupLog "Android Platform Tools available."
    } else {
        Write-StartupLog "Android Platform Tools unavailable; Windows-only commands remain available."
    }
    if (-not (Test-Path $venvPython)) {
        if ($NoInstall) { throw "Python environment not installed yet. Run Start-Guri.bat once before enabling auto-start." }
        if (Get-Command py -ErrorAction SilentlyContinue) {
            & py -3 -m venv (Join-Path $backend ".venv")
        } elseif (Get-Command python -ErrorAction SilentlyContinue) {
            & python -m venv (Join-Path $backend ".venv")
        } else {
            throw "Python 3.11+ is missing. Install Python once, then run Start-Guri.bat again."
        }
        if ($LASTEXITCODE -ne 0) { throw "Could not create the Python virtual environment." }
    }

    if (-not $NoInstall) {
        if (-not $Quiet) { Write-Host "Installing Python dependencies..." -ForegroundColor Cyan }
        & $venvPython -m pip install -r (Join-Path $backend "requirements.txt")
        if ($LASTEXITCODE -ne 0) { throw "Python dependency installation failed. Check your internet connection." }

        if (-not $Quiet) { Write-Host "Installing Playwright Chromium..." -ForegroundColor Cyan }
        & $venvPython -m playwright install chromium
        if ($LASTEXITCODE -ne 0) { throw "Playwright Chromium installation failed." }

        if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
            if (-not $Quiet) { Write-Host "Installing React dependencies..." -ForegroundColor Cyan }
            & npm install --prefix $frontend
            if ($LASTEXITCODE -ne 0) { throw "React package installation failed." }
        }
    } else {
        if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
            throw "React packages are missing. Run Start-Guri.bat once before enabling auto-start."
        }
    }

    if (Test-LocalPort 8000) {
        if (-not (Test-GuriApi)) {
            throw ("Port 8000 belongs to " + (Get-PortOwner 8000) + ", not Guri. Close that program before starting Guri.")
        }
    } else {
        $backendLog = Join-Path $env:TEMP "guri-backend.log"
        Start-Process -FilePath $venvPython -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000") -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $backendLog -RedirectStandardError $backendError | Out-Null
    }

    $apiReady = $false
    for ($i = 0; $i -lt 45; $i++) {
        if (Test-GuriApi) { $apiReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $apiReady) {
        $details = ""
        if (Test-Path $backendError) { $details = (Get-Content $backendError -Tail 18 | Out-String) }
        throw ("Python API did not start. See " + $backendError + [Environment]::NewLine + $details)
    }

    if (Test-LocalPort 5173) {
        if (-not (Test-GuriWeb)) {
            throw ("Port 5173 belongs to " + (Get-PortOwner 5173) + ", not the Guri webpage.")
        }
    } else {
        $frontendLog = Join-Path $env:TEMP "guri-frontend.log"
        Start-Process -FilePath $env:ComSpec -ArgumentList @("/c", "npm run dev -- --host 127.0.0.1") -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendError | Out-Null
    }

    $webReady = $false
    for ($i = 0; $i -lt 30; $i++) {
        if (Test-GuriWeb) { $webReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $webReady) {
        $details = ""
        if (Test-Path $frontendError) { $details = (Get-Content $frontendError -Tail 18 | Out-String) }
        throw ("Guri webpage did not start. See " + $frontendError + [Environment]::NewLine + $details)
    }

    Start-Process "http://127.0.0.1:5173"
    Write-StartupLog "Guri started successfully."
    if (-not $Quiet) {
        Write-Host ""
        Write-Host "Guri is ready. Backend health check passed." -ForegroundColor Green
        Write-Host "Voice page: http://127.0.0.1:5173"
        Write-Host "Health check: http://127.0.0.1:8000/api/health"
    }
} catch {
    $message = $_.Exception.Message
    Write-StartupLog ("START ERROR: " + $message)
    if ($Quiet) { exit 1 }
    Write-Host ""
    Write-Host ("GURI START ERROR: " + $message) -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}
