import subprocess
import sys

from src.config.settings import Settings
from src.graphics import layout as layout_module
from src.os_integration import hyprland


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

    monkeypatch.setattr(sys, "platform", "win32")
    # raising=False: ctypes has no windll outside Windows.
    monkeypatch.setattr(layout_module.ctypes, "windll", BrokenWindll(), raising=False)

    result = layout_module.get_primary_resolution((1920, 1080))

    assert result == (1920, 1080)


def _hyprctl(stdout, returncode=0):
    def run(args, **kwargs):
        assert args == ["hyprctl", "monitors", "-j"]
        return subprocess.CompletedProcess(args, returncode, stdout, "")

    return run


def test_off_windows_the_resolution_comes_from_hyprland(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(hyprland.subprocess, "run", _hyprctl('[{"id": 0, "width": 2560, "height": 1440, "transform": 0}]'))

    assert layout_module.get_primary_resolution((1920, 1080)) == (2560, 1440)


def test_without_hyprland_the_fallback_is_used(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")

    def no_hyprctl(args, **kwargs):
        raise FileNotFoundError("hyprctl")

    monkeypatch.setattr(hyprland.subprocess, "run", no_hyprctl)

    assert layout_module.get_primary_resolution((1366, 768)) == (1366, 768)
