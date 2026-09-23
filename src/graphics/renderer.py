import logging
import os
from io import BytesIO
from pathlib import Path
from typing import Optional, Tuple

import requests
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageStat

from src.config.settings import Settings
from src.graphics.color_extractor import extract_dominant_color
from src.graphics.layout import ArtLayout
from src.spotify.client import NowPlaying

logger = logging.getLogger(__name__)

_DOWNLOAD_TIMEOUT_SECONDS = 10
_TEXT_MAX_WIDTH_PCT = 0.8


def download_art(url: str) -> bytes:
    response = requests.get(url, timeout=_DOWNLOAD_TIMEOUT_SECONDS)
    response.raise_for_status()
    return response.content


def _rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), (size - 1, size - 1)], radius=radius, fill=255)
    return mask


def _shadow_layer(canvas_size: Tuple[int, int], layout: ArtLayout, settings: Settings) -> Image.Image:
    shadow = Image.new("RGBA", canvas_size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(shadow)
    pad = settings.shadow_blur_radius
    x, y = layout.art_position
    box = [
        (x - pad // 2, y - pad // 2 + 8),
        (x + layout.art_size + pad // 2, y + layout.art_size + pad // 2 + 8),
    ]
    draw.rounded_rectangle(box, radius=settings.corner_radius + pad // 2, fill=(0, 0, 0, 140))
    return shadow.filter(ImageFilter.GaussianBlur(settings.shadow_blur_radius))


def _build_base_canvas(art_bytes: bytes, settings: Settings, layout: ArtLayout) -> Image.Image:
    """Background fill + shadow + centered rounded art. No track text — this is the
    part that's identical for every track on the same album, so it's safe to cache."""
    dominant_rgb = extract_dominant_color(art_bytes)

    canvas = Image.new("RGB", layout.canvas_size, dominant_rgb).convert("RGBA")

    shadow = _shadow_layer(layout.canvas_size, layout, settings)
    canvas = Image.alpha_composite(canvas, shadow)

    art = Image.open(BytesIO(art_bytes)).convert("RGB")
    art = art.resize((layout.art_size, layout.art_size), Image.LANCZOS)
    mask = _rounded_mask(layout.art_size, settings.corner_radius)

    canvas.paste(art, layout.art_position, mask)

    return canvas.convert("RGB")


def _load_font(size: int, bold: bool) -> ImageFont.ImageFont:
    fonts_dir = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts"
    candidates = ["segoeuib.ttf"] if bold else ["segoeui.ttf"]
    for name in candidates:
        font_path = fonts_dir / name
        if font_path.exists():
            try:
                return ImageFont.truetype(str(font_path), size)
            except OSError:
                continue
    return ImageFont.load_default()


def _text_color_for_background(rgb: Tuple[int, int, int]) -> Tuple[int, int, int]:
    r, g, b = rgb
    luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return (245, 245, 245) if luminance < 140 else (20, 20, 20)


def _truncate_to_width(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont, max_width: int) -> str:
    if draw.textlength(text, font=font) <= max_width:
        return text
    ellipsis = "…"
    truncated = text
    while truncated and draw.textlength(truncated + ellipsis, font=font) > max_width:
        truncated = truncated[:-1]
    return (truncated + ellipsis) if truncated else ellipsis


def _font_sizes(canvas_height: int) -> Tuple[int, int]:
    """(title, artist) font sizes in px."""
    return max(24, int(canvas_height * 0.035)), max(18, int(canvas_height * 0.024))


def _text_band(layout: ArtLayout) -> Tuple[int, int, int, int]:
    """The box the track text can occupy: below the art, _TEXT_MAX_WIDTH_PCT wide."""
    canvas_width, canvas_height = layout.canvas_size
    title_size, artist_size = _font_sizes(canvas_height)
    half_width = int(canvas_width * _TEXT_MAX_WIDTH_PCT) // 2
    center_x = layout.art_position[0] + layout.art_size // 2
    top = layout.art_position[1] + layout.art_size + int(canvas_height * 0.035)
    bottom = top + title_size + int(canvas_height * 0.012) + artist_size
    return (
        max(0, center_x - half_width),
        min(top, canvas_height - 1),
        min(canvas_width, center_x + half_width),
        min(canvas_height, max(bottom, top + 1)),
    )


def _average_color(image: Image.Image, box: Tuple[int, int, int, int]) -> Tuple[int, int, int]:
    mean = ImageStat.Stat(image.crop(box)).mean
    return (round(mean[0]), round(mean[1]), round(mean[2]))


def _draw_track_info(
    canvas: Image.Image,
    layout: ArtLayout,
    background_rgb: Tuple[int, int, int],
    track_name: Optional[str],
    artist_name: Optional[str],
) -> None:
    if not track_name and not artist_name:
        return

    draw = ImageDraw.Draw(canvas)
    color = _text_color_for_background(background_rgb)
    canvas_width, canvas_height = layout.canvas_size
    max_width = int(canvas_width * _TEXT_MAX_WIDTH_PCT)
    center_x = layout.art_position[0] + layout.art_size // 2

    title_size, artist_size = _font_sizes(canvas_height)
    title_font = _load_font(title_size, bold=True)
    artist_font = _load_font(artist_size, bold=False)

    y = layout.art_position[1] + layout.art_size + int(canvas_height * 0.035)

    if track_name:
        text = _truncate_to_width(draw, track_name, title_font, max_width)
        bbox = draw.textbbox((0, 0), text, font=title_font)
        draw.text((center_x - (bbox[2] - bbox[0]) // 2, y), text, font=title_font, fill=color)
        y += (bbox[3] - bbox[1]) + int(canvas_height * 0.012)

    if artist_name:
        text = _truncate_to_width(draw, artist_name, artist_font, max_width)
        bbox = draw.textbbox((0, 0), text, font=artist_font)
        draw.text((center_x - (bbox[2] - bbox[0]) // 2, y), text, font=artist_font, fill=color)


def render_for_now_playing(
    now_playing: NowPlaying, settings: Settings, layout: ArtLayout, base_path: Path, output_path: Path
) -> None:
    if base_path.exists():
        base_image = Image.open(base_path).convert("RGB")
        logger.info("Reusing cached base art for album %s", now_playing.album_id)
    else:
        art_bytes = download_art(now_playing.art_url)
        base_image = _build_base_canvas(art_bytes, settings, layout)
        base_image.save(base_path, format="PNG")
        logger.info("Rendered new base art for album %s", now_playing.album_id)

    final_image = base_image.copy()
    if settings.show_track_info:
        # Sampled from what is actually behind the text: a gradient background
        # has no single color, and its corners say nothing about the text band.
        background_rgb = _average_color(base_image, _text_band(layout))
        _draw_track_info(final_image, layout, background_rgb, now_playing.track_name, now_playing.artist_name)

    final_image.save(output_path, format="PNG")
    logger.info("Rendered wallpaper to %s", output_path)
