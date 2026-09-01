from PIL import Image, ImageDraw, ImageFont

from src.config.settings import Settings
from src.graphics import layout as layout_module
from src.graphics.renderer import _text_color_for_background, _truncate_to_width, render_for_now_playing
from src.spotify.client import NowPlaying


def test_text_color_for_background_picks_light_text_on_dark_bg():
    assert _text_color_for_background((10, 10, 10)) == (245, 245, 245)


def test_text_color_for_background_picks_dark_text_on_light_bg():
    assert _text_color_for_background((240, 240, 240)) == (20, 20, 20)


def test_truncate_to_width_leaves_short_text_untouched():
    image = Image.new("RGB", (10, 10))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()

    result = _truncate_to_width(draw, "Short", font, max_width=1000)

    assert result == "Short"


def test_truncate_to_width_shortens_long_text_with_ellipsis():
    image = Image.new("RGB", (10, 10))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    long_text = "A" * 200

    result = _truncate_to_width(draw, long_text, font, max_width=50)

    assert result.endswith("…")
    assert len(result) < len(long_text)


def test_render_for_now_playing_reuses_cached_base_without_downloading(tmp_path, monkeypatch):
    monkeypatch.setattr(layout_module, "get_primary_resolution", lambda fallback: (400, 300))

    base_path = tmp_path / "base.png"
    Image.new("RGB", (400, 300), (30, 60, 90)).save(base_path)

    def fail_if_called(url):
        raise AssertionError("download_art should not be called when the base cache exists")

    monkeypatch.setattr("src.graphics.renderer.download_art", fail_if_called)

    now_playing = NowPlaying(
        is_playing=True,
        track_id="t1",
        album_id="a1",
        art_url="http://example.invalid/art.jpg",
        track_name="Test Track",
        artist_name="Test Artist",
    )
    settings = Settings(fallback_resolution=[400, 300])
    output_path = tmp_path / "out.png"

    render_for_now_playing(now_playing, settings, base_path, output_path)

    assert output_path.exists()
    result = Image.open(output_path)
    assert result.size == (400, 300)
