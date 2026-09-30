import json
import os
import time
from io import BytesIO

import pytest
from PIL import Image

from src.config.settings import Settings
from src.graphics import art_cache, renderer
from src.graphics.art_cache import AlbumArtCache
from src.graphics.layout import ArtLayout
from src.spotify.client import NowPlaying

_LAYOUT = ArtLayout(canvas_size=(400, 300), art_size=100, art_position=(150, 100))


def _art(color=(200, 40, 40)) -> bytes:
    image = Image.new("RGB", (64, 64), color)
    image.paste((20, 60, 200), (0, 0, 32, 64))
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class _Downloads:
    def __init__(self, data=None):
        self.data = data if data is not None else _art()
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return self.data


def test_the_first_load_downloads_and_keeps_the_art(tmp_path):
    cache = AlbumArtCache(tmp_path)
    download = _Downloads()

    first = cache.load("a1", download)
    second = AlbumArtCache(tmp_path).load("a1", download)  # a new run of the app

    assert (first.from_disk, second.from_disk) == (False, True)
    assert second.bytes == first.bytes == download.data
    assert download.calls == 1


def test_the_colors_are_worked_out_once_and_survive_a_restart(tmp_path, monkeypatch):
    art = AlbumArtCache(tmp_path).load("a1", _Downloads())
    dominant, accents = art.colors.dominant(), art.colors.accents()
    assert accents

    def boom(_bytes):
        raise AssertionError("the colors were stored: no ColorThief pass")

    monkeypatch.setattr(art_cache, "extract_dominant_color", boom)
    monkeypatch.setattr(art_cache, "extract_accent_palette", boom)
    again = AlbumArtCache(tmp_path).load("a1", _Downloads())

    assert (again.colors.dominant(), again.colors.accents()) == (dominant, accents)


def test_the_accents_are_only_worked_out_when_a_style_asks_for_them(tmp_path, monkeypatch):
    calls = []
    real = art_cache.extract_accent_palette
    monkeypatch.setattr(art_cache, "extract_accent_palette", lambda b: calls.append(1) or real(b))
    first = AlbumArtCache(tmp_path).load("a1", _Downloads())
    dominant = first.colors.dominant()  # a solid background: dominant only
    assert calls == []

    later = AlbumArtCache(tmp_path).load("a1", _Downloads())  # then the user picks mesh
    assert later.colors.dominant() == dominant
    accents = later.colors.accents()
    assert calls == [1] and accents

    stored = json.loads(next(tmp_path.glob("*.colors.json")).read_text(encoding="utf-8"))
    assert stored["dominant"] == list(dominant) and len(stored["accents"]) == len(accents)


@pytest.mark.parametrize("content", ["not json", '{"version": 99, "dominant": [1, 2, 3]}', '{"version": 1, "dominant": [1, 2]}', "[]"])
def test_unusable_stored_colors_are_worked_out_again(tmp_path, content):
    AlbumArtCache(tmp_path).load("a1", _Downloads())
    next(tmp_path.glob("*.colors.json"), tmp_path / "a1.colors.json").write_text(content, encoding="utf-8")

    art = AlbumArtCache(tmp_path).load("a1", _Downloads())

    assert art.from_disk and len(art.colors.dominant()) == 3


def test_a_cover_that_cannot_be_read_leaves_no_colors_behind(tmp_path):
    art = AlbumArtCache(tmp_path).load("a1", _Downloads(b"not an image"))

    assert art.colors.dominant() == (30, 30, 30)
    assert art.colors.accents() == []
    assert list(tmp_path.glob("*.colors.json")) == []


def test_discard_forgets_the_art_and_its_colors(tmp_path):
    cache = AlbumArtCache(tmp_path)
    cache.load("a1", _Downloads()).colors.dominant()

    cache.discard("a1")

    assert list(tmp_path.iterdir()) == []


def test_ids_that_are_not_file_names_cannot_leave_the_folder(tmp_path):
    AlbumArtCache(tmp_path).load("../../evil", _Downloads()).colors.dominant()

    assert all(p.parent == tmp_path for p in tmp_path.rglob("*"))
    assert not (tmp_path.parent / "evil.art").exists()


def test_the_least_recently_used_album_goes_first_and_the_current_one_stays(tmp_path):
    size = len(_art())
    cache = AlbumArtCache(tmp_path, max_bytes=size * 2 + 10)
    for index, album in enumerate(("a1", "a2")):
        cache.load(album, _Downloads())
        stamp = time.time() - 100 + index
        os.utime(tmp_path / f"{album}.art", (stamp, stamp))
    cache.load("a1", _Downloads())  # a1 used again: a2 is now the oldest

    cache.load("a3", _Downloads())

    names = {p.name for p in tmp_path.glob("*.art")}
    assert names == {"a1.art", "a3.art"}


def test_a_failed_write_is_not_an_error(tmp_path, monkeypatch):
    def broken(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr(art_cache.os, "replace", broken)

    art = AlbumArtCache(tmp_path).load("a1", _Downloads())

    assert art.bytes and list(tmp_path.glob("*.tmp")) == []


def _now_playing(album="a1"):
    return NowPlaying(True, "t1", album, "https://i.scdn.co/image/x", "Track", "Artist")


def test_changing_the_style_redraws_from_the_stored_art(tmp_path, monkeypatch):
    downloads = _Downloads()
    monkeypatch.setattr(renderer, "download_art", lambda url: downloads())
    cache = AlbumArtCache(tmp_path / "art")
    settings = Settings(show_track_info=False)
    renderer.render_for_now_playing(_now_playing(), settings, _LAYOUT, tmp_path / "b1.png", tmp_path / "o1.png", cache)

    mesh = Settings(show_track_info=False, background_style="mesh")
    renderer.render_for_now_playing(_now_playing(), mesh, _LAYOUT, tmp_path / "b2.png", tmp_path / "o2.png", cache)

    assert downloads.calls == 1
    assert (tmp_path / "b2.png").exists()


def test_stored_art_that_is_not_an_image_is_downloaded_again(tmp_path, monkeypatch):
    downloads = _Downloads()
    monkeypatch.setattr(renderer, "download_art", lambda url: downloads())
    cache = AlbumArtCache(tmp_path / "art")
    (tmp_path / "art").mkdir()
    (tmp_path / "art" / "a1.art").write_bytes(b"damaged")

    renderer.render_for_now_playing(
        _now_playing(), Settings(show_track_info=False), _LAYOUT, tmp_path / "b.png", tmp_path / "o.png", cache
    )

    assert downloads.calls == 1
    assert (tmp_path / "art" / "a1.art").read_bytes() == downloads.data
    assert (tmp_path / "o.png").exists()


def test_fresh_art_that_is_not_an_image_still_fails_loudly(tmp_path, monkeypatch):
    monkeypatch.setattr(renderer, "download_art", lambda url: b"not an image")
    cache = AlbumArtCache(tmp_path / "art")

    with pytest.raises(OSError):
        renderer.render_for_now_playing(
            _now_playing(), Settings(show_track_info=False), _LAYOUT, tmp_path / "b.png", tmp_path / "o.png", cache
        )
