from src.config.settings import Settings
from src.graphics import layout as layout_module


def test_compute_layout_centers_art(monkeypatch):
    monkeypatch.setattr(layout_module, "get_primary_resolution", lambda fallback: (1920, 1080))
    settings = Settings(art_size_pct=0.5)

    result = layout_module.compute_layout(settings)

    assert result.canvas_size == (1920, 1080)
    assert result.art_size == 540
    assert result.art_position == ((1920 - 540) // 2, (1080 - 540) // 2)


def test_get_primary_resolution_falls_back_on_error(monkeypatch):
    class BrokenUser32:
        def GetSystemMetrics(self, index):
            raise OSError("no display")

    class BrokenWindll:
        user32 = BrokenUser32()

        def __getattr__(self, name):
            raise AttributeError(name)

    monkeypatch.setattr(layout_module.ctypes, "windll", BrokenWindll())

    result = layout_module.get_primary_resolution((1920, 1080))

    assert result == (1920, 1080)
