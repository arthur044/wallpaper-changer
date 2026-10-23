<#
.SYNOPSIS
  Builds the desktop app into one Windows exe (dist\SpotifyWallpaperEngine.exe).

.DESCRIPTION
  Run from the repository root, in an environment with requirements.txt and
  pyinstaller installed. The CI (.github/workflows/windows.yml) runs this same
  script, so a local build is the build that gets published.

.EXAMPLE
  .venv\Scripts\pip install pyinstaller==6.22.3
  powershell -ExecutionPolicy Bypass -File scripts\build-exe.ps1
#>
$ErrorActionPreference = "Stop"

# The exe has no git clone to ask: the tray reads its version from this file
# (src/os_integration/app_version.py, BUNDLED_VERSION_FILE).
$commit = (& git log -1 --format="%h %cs").Trim()
if ($LASTEXITCODE -ne 0 -or -not $commit) { throw "Could not read the commit (is this a git checkout?)." }
Set-Content -Path build_info.txt -Value $commit -NoNewline -Encoding ascii

# The lock screen task runs elevated on every track change: it gets a small exe
# of its own, packed inside the app (src/os_integration/lockscreen.py copies it
# out). Built first so the app build can include it.
& python -m PyInstaller --noconfirm --clean --onefile --windowed --name apply-lockscreen --paths . scripts\apply_lockscreen.py
if ($LASTEXITCODE -ne 0) { throw "PyInstaller (apply-lockscreen) exited with $LASTEXITCODE" }
if (-not (Test-Path "dist\apply-lockscreen.exe")) { throw "dist\apply-lockscreen.exe was not produced." }

# winsdk is only imported inside SmtcWatcher, and its native modules load each
# other from C, so PyInstaller can't see them: they are listed by hand. A miss
# shows up as "SMTC watcher failed to start" in the log (the CI smoke test
# greps for it). keyring finds its Windows backend through entry points.
$arguments = @(
    "--noconfirm", "--clean",
    "--onefile", "--windowed",
    "--name", "SpotifyWallpaperEngine",
    "--add-data", "build_info.txt;.",
    "--add-data", "dist\apply-lockscreen.exe;.",
    "--copy-metadata", "keyring",
    "--collect-submodules", "keyring.backends",
    "--hidden-import", "pystray._win32",
    "--hidden-import", "winsdk.windows.media.control",
    "--hidden-import", "winsdk.windows.media",
    "--hidden-import", "winsdk.windows.foundation",
    "--hidden-import", "winsdk.windows.foundation.collections",
    "--hidden-import", "winsdk.windows.storage",
    "--hidden-import", "winsdk.windows.storage.streams",
    "main.py"
)
& python -m PyInstaller @arguments
if ($LASTEXITCODE -ne 0) { throw "PyInstaller exited with $LASTEXITCODE" }

$exe = "dist\SpotifyWallpaperEngine.exe"
if (-not (Test-Path $exe)) { throw "$exe was not produced." }
Remove-Item "dist\apply-lockscreen.exe"  # inside the app now; dist holds only what ships
Write-Host ("Built {0} ({1:N1} MB), version {2}" -f $exe, ((Get-Item $exe).Length / 1MB), $commit)
