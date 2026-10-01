"""The lyrics widget's window on Hyprland, where a client can't place itself.

Measured on Hyprland 0.56 (Lua config), 2026-10-01:
- Without a rule the widget is tiled, takes a tile's size and the focus, and
  ignores move(). A window rule set at runtime (`hyprctl eval`) makes it
  float, pinned to every workspace, without taking the focus, at the size and
  place the rule says. The rule's `move` is relative to its `monitor`.
- `hyprctl eval` keeps Lua globals between calls, so the rule object can be
  kept and switched off later; a reload (a theme switch does one) drops them
  and the rule with them. A window already mapped stays floating, but one
  shown again after it would be tiled: so the rule is made again before every
  show, with the rect of that moment.
- The window's real place is only known to Hyprland (`hyprctl clients`);
  moving it from here is a dispatch, in global coordinates. A resize keeps
  the center, so it comes before the move.
- "Behind the other windows" doesn't exist: a floating window is always above
  the tiled ones. On Linux the widget is always on top.
"""

import json
import logging
import os
import subprocess
from typing import Callable, Optional, Tuple

logger = logging.getLogger(__name__)

# Matched by title: the app's windows all share one class (the interpreter's
# name), and this one has no title bar to show it.
TITLE = "Spotify Wallpaper Engine lyrics"
_RULE_NAME = "spotify-wallpaper-engine-lyrics"
_RULE_GLOBAL = "SPOTIFY_WALLPAPER_ENGINE_LYRICS_RULE"
_TIMEOUT_S = 2.0
_FULLSCREEN = 2  # hyprctl's fullscreen mode: 0 none, 1 maximized, 2 fullscreen

Rect = Tuple[int, int, int, int]


def _lua_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _title_pattern() -> str:
    return "^" + TITLE + "$"  # no regex characters in TITLE


def rule_lua(rect: Rect, monitor: str, origin: Tuple[int, int]) -> str:
    """The Lua that swaps the previous rule (if this Hyprland still has it) for
    one placing the widget at [rect] (global) on [monitor] (whose top-left is
    [origin]): the rule's `move` counts from the monitor."""
    x, y, width, height = rect
    return (
        f"if {_RULE_GLOBAL} then {_RULE_GLOBAL}:set_enabled(false) end "
        f"{_RULE_GLOBAL} = hl.window_rule({{ name = {_lua_string(_RULE_NAME)}, "
        f"match = {{ title = {_lua_string(_title_pattern())} }}, "
        "float = true, pin = true, no_initial_focus = true, no_anim = true, "
        "border_size = 0, no_shadow = true, "
        f"monitor = {_lua_string(monitor)}, "
        f"size = {{ {int(width)}, {int(height)} }}, "
        f"move = {{ {int(x - origin[0])}, {int(y - origin[1])} }} }})"
    )


def remove_rule_lua() -> str:
    return f"if {_RULE_GLOBAL} then {_RULE_GLOBAL}:set_enabled(false) {_RULE_GLOBAL} = nil end"


def move_resize_lua(rect: Rect) -> str:
    x, y, width, height = rect
    window = _lua_string("title:" + _title_pattern())
    return (
        f"hl.dispatch(hl.dsp.window.resize({{ x = {int(width)}, y = {int(height)}, window = {window} }})) "
        f"hl.dispatch(hl.dsp.window.move({{ x = {int(x)}, y = {int(y)}, window = {window} }}))"
    )


def find_client(clients, pid: int) -> Optional[dict]:
    for client in clients if isinstance(clients, list) else []:
        if isinstance(client, dict) and client.get("title") == TITLE and client.get("pid") == pid:
            return client
    return None


def client_rect(client: dict) -> Optional[Rect]:
    try:
        (x, y), (width, height) = client["at"], client["size"]
        return int(x), int(y), int(width), int(height)
    except (KeyError, TypeError, ValueError):
        return None


class HyprlandWidgetWindow:
    """hyprctl calls for the widget's window. Failures are logged (once until
    one works again) and otherwise ignored: the widget still shows, only not
    where it should."""

    def __init__(self, run: Optional[Callable] = None, pid: Optional[int] = None) -> None:
        self._run = run if run is not None else subprocess.run
        self._pid = pid if pid is not None else os.getpid()
        self._failing = False

    def _hyprctl(self, *args: str) -> Optional[str]:
        try:
            result = self._run(["hyprctl", *args], capture_output=True, text=True, timeout=_TIMEOUT_S)
        except (OSError, subprocess.SubprocessError) as exc:
            return self._failed(f"hyprctl {args[0]} could not run: {exc}")
        output = (result.stdout or "").strip()
        # eval answers "ok" or the Lua error, on stdout, with exit 0.
        if result.returncode != 0 or (args[0] == "eval" and output != "ok"):
            return self._failed(f"hyprctl {args[0]} failed ({result.returncode}): {output[:200]}")
        self._failing = False
        return output

    def _failed(self, why: str) -> None:
        if not self._failing:
            logger.warning("Lyrics widget on Hyprland: %s", why)
        self._failing = True
        return None

    def before_show(self, rect: Rect, monitor: str, origin: Tuple[int, int]) -> None:
        self._hyprctl("eval", rule_lua(rect, monitor, origin))

    def remove_rule(self) -> None:
        self._hyprctl("eval", remove_rule_lua())

    def move_resize(self, rect: Rect) -> None:
        self._hyprctl("eval", move_resize_lua(rect))

    def window_rect(self) -> Optional[Tuple[Rect, Optional[int]]]:
        """Where the widget really is, and the id of its monitor; None when it
        isn't mapped (or Hyprland can't be asked)."""
        output = self._hyprctl("clients", "-j")
        if output is None:
            return None
        try:
            client = find_client(json.loads(output), self._pid)
        except ValueError:
            return None
        rect = client_rect(client) if client is not None else None
        if rect is None:
            return None
        return rect, client.get("monitor")

    def monitor_name(self, monitor_id) -> Optional[str]:
        output = self._hyprctl("monitors", "-j")
        try:
            for monitor in json.loads(output) if output else []:
                if monitor.get("id") == monitor_id:
                    return monitor.get("name")
        except (ValueError, AttributeError):
            pass
        return None

    def full_screen_active(self) -> bool:
        """A full-screen window has the focus: a game, a video. Maximized
        doesn't count, as on Windows."""
        output = self._hyprctl("activewindow", "-j")
        try:
            active = json.loads(output) if output else {}
        except ValueError:
            return False
        return isinstance(active, dict) and active.get("fullscreen") == _FULLSCREEN
