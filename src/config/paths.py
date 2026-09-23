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


def album_base_path(key: str) -> Path:
    """Permanent per-album base composite (background + art + shadow, no track text),
    named by graphics.base_cache.base_cache_key. Written once per key and never
    overwritten, so re-visiting an album skips the download and the expensive
    composition step entirely; changing a setting that affects it changes the key."""
    albums_dir = cache_dir() / "album_bases"
    albums_dir.mkdir(parents=True, exist_ok=True)
    return albums_dir / f"{key}.png"
