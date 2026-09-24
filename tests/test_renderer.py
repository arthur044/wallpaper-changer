import dataclasses

from PIL import Image, ImageDraw, ImageFont, ImageStat

from src.config.settings import Settings
from src.graphics.layout import ArtLayout
from src.graphics.renderer import (
    _card_box,
    _ink_bounds,
    _layout_track_info,
    _text_color_for_background,
    _truncate_to_width,
    render_for_now_playing,
)
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



def _checkerboard(size, cell=4) -> Image.Image:
    image = Image.new("RGB", size, (0, 0, 0))
    draw = ImageDraw.Draw(image)
    for x in range(0, size[0], cell):
        for y in range(0, size[1], cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=(255, 255, 255))
    return image


def test_card_box_pads_the_text_and_stays_on_the_canvas():
    box = _card_box((100, 200, 300, 255), canvas_size=(400, 260))

    left, top, right, bottom = box
    assert left < 100 and top < 200 and right > 300
    assert bottom == 260, "clamped to the canvas edge"
    assert left >= 0 and top >= 0


def test_glass_card_frosts_what_is_behind_the_text(tmp_path):
    base = _checkerboard(_LAYOUT.canvas_size)
    base_path = tmp_path / "base.png"
    base.save(base_path)
    out = tmp_path / "out.png"

    render_for_now_playing(_now_playing(), Settings(text_card="glass"), _LAYOUT, base_path, out)

    result = Image.open(out).convert("RGB")
    # The card's top padding: inside the card, above the glyphs.
    draw = ImageDraw.Draw(base.copy())
    ink = _ink_bounds(draw, _layout_track_info(draw, _LAYOUT, "WWWWWW", "MMMMMM"))
    card = _card_box(ink, _LAYOUT.canvas_size)
    strip = (ink[0], card[1] + 2, ink[2], ink[1] - 1)
    assert strip[3] - strip[1] >= 3
    assert ImageStat.Stat(result.crop(strip)).stddev[0] < 20, "the checkerboard should be blurred away"
    assert ImageStat.Stat(base.crop(strip)).stddev[0] > 100


def test_text_color_is_judged_on_the_card(tmp_path):
    # Mid-gray reads as "dark" (light text) on its own; the frosted card's
    # white tint lifts it past the threshold, so the text over it turns dark.
    base = Image.new("RGB", _LAYOUT.canvas_size, (128, 128, 128))
    base_path = tmp_path / "base.png"
    base.save(base_path)

    plain = tmp_path / "plain.png"
    glass = tmp_path / "glass.png"
    render_for_now_playing(_now_playing(), Settings(), _LAYOUT, base_path, plain)
    render_for_now_playing(_now_playing(), Settings(text_card="glass"), _LAYOUT, base_path, glass)

    assert max(_text_band_sums(Image.open(plain).convert("RGB"))) > 3 * 200
    assert min(_text_band_sums(Image.open(glass).convert("RGB"))) < 3 * 100


def test_no_card_without_track_info(tmp_path):
    base = _checkerboard(_LAYOUT.canvas_size)
    base_path = tmp_path / "base.png"
    base.save(base_path)
    out = tmp_path / "out.png"

    render_for_now_playing(
        _now_playing(), Settings(text_card="glass", show_track_info=False), _LAYOUT, base_path, out
    )

    assert Image.open(out).convert("RGB").tobytes() == base.tobytes()



def _art_top_red_bottom_blue() -> bytes:
    art = Image.new("RGB", (64, 64), (200, 30, 30))
    art.paste((30, 40, 200), (0, 32, 64, 64))
    return _png_bytes(art)


def _render_base_only(tmp_path, monkeypatch, settings, art_bytes) -> Image.Image:
    monkeypatch.setattr("src.graphics.renderer.download_art", lambda url: art_bytes)
    out = tmp_path / "out.png"
    render_for_now_playing(
        _now_playing(), dataclasses.replace(settings, show_track_info=False), _LAYOUT, tmp_path / "b.png", out
    )
    return Image.open(out).convert("RGB")


def test_blurred_art_background_is_the_cover_itself(tmp_path, monkeypatch):
    # The layout is landscape, so the square art covers it by width: its top
    # (red) ends up behind the top of the screen, its bottom (blue) at the bottom.
    result = _render_base_only(tmp_path, monkeypatch, Settings(background_style="blur"), _art_top_red_bottom_blue())

    top_left = result.getpixel((5, 5))
    bottom_left = result.getpixel((5, 294))
    assert top_left[0] > top_left[2], f"top should be reddish, got {top_left}"
    assert bottom_left[2] > bottom_left[0], f"bottom should be bluish, got {bottom_left}"


