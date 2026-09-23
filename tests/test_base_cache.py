import dataclasses

import pytest

from src.config import paths
from src.config.settings import Settings
from src.graphics.base_cache import base_cache_key

_CANVAS = (1920, 1080)


def test_same_inputs_give_the_same_key():
    assert base_cache_key("abc123", _CANVAS, Settings()) == base_cache_key("abc123", _CANVAS, Settings())


@pytest.mark.parametrize(
    "change",
    [
        {"art_size_pct": 0.5},
        {"corner_radius": 40},
        {"shadow_blur_radius": 60},
        {"background_style": "mesh"},
        {"art_glow": True},
        {"background_style": "blur"},
        {"art_frame": True},
    ],
)
def test_pixel_affecting_settings_change_the_key(change):
    changed = dataclasses.replace(Settings(), **change)

    assert base_cache_key("abc123", _CANVAS, changed) != base_cache_key("abc123", _CANVAS, Settings())


def test_resolution_changes_the_key():
    assert base_cache_key("abc123", (2560, 1440), Settings()) != base_cache_key("abc123", _CANVAS, Settings())


@pytest.mark.parametrize(
    "change",
    [
        # Drawn over the base on every track, never baked into it.
        {"show_track_info": False},
        {"text_card": "glass"},
        {"poll_interval_seconds": 9.0},
        {"client_id": "x" * 32},
        {"sync_lock_screen": True},
    ],
)
def test_settings_outside_the_base_keep_the_key(change):
    changed = dataclasses.replace(Settings(), **change)

    assert base_cache_key("abc123", _CANVAS, changed) == base_cache_key("abc123", _CANVAS, Settings())


def test_key_is_file_safe_even_for_odd_album_ids():
    key = base_cache_key("../evil:id", _CANVAS, Settings())

    assert all(c.isalnum() or c in "-_" for c in key)


def test_album_base_path_lives_in_the_album_bases_dir(monkeypatch, tmp_path):
    monkeypatch.setattr(paths, "cache_dir", lambda: tmp_path)

    path = paths.album_base_path("somekey")

    assert path == tmp_path / "album_bases" / "somekey.png"
    assert path.parent.is_dir()
