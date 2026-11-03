$startup = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startup "Guri Voice Assistant.lnk"
if (Test-Path $shortcutPath) {
    Remove-Item $shortcutPath -Force
    Write-Host "Guri auto-start disabled." -ForegroundColor Green
} else {
    Write-Host "Guri auto-start shortcut was not installed."
}
Read-Host "Press Enter to close"
