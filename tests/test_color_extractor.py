import io

from PIL import Image

from src.graphics.color_extractor import (
    extract_accent_palette,
    extract_dominant_color,
    pick_glow_color,
    pick_mesh_colors,
    to_hex,
)


def _solid_color_png(rgb) -> bytes:
    image = Image.new("RGB", (32, 32), rgb)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_extract_dominant_color_returns_expected_hue():
    image_bytes = _solid_color_png((200, 30, 30))

    color = extract_dominant_color(image_bytes)

    assert color[0] > color[1]
    assert color[0] > color[2]


def test_extract_dominant_color_falls_back_on_invalid_bytes():
    color = extract_dominant_color(b"not an image")

    assert color == (30, 30, 30)


def test_to_hex_formats_rgb_tuple():
    assert to_hex((255, 0, 128)) == "#ff0080"



def _distance(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def test_pick_glow_color_prefers_the_vivid_color_over_the_common_gray():
    palette = [(40, 40, 40), (120, 120, 120), (210, 30, 60), (90, 80, 70)]

    assert pick_glow_color(palette, background=(40, 40, 40)) == (210, 30, 60)


def test_pick_glow_color_lifts_a_color_lost_in_the_background():
    # All-dark art on its own dark fill: the glow must still read.
    palette = [(12, 12, 14), (20, 18, 22)]
    background = (12, 12, 14)

    glow = pick_glow_color(palette, background)

    assert _distance(glow, background) >= 80


def test_pick_glow_color_darkens_instead_on_a_light_background():
    palette = [(240, 240, 235)]
    background = (240, 240, 235)

    glow = pick_glow_color(palette, background)

    assert _distance(glow, background) >= 80
    assert sum(glow) < sum(background)


def test_pick_glow_color_without_palette_still_differs_from_background():
    glow = pick_glow_color([], background=(30, 30, 30))

    assert _distance(glow, (30, 30, 30)) >= 80


def test_extract_accent_palette_finds_a_small_vivid_accent():
    image = Image.new("RGB", (64, 64), (50, 50, 50))
    image.paste((230, 20, 40), (0, 0, 12, 12))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")

    palette = extract_accent_palette(buffer.getvalue())

    assert any(r > 180 and g < 80 and b < 90 for r, g, b in palette)


def test_extract_accent_palette_is_empty_on_invalid_bytes():
    assert extract_accent_palette(b"not an image") == []



def _min_pairwise_distance(colors):
    return min(_distance(a, b) for i, a in enumerate(colors) for b in colors[i + 1 :])


def test_pick_mesh_colors_starts_with_the_dominant_and_keeps_distinct_colors():
    dominant = (30, 40, 90)
    palette = [(30, 40, 90), (32, 42, 88), (220, 120, 40), (200, 30, 80), (40, 180, 160), (90, 90, 90)]

    colors = pick_mesh_colors(dominant, palette)

    assert colors[0] == dominant
    assert 3 <= len(colors) <= 4
    assert (32, 42, 88) not in colors, "a near-duplicate of the dominant adds nothing"
    assert _min_pairwise_distance(colors) >= 48


def test_pick_mesh_colors_fills_a_monochrome_cover_with_tones():
    colors = pick_mesh_colors((8, 8, 8), [(8, 8, 8), (12, 12, 12)])

    assert len(colors) >= 3
    assert colors[0] == (8, 8, 8)
    assert _min_pairwise_distance(colors) >= 20


def test_pick_mesh_colors_without_palette_still_gives_a_gradient():
    colors = pick_mesh_colors((200, 60, 60), [])

    assert len(colors) >= 3
    assert _min_pairwise_distance(colors) >= 20
