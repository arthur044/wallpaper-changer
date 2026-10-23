<#
.SYNOPSIS
  Runs dist\apply-lockscreen.exe with a throwaway profile and fails if it crashes.

.DESCRIPTION
  Without a pending file the helper has nothing to apply and exits with 0, so
  this only proves it starts and imports everything it needs. Called by
  scripts/build-exe.ps1 before the helper is packed into the app.
#>
param([string]$Exe = "dist\apply-lockscreen.exe")
$ErrorActionPreference = "Stop"

# Runs in build-exe.ps1's session: the throwaway profile must not outlive this script.
$oldAppData, $oldLocalAppData = $env:APPDATA, $env:LOCALAPPDATA
$profileDir = Join-Path ([IO.Path]::GetTempPath()) ("wallpaper-helper-smoke-" + [guid]::NewGuid())
try {
    $env:APPDATA = Join-Path $profileDir "roaming"
    $env:LOCALAPPDATA = Join-Path $profileDir "local"
    New-Item -ItemType Directory -Force $env:APPDATA, $env:LOCALAPPDATA | Out-Null

    $process = Start-Process -FilePath (Resolve-Path $Exe) -PassThru -Wait
    $log = Join-Path $env:LOCALAPPDATA "SpotifyWallpaperEngine\logs\app.log"
    $text = if (Test-Path $log) { Get-Content $log -Raw } else { "" }

    if ($process.ExitCode -ne 0) { throw "apply-lockscreen.exe exited with $($process.ExitCode). Log: $text" }
    if ($text -match "Traceback|ModuleNotFoundError|ImportError") { throw "apply-lockscreen.exe logged an error: $text" }
    Write-Host "Helper smoke test passed."
} finally {
    $env:APPDATA, $env:LOCALAPPDATA = $oldAppData, $oldLocalAppData
}
