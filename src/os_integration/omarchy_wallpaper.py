import logging
import os
import subprocess
import time
from pathlib import Path
from typing import Callable, Optional

from src.config.paths import state_dir

logger = logging.getLogger(__name__)

_PREFIX = "wallpaper_"
_IPC_TIMEOUT_S = 5.0


def background_link() -> Path:
    """The symlink Omarchy's shell and lock screen read the background from.
    Omarchy builds it from $HOME, not $XDG_STATE_HOME (omarchy-theme-bg-set,
    plugins/background/Background.qml), so this does too."""
    return Path.home() / ".local" / "state" / "omarchy" / "current" / "background"


def _wallpaper_dir() -> Path:
    path = state_dir() / "wallpaper"
    path.mkdir(parents=True, exist_ok=True)
    return path


def next_output_path() -> Path:
    """A new file for every wallpaper, not Windows' a/b pair: the shell shows
    the background through a QML Image with cache: true, which keys on the
    path, so a/b/a showed the a from two tracks ago (measured 2026-10-01).
    In state_dir(), not the cache: the link must still point to a file after
    a reboot, even if something cleared ~/.cache."""
    return _wallpaper_dir() / f"{_PREFIX}{time.time_ns()}.png"


def set_wallpaper(path: Path, smooth: bool = False, run: Callable = subprocess.run) -> None:
    """Points Omarchy's background link at [path] and tells the running shell.
    The link is what the lock screen and a restarted shell read; the shell
    itself only rereads it when told, through its IPC. [smooth] is the
    shell's own reveal animation."""
    link = background_link()
    if not link.parent.is_dir():
        raise OSError(f"Omarchy's background link folder is missing ({link.parent}); is this Omarchy?")
    absolute = path.resolve()
    previous = _link_target(link)
    _replace_link(link, absolute)
    _tell_the_shell(absolute, smooth, run)
    logger.info("Wallpaper set to %s", absolute)
    _remove_old_wallpapers(keep={absolute, previous})


def _link_target(link: Path) -> Optional[Path]:
    try:
        return (link.parent / os.readlink(link)).resolve()
    except OSError:
        return None


def _replace_link(link: Path, target: Path) -> None:
    # A new link renamed over the old one: never a moment with no background.
    temporary = link.with_name(f".{link.name}.{os.getpid()}.tmp")
    try:
        temporary.unlink(missing_ok=True)
        temporary.symlink_to(target)
        os.replace(temporary, link)
    finally:
        temporary.unlink(missing_ok=True)


def _tell_the_shell(absolute: Path, smooth: bool, run: Callable) -> None:
    method = "set" if smooth else "setInstant"
    try:
        result = run(
            ["omarchy-shell", "background", method, str(absolute)],
            capture_output=True,
            text=True,
            timeout=_IPC_TIMEOUT_S,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.warning("Could not reach the Omarchy shell (%s); it shows the new background when it next starts", exc)
        return
    if result.returncode != 0:
        # The link is already set: the lock screen and the next shell start use it.
        logger.warning(
            "The Omarchy shell did not take the new background (%s): %s",
            result.returncode,
            (result.stderr or result.stdout or "").strip()[:200],
        )


def _remove_old_wallpapers(keep: set) -> None:
    """The files older than the new one and the one it replaces (which the
    shell may still be fading out from). Names carry the time they were made,
    so a newer file, written but not set yet, is never touched."""
    folder = _wallpaper_dir()
    kept = sorted(path.name for path in keep if path is not None and path.parent == folder.resolve())
    if not kept:
        return
    for file in folder.glob(f"{_PREFIX}*.png"):
        if file.name >= kept[0]:
            continue
        try:
            file.unlink()
        except OSError as exc:
            logger.debug("Could not remove old wallpaper %s: %s", file, exc)
