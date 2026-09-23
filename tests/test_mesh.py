from PIL import ImageStat

from src.graphics.mesh import mesh_background, mix_oklab


def _distance(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def _luminance(rgb):
    r, g, b = rgb
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def test_mix_oklab_keeps_the_ends():
    assert mix_oklab((255, 0, 0), (0, 0, 255), 0.0) == (255, 0, 0)
    assert mix_oklab((255, 0, 0), (0, 0, 255), 1.0) == (0, 0, 255)


def test_mix_oklab_midpoint_is_less_muddy_than_rgb():
    # RGB averaging of blue and yellow lands on a dull gray; OKLab keeps it lighter.
    blue, yellow = (0, 0, 255), (255, 255, 0)
    rgb_mid = tuple((a + b) // 2 for a, b in zip(blue, yellow))

    assert _luminance(mix_oklab(blue, yellow, 0.5)) > _luminance(rgb_mid) + 15


def test_mesh_background_fills_the_canvas_with_a_varying_gradient():
    colors = [(20, 30, 80), (220, 120, 40), (200, 30, 80), (40, 180, 160)]

    image = mesh_background((320, 180), colors)

    assert image.size == (320, 180)
    assert image.mode == "RGB"
    corners = [image.getpixel(p) for p in [(0, 0), (319, 0), (0, 179), (319, 179)]]
    assert max(_distance(a, b) for a in corners for b in corners) > 60


def test_mesh_background_is_deterministic():
    colors = [(20, 30, 80), (220, 120, 40), (40, 180, 160)]

    assert mesh_background((160, 90), colors).tobytes() == mesh_background((160, 90), colors).tobytes()


def test_mesh_background_with_one_color_is_flat():
    image = mesh_background((64, 36), [(90, 20, 40)])

    assert image.getcolors() == [(64 * 36, (90, 20, 40))]


def _longest_flat_run(values):
    longest = run = 1
    for a, b in zip(values, values[1:]):
        run = run + 1 if a == b else 1
        longest = max(longest, run)
    return longest


def test_dark_mesh_has_no_flat_bands():
    # A near-black cover spans only a few 8-bit levels across the screen, the
    # worst case for banding: rounded without dithering, each level is a flat
    # plateau hundreds of pixels wide. The eye averages over a few pixels, so
    # judge 8x8 block means along a band: dithered, they keep following the ramp.
    image = mesh_background((1920, 1080), [(8, 8, 8), (52, 52, 52), (87, 87, 87)])

    block_means = [
        round(ImageStat.Stat(image.crop((x, 200, x + 8, 208))).mean[0], 3) for x in range(0, 1920, 8)
    ]

    assert _longest_flat_run(block_means) < 8, "8x8 averages stuck on one value: a visible band"


def test_dithering_does_not_shift_the_average_color():
    image = mesh_background((256, 144), [(40, 40, 40), (40, 40, 40)])

    mean = ImageStat.Stat(image).mean
    assert all(abs(m - 40) < 0.5 for m in mean)
    lo, hi = image.getextrema()[0]
    assert 39 <= lo and hi <= 41
