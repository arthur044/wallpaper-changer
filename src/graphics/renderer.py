import logging
import os
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import List, Optional, Tuple

import requests
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageStat

from src.config.settings import Settings
from src.graphics.base_cache import mark_used, prune_album_bases
from src.graphics.color_extractor import (
    extract_accent_palette,
    extract_dominant_color,
    pick_glow_color,
    pick_mesh_colors,
)
from src.graphics.glass import frost
from src.graphics.layout import ArtLayout
from src.graphics.mesh import mesh_background
from src.spotify.client import NowPlaying

logger = logging.getLogger(__name__)

_DOWNLOAD_TIMEOUT_SECONDS = 10
_TEXT_MAX_WIDTH_PCT = 0.8

_SHADOW_ALPHA = 140
_SHADOW_OFFSET_Y = 8
_GLOW_ALPHA = 200
_GLOW_BLUR_PCT = 0.08  # of the art side
_GLOW_SPREAD_PCT = 0.03
# Glass card around the track text, as fractions of the canvas height.
_CARD_PAD_X_PCT = 0.03
_CARD_PAD_Y_PCT = 0.018
_CARD_RADIUS_PCT = 0.02
_CARD_BLUR_PCT = 0.02
# "blur" background: the art covering the screen, blurred enough to soften it
# but not so much that its shapes stop reading, then darkened toward the edges.
# Sigma per point of blur_strength, as a fraction of the canvas short side:
# the default 26 gives 2.6%, 100 gives 10% (a cloud of color).
_BLUR_BG_SIGMA_PER_STRENGTH = 0.001
_BLUR_BG_DOWNSCALE = 4
_VIGNETTE_MIN_ALPHA = 50  # black over the centre...
_VIGNETTE_GAIN = 0.5  # ...rising toward the edges, up to ~177 in the corners
# Glass frame rims, outermost first: (gap as a fraction of the shrunken art
# side, veil alpha, edge alpha). The outermost gap sets how much the art shrinks.
_FRAME_RIMS = {
    "single": ((0.05, 28, 110),),
    "double": ((0.075, 24, 90), (0.035, 28, 110)),
}
# Big blurs run on a downscaled layer: same look (the result is smooth), about
# half the time on a full-screen canvas.
_MAX_BLUR_DOWNSCALE = 4


def download_art(url: str) -> bytes:
    response = requests.get(url, timeout=_DOWNLOAD_TIMEOUT_SECONDS)
    response.raise_for_status()
    return response.content


def _rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), (size - 1, size - 1)], radius=radius, fill=255)
    return mask


