from typing import Callable

from src.config.settings import ART_FRAMES, BACKGROUND_STYLES, Settings


class StyleActions:
    """The look of the wallpaper as the user changes it, shared by the settings
    window and the tray menu. The render reads this same Settings object and
    the base cache key includes the style, so saving and forcing a redraw is
    all it takes to show a change."""

    # Presets for the blurred background; any 0-100 value works from config.json.
    BLUR_LEVELS = (("Soft", 10), ("Medium (default)", 26), ("Strong", 50), ("Maximum", 100))

    def __init__(self, settings: Settings, save: Callable[[Settings], None], redraw: Callable[[], None]) -> None:
        self._settings = settings
        self._save = save
        self._redraw = redraw

    def set_background(self, style: str) -> None:
        if style in BACKGROUND_STYLES and self._settings.background_style != style:
            self._settings.background_style = style
            self._apply()

    def set_glow(self, on: bool) -> None:
        if self._settings.art_glow != on:
            self._settings.art_glow = on
            self._apply()

    def set_blur_strength(self, value: int) -> None:
        if self._settings.blur_strength != value:
            self._settings.blur_strength = value
            self._apply()

    def set_frame(self, frame: str) -> None:
        if frame in ART_FRAMES and self._settings.art_frame != frame:
            self._settings.art_frame = frame
            self._apply()

    def set_glass_card(self, on: bool) -> None:
        card = "glass" if on else "none"
        if self._settings.text_card != card:
            self._settings.text_card = card
            self._apply()

    def set_smooth_transition(self, on: bool) -> None:
        if self._settings.smooth_transition != on:
            self._settings.smooth_transition = on
            self._apply()

    def _apply(self) -> None:
        self._save(self._settings)
        self._redraw()
