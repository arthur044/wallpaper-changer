from src.os_integration import wallpaper


def _record(monkeypatch, fade_error=None):
    calls = []
    monkeypatch.setattr(wallpaper, "_ensure_fill_style", lambda: None)

    def fade(path):
        calls.append(("fade", path))
        if fade_error:
            raise fade_error

    monkeypatch.setattr(wallpaper, "_set_with_fade", fade)
    monkeypatch.setattr(wallpaper, "_set_directly", lambda path: calls.append(("direct", path)))
    return calls


def test_by_default_the_wallpaper_is_set_directly(monkeypatch, tmp_path):
    calls = _record(monkeypatch)

    wallpaper.set_wallpaper(tmp_path / "a.png")

    assert [kind for kind, _ in calls] == ["direct"]


def test_smooth_goes_through_the_fading_path_only(monkeypatch, tmp_path):
    calls = _record(monkeypatch)

    wallpaper.set_wallpaper(tmp_path / "a.png", smooth=True)

    assert calls == [("fade", str((tmp_path / "a.png").resolve()))]


def test_a_failed_fade_still_sets_the_wallpaper(monkeypatch, tmp_path):
    calls = _record(monkeypatch, fade_error=OSError("COM said no"))

    wallpaper.set_wallpaper(tmp_path / "a.png", smooth=True)

    assert [kind for kind, _ in calls] == ["fade", "direct"]
