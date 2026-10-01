import subprocess
import sys

import pytest
from PIL import ImageFont

from src.graphics import renderer


@pytest.fixture(autouse=True)
def _fresh_cache():
    renderer._font_files.cache_clear()
    yield
    renderer._font_files.cache_clear()


def _height(font):
    left, top, right, bottom = font.getbbox("Civil War")
    return bottom - top


@pytest.mark.skipif(sys.platform == "win32", reason="fontconfig: not on Windows")
def test_off_windows_the_text_is_a_real_font_at_the_asked_size():
    # Regression: on Linux the title was Pillow's bitmap font, a few pixels tall.
    small, large = renderer._load_font(20, bold=True), renderer._load_font(60, bold=True)

    assert isinstance(large, ImageFont.FreeTypeFont)
    assert _height(large) > 2 * _height(small) and _height(large) >= 35


def test_fontconfigs_bold_and_regular_picks_are_used(monkeypatch, tmp_path):
    monkeypatch.setattr(renderer.sys, "platform", "linux")
    asked = []

    def fc_match(args, **kwargs):
        asked.append(args[-1])
        return subprocess.CompletedProcess(args, 0, "/nowhere/font.ttf", "")

    monkeypatch.setattr(renderer.subprocess, "run", fc_match)
    monkeypatch.setattr(renderer.Path, "is_file", lambda self: True)

    assert renderer._font_files(True) == (renderer.Path("/nowhere/font.ttf"),)
    renderer._font_files(True)  # cached: fontconfig is asked once per weight
    renderer._font_files(False)

    assert asked == ["sans-serif:bold", "sans-serif"]


@pytest.mark.parametrize("failure", [FileNotFoundError("fc-match"), "empty"], ids=["no fc-match", "no match"])
def test_without_fontconfig_the_fallback_still_honours_the_size(monkeypatch, failure):
    monkeypatch.setattr(renderer.sys, "platform", "linux")

    def fc_match(args, **kwargs):
        if isinstance(failure, Exception):
            raise failure
        return subprocess.CompletedProcess(args, 1, "", "")

    monkeypatch.setattr(renderer.subprocess, "run", fc_match)

    small, large = renderer._load_font(20, bold=False), renderer._load_font(60, bold=False)

    assert _height(large) > 2 * _height(small)


def test_windows_keeps_segoe_ui(monkeypatch, tmp_path):
    monkeypatch.setattr(renderer.sys, "platform", "win32")
    monkeypatch.setenv("WINDIR", str(tmp_path))

    assert renderer._font_files(True) == (tmp_path / "Fonts" / "segoeuib.ttf",)
    assert renderer._font_files(False) == (tmp_path / "Fonts" / "segoeui.ttf",)
