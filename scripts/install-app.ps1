<#
.SYNOPSIS
  Sets up the desktop app in a folder of its own, always on main, apart from
  the development checkout.

.DESCRIPTION
  Clones the repository (or reuses an existing clone), creates its own .venv,
  installs the requirements and points the Windows autostart (HKCU Run) at the
  clone. Safe to run again: every step skips what is already done.

  The lock screen task needs UAC, so it is left to you: the script shows where
  it points and how to move it.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\install-app.ps1
#>
param(
    [string]$AppDir = (Join-Path $env:USERPROFILE "wallpaper-app"),
    [string]$RepoUrl = "",
    # For trying the script out without touching the autostart entry.
    [switch]$NoAutostart
)

$ErrorActionPreference = "Stop"
$TaskName = "SpotifyWallpaperEngine_LockScreen"

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }
function Invoke-Native {
    # Stops on a non-zero exit code, which $ErrorActionPreference ignores for .exe files.
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$Exe $($Arguments -join ' ') exited with $LASTEXITCODE" }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail "git is not on PATH." }

if (-not $RepoUrl) {
    # The origin of the checkout this script lives in.
    $RepoUrl = (& git -C (Split-Path $PSScriptRoot -Parent) remote get-url origin).Trim()
    if (-not $RepoUrl) { Fail "Could not find the repository URL; pass -RepoUrl." }
}

Step "Clone ($AppDir)"
if (Test-Path (Join-Path $AppDir ".git")) {
    $branch = (& git -C $AppDir rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -ne "main") { Fail "$AppDir is on '$branch', not main. The app only runs from main." }
    Write-Host "Already cloned, on main."
} elseif (Test-Path $AppDir) {
    Fail "$AppDir exists but is not a git clone. Move it away and run again."
} else {
    Invoke-Native git @("clone", "--branch", "main", $RepoUrl, $AppDir)
}

Step "Virtual environment"
$python = Join-Path $AppDir ".venv\Scripts\python.exe"
if (Test-Path $python) {
    Write-Host "Already there."
} else {
    # 3.11 is what the app is developed on; any Python 3 found after that.
    $candidates = @(@("py", "-3.11"), @("py", "-3"), @("python"))
    $base = $null
    foreach ($candidate in $candidates) {
        if (-not (Get-Command $candidate[0] -ErrorAction SilentlyContinue)) { continue }
        & $candidate[0] @($candidate[1..($candidate.Length)] | Where-Object { $_ }) -c "import sys; assert sys.version_info >= (3, 11)" 2>$null
        if ($LASTEXITCODE -eq 0) { $base = $candidate; break }
    }
    if (-not $base) { Fail "No Python 3.11+ found (tried py -3.11, py -3, python)." }
    Write-Host "Using: $($base -join ' ')"
    $venvArgs = @($base[1..($base.Length)] | Where-Object { $_ }) + @("-m", "venv", (Join-Path $AppDir ".venv"))
    Invoke-Native $base[0] $venvArgs
}

Step "Requirements"
Invoke-Native $python @("-m", "pip", "install", "--disable-pip-version-check", "-q", "-r", (Join-Path $AppDir "requirements.txt"))

Step "Autostart"
if ($NoAutostart) {
    Write-Host "Skipped (-NoAutostart)."
} else {
    Push-Location $AppDir
    try { Invoke-Native $python @("main.py", "--install-autostart") } finally { Pop-Location }
}
$run = (Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -ErrorAction SilentlyContinue).SpotifyWallpaperEngine
Write-Host "Autostart runs: $run"

Step "Lock screen task"
$taskOk = $true
$xml = & schtasks /query /tn $TaskName /xml 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Not installed (Sync Lock Screen is off). Nothing to move."
} else {
    $exec = ([xml]($xml | Out-String)).Task.Actions.Exec
    Write-Host "Task runs: $($exec.Command) $($exec.Arguments)"
    $taskOk = "$($exec.Command) $($exec.Arguments)" -like "*$AppDir*"
}

Step "What is left for you"
$n = 1
Write-Host "$n. Close the running app: tray icon > Exit."; $n++
Write-Host "$n. Start the new one: `"$AppDir\.venv\Scripts\pythonw.exe`" `"$AppDir\main.py`""; $n++
if (-not $taskOk) {
    Write-Host "$n. The lock screen task still runs the old folder. In the NEW app's tray, turn" -ForegroundColor Yellow
    Write-Host "   'Sync Lock Screen' off and on again (one UAC prompt). Then check with:" -ForegroundColor Yellow
    Write-Host "   schtasks /query /tn $TaskName /xml" -ForegroundColor Yellow
    $n++
}
Write-Host "$n. From now on, update from the tray: 'Check for updates'."
