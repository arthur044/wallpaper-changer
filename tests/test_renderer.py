from PIL import Image, ImageDraw, ImageFont

from src.config.settings import Settings
from src.graphics.layout import ArtLayout
from src.graphics.renderer import _text_color_for_background, _truncate_to_width, render_for_now_playing
from src.spotify.client import NowPlaying

_LAYOUT = ArtLayout(canvas_size=(400, 300), art_size=100, art_position=(150, 40))


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
    output_path = tmp_path / "out.png"

    render_for_now_playing(now_playing, Settings(), _LAYOUT, base_path, output_path)

    assert output_path.exists()
    result = Image.open(output_path)
    assert result.size == (400, 300)


def _now_playing() -> NowPlaying:
    return NowPlaying(
        is_playing=True,
        track_id="t1",
        album_id="a1",
        art_url="http://example.invalid/art.jpg",
        track_name="WWWWWW",
        artist_name="MMMMMM",
    )


def _render_on(tmp_path, base: Image.Image) -> Image.Image:
    base_path = tmp_path / "base.png"
    base.save(base_path)
    output_path = tmp_path / "out.png"
    render_for_now_playing(_now_playing(), Settings(), _LAYOUT, base_path, output_path)
    return Image.open(output_path).convert("RGB")


def _text_band_sums(image: Image.Image):
    # Everything below the art, where the title and artist are drawn.
    top = _LAYOUT.art_position[1] + _LAYOUT.art_size
    return [sum(image.getpixel((x, y))) for x in range(40, 360) for y in range(top, 300)]


def test_text_color_follows_what_is_behind_the_text_not_the_corner(tmp_path):
    # A black corner used to decide the text color; the text sits on white.
    base = Image.new("RGB", _LAYOUT.canvas_size, (255, 255, 255))
    base.paste((0, 0, 0), (0, 0, 20, 20))

    result = _render_on(tmp_path, base)

    assert min(_text_band_sums(result)) < 3 * 100, "text on a white band should be dark"


def test_text_on_a_dark_band_is_light_even_with_a_white_corner(tmp_path):
    base = Image.new("RGB", _LAYOUT.canvas_size, (10, 10, 10))
    base.paste((255, 255, 255), (0, 0, 20, 20))

    result = _render_on(tmp_path, base)

    assert max(_text_band_sums(result)) > 3 * 200, "text on a dark band should be light"


def _png_bytes(image: Image.Image) -> bytes:
    import io

    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_new_album_draws_the_solid_base_and_caches_it(tmp_path, monkeypatch):
    art = Image.new("RGB", (64, 64), (200, 40, 40))
    downloads = []

    def fake_download(url):
        downloads.append(url)
        return _png_bytes(art)

    monkeypatch.setattr("src.graphics.renderer.download_art", fake_download)
    base_path = tmp_path / "base.png"

    render_for_now_playing(_now_playing(), Settings(show_track_info=False), _LAYOUT, base_path, tmp_path / "out.png")

    assert downloads == ["http://example.invalid/art.jpg"]
    assert base_path.exists()
    result = Image.open(tmp_path / "out.png").convert("RGB")
    # "solid": plain dominant-color fill away from the art...
    corner = result.getpixel((0, 0))
    assert corner[0] > 150 and corner[1] < 90 and corner[2] < 90
    # ...and the art itself in the middle.
    assert result.getpixel((200, 90)) == (200, 40, 40)


def _art_with_accent() -> bytes:
    # Mostly gray art (the dominant fill) with a vivid red corner (the accent).
    art = Image.new("RGB", (64, 64), (60, 60, 60))
    art.paste((230, 20, 40), (0, 0, 20, 20))
    return _png_bytes(art)


def _render_new_album(tmp_path, monkeypatch, settings) -> Image.Image:
    monkeypatch.setattr("src.graphics.renderer.download_art", lambda url: _art_with_accent())
    out = tmp_path / "out.png"
    render_for_now_playing(_now_playing(), settings, _LAYOUT, tmp_path / "base.png", out)
    return Image.open(out).convert("RGB")


def test_solid_base_keeps_a_dark_shadow_under_the_art(tmp_path, monkeypatch):
    result = _render_new_album(tmp_path, monkeypatch, Settings(show_track_info=False))

    below_art = result.getpixel((200, _LAYOUT.art_position[1] + _LAYOUT.art_size + 6))
    assert sum(below_art) < sum(result.getpixel((0, 0)))


def test_art_glow_tints_the_area_around_the_art_with_the_accent(tmp_path, monkeypatch):
    left_of_art = (_LAYOUT.art_position[0] - 6, _LAYOUT.art_position[1] + _LAYOUT.art_size // 2)

    (tmp_path / "plain").mkdir()
    (tmp_path / "glow").mkdir()
    plain = _render_new_album(tmp_path / "plain", monkeypatch, Settings(show_track_info=False))
    glow = _render_new_album(tmp_path / "glow", monkeypatch, Settings(show_track_info=False, art_glow=True))

    r_plain, g_plain, _ = plain.getpixel(left_of_art)
    r_glow, g_glow, _ = glow.getpixel(left_of_art)
    assert r_glow - g_glow > r_plain - g_plain + 30, "glow should push the edge toward the red accent"


def test_accent_palette_is_only_computed_when_an_effect_needs_it(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr("src.graphics.renderer.extract_accent_palette", lambda b: calls.append(1) or [])

    _render_new_album(tmp_path, monkeypatch, Settings(show_track_info=False))

    assert calls == []


def _corners(image: Image.Image):
    w, h = image.size
    return [image.getpixel(p) for p in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]]


def _art_with_colors() -> bytes:
    art = Image.new("RGB", (64, 64), (30, 40, 90))
    art.paste((230, 140, 30), (0, 0, 24, 24))
    art.paste((40, 190, 150), (40, 40, 64, 64))
    return _png_bytes(art)


def test_mesh_style_varies_across_the_background(tmp_path, monkeypatch):
    monkeypatch.setattr("src.graphics.renderer.download_art", lambda url: _art_with_colors())
    out = tmp_path / "out.png"

    render_for_now_playing(
        _now_playing(), Settings(show_track_info=False, background_style="mesh"), _LAYOUT, tmp_path / "b.png", out
    )

    corners = _corners(Image.open(out).convert("RGB"))
    assert len(set(corners)) > 1, "a mesh background can't be one flat color"


def test_solid_style_keeps_one_flat_color_in_the_corners(tmp_path, monkeypatch):
    monkeypatch.setattr("src.graphics.renderer.download_art", lambda url: _art_with_colors())
    out = tmp_path / "out.png"

    render_for_now_playing(_now_playing(), Settings(show_track_info=False), _LAYOUT, tmp_path / "b.png", out)

    assert len(set(_corners(Image.open(out).convert("RGB")))) == 1


def test_glow_and_mesh_share_one_palette_extraction(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr("src.graphics.renderer.download_art", lambda url: _art_with_colors())
    monkeypatch.setattr("src.graphics.renderer.extract_accent_palette", lambda b: calls.append(1) or [])

    render_for_now_playing(
        _now_playing(),
        Settings(show_track_info=False, background_style="mesh", art_glow=True),
        _LAYOUT,
        tmp_path / "b.png",
        tmp_path / "out.png",
    )

    assert calls == [1]
