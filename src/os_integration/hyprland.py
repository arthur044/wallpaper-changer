import json
import subprocess
from typing import Callable, List, Optional, Tuple

_HYPRCTL_TIMEOUT_S = 2.0
# wl_output transforms 1, 3, 5 and 7 turn the monitor by 90 or 270 degrees.
_ROTATED_TRANSFORMS = {1, 3, 5, 7}


def monitors(run: Optional[Callable] = None) -> List[dict]:
    """`hyprctl monitors -j`: the enabled monitors. Raises when Hyprland can't
    be asked (not running, no hyprctl, garbled answer)."""
    run = run if run is not None else subprocess.run
    result = run(["hyprctl", "monitors", "-j"], capture_output=True, text=True, timeout=_HYPRCTL_TIMEOUT_S)
    if result.returncode != 0:
        raise OSError(f"hyprctl monitors failed ({result.returncode}): {(result.stderr or result.stdout).strip()[:200]}")
    parsed = json.loads(result.stdout)
    if not isinstance(parsed, list):
        raise ValueError(f"hyprctl monitors gave {type(parsed).__name__}, not a list")
    return parsed


def primary_monitor(found: List[dict]) -> Optional[dict]:
    """Hyprland has no primary monitor. The one with the lowest id (the first
    one Hyprland set up) stands in: the canvas size is part of the album
    base's cache key, so it must not follow the focus from monitor to
    monitor. Disabled and mirroring monitors don't count."""
    candidates = [
        monitor
        for monitor in found
        if isinstance(monitor, dict)
        and not monitor.get("disabled", False)
        and monitor.get("mirrorOf", "none") in ("none", "", None)
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda monitor: monitor.get("id", 0))


def physical_size(monitor: dict) -> Tuple[int, int]:
    """The monitor's mode in physical pixels, turned when the monitor is.
    Not divided by the scale: the shell draws the background at the
    monitor's full resolution, so the wallpaper is made at it too."""
    width, height = int(monitor["width"]), int(monitor["height"])
    if monitor.get("transform") in _ROTATED_TRANSFORMS:
        width, height = height, width
    if width <= 0 or height <= 0:
        raise ValueError(f"Invalid resolution reported: {width}x{height}")
    return width, height


def primary_resolution(run: Optional[Callable] = None) -> Tuple[int, int]:
    monitor = primary_monitor(monitors(run))
    if monitor is None:
        raise ValueError("hyprctl lists no enabled monitor")
    return physical_size(monitor)