def _halo_layer(
    layout: ArtLayout,
    rgba: Tuple[int, int, int, int],
    blur: int,
    spread: int,
    offset_y: int,
    corner_radius: int,
) -> Image.Image:
    """A blurred rounded rectangle behind the art: the drop shadow, or the glow."""
    scale = max(1, min(_MAX_BLUR_DOWNSCALE, blur // 6))
    width, height = layout.canvas_size
    # Transparent pixels carry the halo's own color so blurring and resizing
    # (neither is alpha-premultiplied) don't bleed black into its edge.
    layer = Image.new("RGBA", (-(-width // scale), -(-height // scale)), rgba[:3] + (0,))
    x, y = layout.art_position
    box = [
        ((x - spread) / scale, (y - spread + offset_y) / scale),
        ((x + layout.art_size + spread) / scale, (y + layout.art_size + spread + offset_y) / scale),
    ]
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=(corner_radius + spread) / scale, fill=rgba)
    layer = layer.filter(ImageFilter.GaussianBlur(blur / scale))
    return layer if scale == 1 else layer.resize(layout.canvas_size, Image.BICUBIC)


def _shadow_layer(layout: ArtLayout, settings: Settings) -> Image.Image:
    pad = settings.shadow_blur_radius
    return _halo_layer(
        layout, (0, 0, 0, _SHADOW_ALPHA), pad, pad // 2, _SHADOW_OFFSET_Y, settings.corner_radius
    )


def _glow_layer(
    layout: ArtLayout, settings: Settings, palette: List[Tuple[int, int, int]], background: Tuple[int, int, int]
) -> Image.Image:
    """The art as a light source: its most vivid color, spread wider than a shadow and centered."""
    color = pick_glow_color(palette, background)
    blur = max(settings.shadow_blur_radius * 2, int(layout.art_size * _GLOW_BLUR_PCT))
    spread = int(layout.art_size * _GLOW_SPREAD_PCT)
    return _halo_layer(layout, color + (_GLOW_ALPHA,), blur, spread, 0, settings.corner_radius)


def _blurred_art_background(art: Image.Image, size: Tuple[int, int], strength: int) -> Image.Image:
    """The art covering the canvas (center-cropped), blurred and darkened toward the edges.

    Blurred on a quarter-size copy: it looks the same once upscaled (the blur
    removed the detail a bigger copy would keep) at a fraction of the cost."""
    width, height = size
    small_w = max(1, width // _BLUR_BG_DOWNSCALE)
    small_h = max(1, height // _BLUR_BG_DOWNSCALE)
    scale = max(small_w / art.width, small_h / art.height)
    cover_w, cover_h = max(small_w, round(art.width * scale)), max(small_h, round(art.height * scale))
    left, top = (cover_w - small_w) // 2, (cover_h - small_h) // 2
    small = art.resize((cover_w, cover_h), Image.LANCZOS).crop((left, top, left + small_w, top + small_h))
    sigma = min(width, height) * strength * _BLUR_BG_SIGMA_PER_STRENGTH / _BLUR_BG_DOWNSCALE
    background = small.filter(ImageFilter.GaussianBlur(sigma)).resize(size, Image.BICUBIC).convert("RGBA")

    # radial_gradient is 0 at the centre and 255 at its rim; stretched to the
    # canvas it follows the screen's shape.
    darkness = Image.radial_gradient("L").resize(size, Image.BILINEAR)
    darkness = darkness.point(lambda v: min(255, int(_VIGNETTE_MIN_ALPHA + v * _VIGNETTE_GAIN)))
    shade = Image.new("RGBA", size, (0, 0, 0, 0))
    shade.putalpha(darkness)
    return Image.alpha_composite(background, shade).convert("RGB")


def _framed_art(layout: ArtLayout, frame: str) -> ArtLayout:
    """The art shrunk so that it plus its outer glass rim fill the art's original box."""
    outer_gap = _FRAME_RIMS[frame][0][0]
    inner = round(layout.art_size / (1 + 2 * outer_gap))
    offset = (layout.art_size - inner) // 2
    x, y = layout.art_position
    return ArtLayout(canvas_size=layout.canvas_size, art_size=inner, art_position=(x + offset, y + offset))


def _glass_frame_layer(art: ArtLayout, settings: Settings) -> Image.Image:
    """The frame's rims around [art]: a light veil and a hairline edge each, an
    outer one fainter. The background shows through, blurred or not."""
    layer = Image.new("RGBA", art.canvas_size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    edge_width = max(1, round(art.canvas_size[1] / 540))
    x, y = art.art_position
    for gap_pct, veil, edge in _FRAME_RIMS[settings.art_frame]:
        gap = round(art.art_size * gap_pct)
        box = [x - gap, y - gap, x + art.art_size + gap - 1, y + art.art_size + gap - 1]
        draw.rounded_rectangle(
            box,
            radius=settings.corner_radius + gap,
            fill=(255, 255, 255, veil),
            outline=(255, 255, 255, edge),
            width=edge_width,
        )
    return layer


def _build_base_canvas(art_bytes: bytes, settings: Settings, layout: ArtLayout) -> Image.Image:
    """Background fill + shadow + centered rounded art. No track text — this is the
    part that's identical for every track on the same album, so it's safe to cache."""
    dominant_rgb = extract_dominant_color(art_bytes)
    use_mesh = settings.background_style == "mesh"
    # One extraction shared by every effect that needs accents; none without them.
    palette = extract_accent_palette(art_bytes) if (use_mesh or settings.art_glow) else []
    art_image = Image.open(BytesIO(art_bytes)).convert("RGB")

    if use_mesh:
        background = mesh_background(layout.canvas_size, pick_mesh_colors(dominant_rgb, palette))
    elif settings.background_style == "blur":
        background = _blurred_art_background(art_image, layout.canvas_size, settings.blur_strength)
    else:
        background = Image.new("RGB", layout.canvas_size, dominant_rgb)
    canvas = background.convert("RGBA")

    if settings.art_glow:
        halo = _glow_layer(layout, settings, palette, dominant_rgb)
    else:
        halo = _shadow_layer(layout, settings)
    canvas = Image.alpha_composite(canvas, halo)

    # The shadow or glow keeps the art's original box: with a frame, that is
    # the outer rim, and the art itself sits inside it.
    art_box = layout
    if settings.art_frame in _FRAME_RIMS:
        art_box = _framed_art(layout, settings.art_frame)
        canvas = Image.alpha_composite(canvas, _glass_frame_layer(art_box, settings))

    art = art_image.resize((art_box.art_size, art_box.art_size), Image.LANCZOS)
    mask = _rounded_mask(art_box.art_size, settings.corner_radius)
    canvas.paste(art, art_box.art_position, mask)

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


@dataclass(frozen=True)
class _TextLine:
    text: str
    font: ImageFont.ImageFont
    position: Tuple[int, int]


def _layout_track_info(
    draw: ImageDraw.ImageDraw, layout: ArtLayout, track_name: Optional[str], artist_name: Optional[str]
) -> List[_TextLine]:
    """Title and artist, truncated and centered below the art; [] if there's no text."""
    canvas_width, canvas_height = layout.canvas_size
    max_width = int(canvas_width * _TEXT_MAX_WIDTH_PCT)
    center_x = layout.art_position[0] + layout.art_size // 2

    title_size, artist_size = _font_sizes(canvas_height)
    y = layout.art_position[1] + layout.art_size + int(canvas_height * 0.035)

    lines = []
    if track_name:
        font = _load_font(title_size, bold=True)
        text = _truncate_to_width(draw, track_name, font, max_width)
        bbox = draw.textbbox((0, 0), text, font=font)
        lines.append(_TextLine(text, font, (center_x - (bbox[2] - bbox[0]) // 2, y)))
        y += (bbox[3] - bbox[1]) + int(canvas_height * 0.012)

    if artist_name:
        font = _load_font(artist_size, bold=False)
        text = _truncate_to_width(draw, artist_name, font, max_width)
        bbox = draw.textbbox((0, 0), text, font=font)
        lines.append(_TextLine(text, font, (center_x - (bbox[2] - bbox[0]) // 2, y)))
    return lines


def _ink_bounds(draw: ImageDraw.ImageDraw, lines: List[_TextLine]) -> Tuple[int, int, int, int]:
    """The box the glyphs actually cover (textbbox includes each font's top offset)."""
    boxes = [draw.textbbox(line.position, line.text, font=line.font) for line in lines]
    return (
        min(b[0] for b in boxes),
        min(b[1] for b in boxes),
        max(b[2] for b in boxes),
        max(b[3] for b in boxes),
    )


def _card_box(ink: Tuple[int, int, int, int], canvas_size: Tuple[int, int]) -> Tuple[int, int, int, int]:
    """The glass card: the text's ink box plus padding, kept on the canvas."""
    width, height = canvas_size
    pad_x = max(12, int(height * _CARD_PAD_X_PCT))
    pad_y = max(8, int(height * _CARD_PAD_Y_PCT))
    return (
        max(0, ink[0] - pad_x),
        max(0, ink[1] - pad_y),
        min(width, ink[2] + pad_x),
        min(height, ink[3] + pad_y),
    )


def _draw_track_info(
    canvas: Image.Image,
    layout: ArtLayout,
    text_card: str,
    track_name: Optional[str],
    artist_name: Optional[str],
) -> None:
    draw = ImageDraw.Draw(canvas)
    lines = _layout_track_info(draw, layout, track_name, artist_name)
    if not lines:
        return

    if text_card == "glass":
        canvas_height = layout.canvas_size[1]
        card = _card_box(_ink_bounds(draw, lines), layout.canvas_size)
        frost(
            canvas,
            card,
            blur=max(6, int(canvas_height * _CARD_BLUR_PCT)),
            radius=max(6, int(canvas_height * _CARD_RADIUS_PCT)),
        )
        behind_text = card
    else:
        behind_text = _text_band(layout)
    # Sampled from what is actually behind the text: a gradient background
    # has no single color, and a card changes what the text sits on.
    color = _text_color_for_background(_average_color(canvas, behind_text))

    for line in lines:
        draw.text(line.position, line.text, font=line.font, fill=color)


def render_for_now_playing(
    now_playing: NowPlaying, settings: Settings, layout: ArtLayout, base_path: Path, output_path: Path
) -> None:
    if base_path.exists():
        base_image = Image.open(base_path).convert("RGB")
        mark_used(base_path)
        logger.info("Reusing cached base art for album %s", now_playing.album_id)
    else:
        art_bytes = download_art(now_playing.art_url)
        base_image = _build_base_canvas(art_bytes, settings, layout)
        # Fast compression: written once per album, and the cache is capped by size.
        base_image.save(base_path, format="PNG", compress_level=1)
        prune_album_bases(base_path.parent, keep=base_path)
        logger.info("Rendered new base art for album %s", now_playing.album_id)

    final_image = base_image.copy()
    if settings.show_track_info:
        _draw_track_info(final_image, layout, settings.text_card, now_playing.track_name, now_playing.artist_name)

    # Rewritten on every track change, to one of two alternating files, so speed
    # beats size: at level 6 a dithered mesh takes ~0.2 s (1080p) / ~0.7 s (4K)
    # to encode, at level 1 well under half. The cached base stays at the default.
    final_image.save(output_path, format="PNG", compress_level=1)
    logger.info("Rendered wallpaper to %s", output_path)
