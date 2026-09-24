"""Frosted-glass card: the pixels behind a box, blurred, lightly tinted and
edged with a hairline — CSS's backdrop-filter, done on the rendered image."""
from typing import Tuple

from PIL import Image, ImageDraw, ImageFilter

Box = Tuple[int, int, int, int]

_TINT = (255, 255, 255)
_TINT_AMOUNT = 0.18
_BORDER_RGBA = (255, 255, 255, 70)


def frost(canvas: Image.Image, box: Box, blur: int, radius: int) -> None:
    """Replaces `box` of the RGB `canvas` with a frosted card, in place."""
    left, top, right, bottom = box
    width, height = canvas.size
    # Blur a margin around the card too, so its edge averages the real
    # neighbourhood instead of the crop's clamped border.
    margin = blur * 2
    outer = (max(0, left - margin), max(0, top - margin), min(width, right + margin), min(height, bottom + margin))
    blurred = canvas.crop(outer).filter(ImageFilter.GaussianBlur(blur))
    inner = blurred.crop((left - outer[0], top - outer[1], right - outer[0], bottom - outer[1]))

    size = inner.size
    card = Image.blend(inner, Image.new("RGB", size, _TINT), _TINT_AMOUNT).convert("RGBA")
    edge = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, outline=_BORDER_RGBA, width=1)
    card = Image.alpha_composite(card, edge).convert("RGB")

    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    canvas.paste(card, (left, top), mask)
