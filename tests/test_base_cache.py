import dataclasses
import os

import pytest

from src.config import paths
from src.config.settings import Settings
from src.graphics.base_cache import base_cache_key, prune_album_bases

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
        {"art_frame": "single"},
        {"art_frame": "double"},
    ],
)
def test_pixel_affecting_settings_change_the_key(change):
    changed = dataclasses.replace(Settings(), **change)

    assert base_cache_key("abc123", _CANVAS, changed) != base_cache_key("abc123", _CANVAS, Settings())


def test_single_and_double_frames_are_different_bases():
    single = dataclasses.replace(Settings(), art_frame="single")
    double = dataclasses.replace(Settings(), art_frame="double")

    assert base_cache_key("abc123", _CANVAS, single) != base_cache_key("abc123", _CANVAS, double)


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


def _base_file(directory, name, size, mtime):
    path = directory / name
    path.write_bytes(b"x" * size)
    os.utime(path, (mtime, mtime))
    return path


def test_prune_removes_bases_in_the_old_album_id_only_format(tmp_path):
    legacy = _base_file(tmp_path, "4aawyAB9vmqN3uQ7FjRGTy.png", 10, 1000)
    current = _base_file(tmp_path, "4aawyAB9vmqN3uQ7FjRGTy_1920x1080_0123456789ab.png", 10, 1000)

    prune_album_bases(tmp_path, keep=current)

    assert not legacy.exists()
    assert current.exists()


def test_prune_drops_the_least_recently_used_bases_over_budget(tmp_path):
    old = _base_file(tmp_path, "a_1920x1080_000000000001.png", 100, 1000)
    mid = _base_file(tmp_path, "b_1920x1080_000000000002.png", 100, 2000)
    new = _base_file(tmp_path, "c_1920x1080_000000000003.png", 100, 3000)

    prune_album_bases(tmp_path, keep=new, max_bytes=250)

    assert not old.exists()
    assert mid.exists() and new.exists()


def test_prune_never_drops_the_base_in_use(tmp_path):
    in_use = _base_file(tmp_path, "a_1920x1080_000000000001.png", 500, 1000)

    prune_album_bases(tmp_path, keep=in_use, max_bytes=100)

    assert in_use.exists()


def test_prune_leaves_everything_under_budget(tmp_path):
    files = [_base_file(tmp_path, f"{c}_1920x1080_00000000000{i}.png", 100, 1000 + i) for i, c in enumerate("abc")]

    prune_album_bases(tmp_path, keep=files[0], max_bytes=10_000)

    assert all(f.exists() for f in files)


def test_prune_keeps_the_base_in_use_whatever_its_name(tmp_path):
    in_use = _base_file(tmp_path, "base.png", 10, 1000)

    prune_album_bases(tmp_path, keep=in_use)

    assert in_use.exists()
