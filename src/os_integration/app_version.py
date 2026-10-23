import logging
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from src.os_integration import frozen

logger = logging.getLogger(__name__)

_GIT_TIMEOUT_S = 5.0
# Written by the exe build (see .github/workflows/windows.yml) and packed next to the code.
BUNDLED_VERSION_FILE = "build_info.txt"


@dataclass(frozen=True)
class AppVersion:
    sha: str  # short commit
    date: str  # commit date, YYYY-MM-DD


def app_dir() -> Path:
    """The folder main.py runs from, which is the git clone."""
    return Path(__file__).resolve().parents[2]


def read_version(repo: Path, run: Callable = subprocess.run) -> Optional[AppVersion]:
    """The commit the app runs from, or None without git or outside a repository.
    Read once at startup: the code in memory doesn't change until a restart."""
    try:
        result = run(
            ["git", "-C", str(repo), "log", "-1", "--format=%h %cs"],
            capture_output=True,
            text=True,
            timeout=_GIT_TIMEOUT_S,
            # Same reason as schtasks in lockscreen.py: pythonw has no console,
            # so a console program would flash a terminal of its own.
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.info("Could not read the app version: %s", exc)
        return None
    if result.returncode != 0:
        logger.info("Could not read the app version: git exited with %s", result.returncode)
        return None
    parts = result.stdout.split()
    if len(parts) != 2:
        logger.info("Unexpected git output for the app version: %r", result.stdout)
        return None
    return AppVersion(sha=parts[0], date=parts[1])


def read_bundled_version(path: Optional[Path]) -> Optional[AppVersion]:
    """The exe has no git clone: the build wrote "<sha> <date>" into a file packed with it."""
    try:
        parts = path.read_text(encoding="utf-8").split() if path else []
    except OSError as exc:
        logger.info("Could not read the bundled app version: %s", exc)
        return None
    if len(parts) != 2:
        return None
    return AppVersion(sha=parts[0], date=parts[1])


def current_version() -> Optional[AppVersion]:
    if frozen.is_frozen():
        return read_bundled_version(frozen.bundled_file(BUNDLED_VERSION_FILE))
    return read_version(app_dir())


def version_label(version: Optional[AppVersion]) -> str:
    if version is None:
        return "Unknown version"
    return f"{version.sha} ({version.date})"
