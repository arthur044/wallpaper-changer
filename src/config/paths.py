import os
from pathlib import Path

APP_NAME = "SpotifyWallpaperEngine"


def _base_dir(env_var: str) -> Path:
    root = os.environ.get(env_var) or str(Path.home())
    path = Path(root) / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_dir() -> Path:
    return _base_dir("APPDATA")


def data_dir() -> Path:
    return _base_dir("LOCALAPPDATA")


def cache_dir() -> Path:
    path = data_dir() / "cache"
    path.mkdir(parents=True, exist_ok=True)
    return path


def logs_dir() -> Path:
    path = data_dir() / "logs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_file() -> Path:
    return config_dir() / "config.json"


def album_base_path(album_id: str) -> Path:
    """Permanent per-album base composite (background + art + shadow, no track text).
    Written once per album and never overwritten, so re-visiting an album skips the
    download and the expensive composition step entirely."""
    albums_dir = cache_dir() / "album_bases"
    albums_dir.mkdir(parents=True, exist_ok=True)
    safe_id = "".join(c for c in album_id if c.isalnum() or c in "-_") or "unknown"
    return albums_dir / f"{safe_id}.png"
