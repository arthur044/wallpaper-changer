"""Mesh gradient background: a few of the art's colors as soft blobs, blended
in OKLab so neighbouring hues meet in a clean mid-tone instead of RGB's gray.

Computed on a small grid and upscaled: the result is smooth by construction,
so a full-screen canvas costs about as much as a thumbnail. The grid stays in
floating point through the upscale and is rounded once, at full size, with an
ordered dither: rounding the grid first turns a dark gradient into blocky
plateaus a hundred pixels wide, and random-noise dither costs 10x more and
bloats the PNG.
"""
import math
from typing import List, Sequence, Tuple

from PIL import Image, ImageMath

RGB = Tuple[int, int, int]
Lab = Tuple[float, float, float]

_GRID_WIDTH = 64
# Blob centers for colors[1:], as fractions of the canvas. The dominant color
# (colors[0]) is the base everywhere, so the art sits on it.
_ANCHORS = ((0.12, 0.18), (0.88, 0.22), (0.80, 0.90), (0.18, 0.86))
_BLOB_SIGMA = 0.28
_BASE_WEIGHT = 0.25

_BAYER_8 = (
    (0, 32, 8, 40, 2, 34, 10, 42),
    (48, 16, 56, 24, 50, 18, 58, 26),
    (12, 44, 4, 36, 14, 46, 6, 38),
    (60, 28, 52, 20, 62, 30, 54, 22),
    (3, 35, 11, 43, 1, 33, 9, 41),
    (51, 19, 59, 27, 49, 17, 57, 25),
    (15, 47, 7, 39, 13, 45, 5, 37),
    (63, 31, 55, 23, 61, 29, 53, 21),
)


def _to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _to_srgb_float(c: float) -> float:
    """Linear light -> sRGB on the 0..255 scale, unrounded."""
    c = 12.92 * c if c <= 0.0031308 else 1.055 * (max(c, 0.0) ** (1 / 2.4)) - 0.055
    return min(255.0, max(0.0, c * 255))


def _to_srgb(c: float) -> int:
    return round(_to_srgb_float(c))


def rgb_to_oklab(rgb: RGB) -> Lab:
    r, g, b = (_to_linear(c / 255) for c in rgb)
    l = math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    m = math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    s = math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return (
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )


def _oklab_to_linear(lab: Lab) -> Tuple[float, float, float]:
    big_l, a, b = lab
    l = (big_l + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (big_l - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (big_l - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def oklab_to_rgb(lab: Lab) -> RGB:
    r, g, b = _oklab_to_linear(lab)
    return (_to_srgb(r), _to_srgb(g), _to_srgb(b))


def mix_oklab(a: RGB, b: RGB, t: float) -> RGB:
    if t <= 0:
        return a
    if t >= 1:
        return b
    la, lb = rgb_to_oklab(a), rgb_to_oklab(b)
    return oklab_to_rgb(tuple(x + (y - x) * t for x, y in zip(la, lb)))


def mesh_background(size: Tuple[int, int], colors: Sequence[RGB]) -> Image.Image:
    """colors[0] is the base; each further color (up to 4 more) is a blob."""
    width, height = size
    if len(colors) < 2:
        return Image.new("RGB", size, colors[0])

    grid_size = (_GRID_WIDTH, max(2, round(_GRID_WIDTH * height / width)))
    threshold = _bayer_thresholds(size)
    channels = []
    for grid in _grid_channels(grid_size, colors):
        smooth = grid.resize(size, Image.BICUBIC)
        # F -> L truncates, so floor(value + threshold) is an ordered dither
        # whose thresholds average 0.5: rounding, spread over the 8x8 pattern.
        dithered = ImageMath.lambda_eval(lambda a: a["c"] + a["t"], c=smooth, t=threshold)
        channels.append(dithered.convert("L"))
    return Image.merge("RGB", channels)


def _grid_channels(grid_size: Tuple[int, int], colors: Sequence[RGB]) -> List[Image.Image]:
    """The blended mesh at grid resolution, one float ("F") image per sRGB channel."""
    grid_w, grid_h = grid_size
    labs = [rgb_to_oklab(c) for c in colors]
    blobs = list(zip(_ANCHORS, labs[1:]))
    two_sigma_sq = 2 * _BLOB_SIGMA**2

    values: Tuple[List[float], List[float], List[float]] = ([], [], [])
    for gy in range(grid_h):
        y = gy / (grid_h - 1)
        for gx in range(grid_w):
            x = gx / (grid_w - 1)
            total = _BASE_WEIGHT
            acc = [_BASE_WEIGHT * v for v in labs[0]]
            for (ax, ay), lab in blobs:
                w = math.exp(-((x - ax) ** 2 + (y - ay) ** 2) / two_sigma_sq)
                total += w
                for i in range(3):
                    acc[i] += w * lab[i]
            linear = _oklab_to_linear((acc[0] / total, acc[1] / total, acc[2] / total))
            for channel, c in zip(values, linear):
                channel.append(_to_srgb_float(c))

    images = []
    for channel in values:
        image = Image.new("F", grid_size)
        image.putdata(channel)
        images.append(image)
    return images


def _bayer_thresholds(size: Tuple[int, int]) -> Image.Image:
    """The 8x8 Bayer matrix as thresholds in (0, 1), tiled over `size`."""
    tile = Image.new("F", (8, 8))
    tile.putdata([(v + 0.5) / 64 for row in _BAYER_8 for v in row])
    # Doubling by pasting takes log2(side / 8) rounds instead of one paste per tile.
    while tile.width < size[0] or tile.height < size[1]:
        bigger = Image.new("F", (tile.width * 2, tile.height * 2))
        for dx in (0, tile.width):
            for dy in (0, tile.height):
                bigger.paste(tile, (dx, dy))
        tile = bigger
    return tile.crop((0, 0) + size)