def test_blurred_art_background_is_darkened_toward_the_edges(tmp_path, monkeypatch):
    flat = Image.new("RGB", (64, 64), (200, 200, 200))
    result = _render_base_only(tmp_path, monkeypatch, Settings(background_style="blur"), _png_bytes(flat))

    corner = sum(result.getpixel((1, 1)))
    assert corner < 3 * 200 * 0.8, "the vignette should darken the corners"


def test_blurred_art_background_needs_no_accent_palette(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr("src.graphics.renderer.extract_accent_palette", lambda b: calls.append(1) or [])

    _render_base_only(tmp_path, monkeypatch, Settings(background_style="blur"), _art_top_red_bottom_blue())

    assert calls == []


def test_glass_frame_takes_the_arts_place_and_shrinks_the_art(tmp_path, monkeypatch):
    # White art on a black fill: without a frame, the art reaches its layout edge.
    white = _png_bytes(Image.new("RGB", (64, 64), (255, 255, 255)))
    black_fill = Settings(shadow_blur_radius=0)
    monkeypatch.setattr("src.graphics.renderer.extract_dominant_color", lambda b: (0, 0, 0))

    (tmp_path / "plain").mkdir()
    (tmp_path / "frame").mkdir()
    plain = _render_base_only(tmp_path / "plain", monkeypatch, black_fill, white)
    framed = _render_base_only(tmp_path / "frame", monkeypatch, dataclasses.replace(black_fill, art_frame="double"), white)

    x, y = _LAYOUT.art_position
    near_edge = (x + 3, y + _LAYOUT.art_size // 2)  # inside the art's footprint, by its left edge
    assert plain.getpixel(near_edge) == (255, 255, 255)
    edge = framed.getpixel(near_edge)
    assert 0 < sum(edge) < 3 * 200, f"a translucent glass frame should sit there now, got {edge}"
    centre = (x + _LAYOUT.art_size // 2, y + _LAYOUT.art_size // 2)
    assert framed.getpixel(centre) == (255, 255, 255), "the art is still in the middle"
    outside = (x - 3, y + _LAYOUT.art_size // 2)
    assert framed.getpixel(outside) == (0, 0, 0), "nothing grows past the art's old footprint"



def test_a_single_frame_leaves_the_art_bigger_than_a_double_one(tmp_path, monkeypatch):
    white = _png_bytes(Image.new("RGB", (64, 64), (255, 255, 255)))
    black_fill = Settings(shadow_blur_radius=0)
    monkeypatch.setattr("src.graphics.renderer.extract_dominant_color", lambda b: (0, 0, 0))

    def art_width(style):
        (tmp_path / style).mkdir()
        image = _render_base_only(tmp_path / style, monkeypatch, dataclasses.replace(black_fill, art_frame=style), white)
        row = _LAYOUT.art_position[1] + _LAYOUT.art_size // 2
        return sum(1 for x in range(image.width) if image.getpixel((x, row)) == (255, 255, 255))

    none, single, double = art_width("none"), art_width("single"), art_width("double")
    assert none > single > double > 0


def test_reusing_a_cached_base_marks_it_as_recently_used(tmp_path):
    import os

    base_path = tmp_path / "base.png"
    Image.new("RGB", _LAYOUT.canvas_size, (40, 80, 120)).save(base_path)
    os.utime(base_path, (1000, 1000))

    render_for_now_playing(_now_playing(), Settings(show_track_info=False), _LAYOUT, base_path, tmp_path / "out.png")

    assert base_path.stat().st_mtime > 1000, "a base in use must not look stale to the cache limit"



def test_a_stronger_blur_smooths_the_background_more(tmp_path, monkeypatch):
    # Fine stripes: the weaker the blur, the more of them survive.
    art = Image.new("RGB", (64, 64), (0, 0, 0))
    draw = ImageDraw.Draw(art)
    for x in range(0, 64, 4):
        draw.rectangle([x, 0, x + 1, 63], fill=(255, 255, 255))

    def spread(strength):
        (tmp_path / str(strength)).mkdir()
        image = _render_base_only(
            tmp_path / str(strength), monkeypatch, Settings(background_style="blur", blur_strength=strength), _png_bytes(art)
        )
        return ImageStat.Stat(image.crop((0, 0, 60, 30))).stddev[0]

    assert spread(5) > spread(26) > spread(100)
