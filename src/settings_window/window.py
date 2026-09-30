from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QButtonGroup,
    QCheckBox,
    QComboBox,
    QGroupBox,
    QRadioButton,
    QVBoxLayout,
    QWidget,
)

from src.config.settings import Settings
from src.settings_window.style_actions import StyleActions

_BACKGROUNDS = (("solid", "Solid color"), ("mesh", "Mesh gradient"), ("blur", "Blurred art"))
_FRAMES = (("none", "No frame"), ("single", "Single glass frame"), ("double", "Double glass frame"))


class SettingsWindow(QWidget):
    """The app's settings, in a window that stays open while they change (a
    tray menu closes after every click). Qt thread only. It writes through
    StyleActions and reads the live Settings back in refresh()."""

    def __init__(self, settings: Settings, style: StyleActions) -> None:
        super().__init__()
        self._settings = settings
        self._style = style
        self.setWindowTitle("Spotify Wallpaper Engine")
        layout = QVBoxLayout(self)
        layout.addWidget(self._build_style_box())
        layout.addStretch(1)
        self.refresh()

    def _build_style_box(self) -> QGroupBox:
        box = QGroupBox("Wallpaper style")
        layout = QVBoxLayout(box)

        self._backgrounds = self._radio_group(layout, _BACKGROUNDS, self._style.set_background)
        self._blur = QComboBox()
        for name, value in StyleActions.BLUR_LEVELS:
            self._blur.addItem(f"Blur: {name}", value)
        self._blur.activated.connect(lambda index: self._style.set_blur_strength(self._blur.itemData(index)))
        layout.addWidget(self._blur)

        self._glow = self._check(layout, "Art glow", self._style.set_glow)
        self._frames = self._radio_group(layout, _FRAMES, self._style.set_frame)
        self._glass = self._check(layout, "Glass card behind the text", self._style.set_glass_card)
        self._smooth = self._check(layout, "Smooth transition", self._style.set_smooth_transition)
        return box

    @staticmethod
    def _radio_group(layout, choices, on_pick) -> dict:
        group = QButtonGroup(layout.parentWidget())
        buttons = {}
        for value, label in choices:
            button = QRadioButton(label)
            group.addButton(button)
            layout.addWidget(button)
            # clicked, not toggled: a refresh() must not read as a user pick.
            button.clicked.connect(lambda _checked=False, v=value: on_pick(v))
            buttons[value] = button
        return buttons

    @staticmethod
    def _check(layout, label, on_change) -> QCheckBox:
        box = QCheckBox(label)
        box.clicked.connect(lambda checked: on_change(bool(checked)))
        layout.addWidget(box)
        return box

    def refresh(self) -> None:
        """Shows the current settings; changes made elsewhere land here too."""
        s = self._settings
        self._backgrounds[s.background_style].setChecked(True)
        self._frames[s.art_frame].setChecked(True)
        self._glow.setChecked(s.art_glow)
        self._glass.setChecked(s.text_card == "glass")
        self._smooth.setChecked(s.smooth_transition)
        self._show_blur(s.blur_strength)

    def _show_blur(self, strength: int) -> None:
        index = self._blur.findData(strength)
        if index < 0:  # a value typed into config.json: keep it selectable
            if self._blur.itemData(self._blur.count() - 1) not in (10, 26, 50, 100):
                self._blur.removeItem(self._blur.count() - 1)
            self._blur.addItem(f"Blur: Custom ({strength})", strength)
            index = self._blur.count() - 1
        self._blur.setCurrentIndex(index)
