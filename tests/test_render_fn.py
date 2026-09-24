import io

from PIL import Image

import main
from src.config.settings import Settings
from src.graphics import renderer
from src.graphics.base_cache import base_cache_key
from src.graphics.layout import ArtLayout
from src.spotify.client import NowPlaying

_LAYOUT = ArtLayout(canvas_size=(400, 300), art_size=100, art_position=(150, 100))


def _multicolor_png() -> bytes:
    art = Image.new("RGB", (64, 64), (20, 40, 110))
    art.paste((240, 150, 30), (0, 0, 32, 32))
    art.paste((30, 190, 150), (32, 32, 64, 64))
    buffer = io.BytesIO()
    art.save(buffer, format="PNG")
    return buffer.getvalue()


def test_a_tray_change_during_a_render_cannot_poison_the_base_cache(monkeypatch, tmp_path):
    # The tray edits the same Settings object from its own thread. A style
    # change while a new album downloads must not end up saved under the key
    # of the style the render started with.
    settings = Settings(show_track_info=False)  # solid
    monkeypatch.setattr(main, "compute_layout", lambda s: _LAYOUT)
    monkeypatch.setattr(main, "album_base_path", lambda key: tmp_path / f"{key}.png")
    monkeypatch.setattr(main, "next_output_path", lambda: tmp_path / "wallpaper_a.png")
    monkeypatch.setattr(main, "set_wallpaper", lambda path, smooth=False: None)
    art = _multicolor_png()

    def download_while_the_tray_switches_to_mesh(url):
        settings.background_style = "mesh"
        return art

    monkeypatch.setattr(renderer, "download_art", download_while_the_tray_switches_to_mesh)

    main._make_render_fn(settings)(NowPlaying(True, "t1", "a1", "http://x/a1.jpg", "Track", "Artist"))

    solid_key = base_cache_key("a1", _LAYOUT.canvas_size, Settings(show_track_info=False))
    base = Image.open(tmp_path / f"{solid_key}.png").convert("RGB")
    corners = {base.getpixel(p) for p in [(0, 0), (399, 0), (0, 299), (399, 299)]}
    assert len(corners) == 1, f"the base under the solid key must be drawn solid, got corners {corners}"
