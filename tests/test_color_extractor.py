import io

from PIL import Image

from src.graphics.color_extractor import extract_dominant_color, to_hex


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
