import os
import sys
from pathlib import Path

APP_NAME = "SpotifyWallpaperEngine"


def _windows_root(env_var: str) -> Path:
    return Path(os.environ.get(env_var) or str(Path.home()))


def _xdg_root(env_var: str, default: str) -> Path:
    """The XDG base directory; the spec says a relative value is invalid and
    must be ignored, the same as an unset one."""
    root = os.environ.get(env_var)
    if root and os.path.isabs(root):
        return Path(root)
    return Path.home() / default


def _app_dir(root: Path) -> Path:
    path = root / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_dir() -> Path:
    """config.json. %APPDATA% on Windows, $XDG_CONFIG_HOME (~/.config) elsewhere."""
    if sys.platform == "win32":
        return _app_dir(_windows_root("APPDATA"))
    return _app_dir(_xdg_root("XDG_CONFIG_HOME", ".config"))


def data_dir() -> Path:
    """Track index, logs and other state. %LOCALAPPDATA% on Windows,
    $XDG_DATA_HOME (~/.local/share) elsewhere."""
    if sys.platform == "win32":
        return _app_dir(_windows_root("LOCALAPPDATA"))
    return _app_dir(_xdg_root("XDG_DATA_HOME", ".local/share"))


def cache_dir() -> Path:
    """Album bases, original art and the wallpaper itself: all of it can be
    drawn again. Inside data_dir() on Windows, as it always was;
    $XDG_CACHE_HOME (~/.cache) elsewhere."""
    if sys.platform == "win32":
        path = data_dir() / "cache"
        path.mkdir(parents=True, exist_ok=True)
        return path
    return _app_dir(_xdg_root("XDG_CACHE_HOME", ".cache"))


def logs_dir() -> Path:
    path = data_dir() / "logs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_file() -> Path:
    return config_dir() / "config.json"


def track_index_file() -> Path:
    """The "artist::title" -> album map, kept across restarts (spotify.track_index)."""
    return data_dir() / "track_index.json"


def album_base_path(key: str) -> Path:
    """Permanent per-album base composite (background + art + shadow, no track text),
    named by graphics.base_cache.base_cache_key. Written once per key and never
    overwritten, so re-visiting an album skips the download and the expensive
    composition step entirely; changing a setting that affects it changes the key."""
    albums_dir = cache_dir() / "album_bases"
    albums_dir.mkdir(parents=True, exist_ok=True)
    return albums_dir / f"{key}.png"


def album_art_dir() -> Path:
    """The original cover and colors of each album (graphics.art_cache): what a
    style change redraws from, without downloading the art again."""
    return cache_dir() / "album_art"
