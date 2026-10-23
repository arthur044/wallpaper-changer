<#
.SYNOPSIS
  Starts the built exe with a throwaway profile and fails if it dies or logs an import error.

.DESCRIPTION
  A frozen app can build fine and still miss a module that is only imported
  later (the SMTC watcher swallows that error and logs it). With a fake client
  id the app skips the setup wizard and starts everything: Qt, tray, SMTC.
  It never gets a Spotify token, which is fine: only the startup is checked.
#>
param(
    [string]$Exe = "dist\SpotifyWallpaperEngine.exe",
    [int]$Seconds = 25
)
$ErrorActionPreference = "Stop"

$profileDir = Join-Path ([IO.Path]::GetTempPath()) ("wallpaper-smoke-" + [guid]::NewGuid())
$env:APPDATA = Join-Path $profileDir "roaming"
$env:LOCALAPPDATA = Join-Path $profileDir "local"
$configDir = Join-Path $env:APPDATA "SpotifyWallpaperEngine"
New-Item -ItemType Directory -Force $configDir | Out-Null
New-Item -ItemType Directory -Force $env:LOCALAPPDATA | Out-Null
'{"client_id": "' + ("0" * 32) + '"}' | Set-Content (Join-Path $configDir "config.json") -Encoding ascii

$process = Start-Process -FilePath (Resolve-Path $Exe) -PassThru
Start-Sleep -Seconds $Seconds
$alive = -not $process.HasExited
if ($alive) { Stop-Process -Id $process.Id -Force }

$log = Join-Path $env:LOCALAPPDATA "SpotifyWallpaperEngine\logs\app.log"
$text = if (Test-Path $log) { Get-Content $log -Raw } else { "" }
Write-Host "---- app.log ----"
Write-Host $text
Write-Host "-----------------"

if (-not $alive) { throw "The exe exited within $Seconds s (code $($process.ExitCode))." }
if (-not $text) { throw "The exe ran but wrote no log: the app did not get through its startup." }
if ($text -match "ModuleNotFoundError|ImportError|No module named|SMTC watcher failed") {
    throw "The log shows a missing module or a watcher that did not start."
}
Write-Host "Smoke test passed."
