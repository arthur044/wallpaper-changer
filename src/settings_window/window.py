from typing import Callable

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import (
    QButtonGroup,
    QCheckBox,
    QComboBox,
    QGroupBox,
    QHBoxLayout,
    QPushButton,
    QRadioButton,
    QVBoxLayout,
    QWidget,
)

from src.config.settings import Settings
from src.settings_window.commands import AppCommands, LyricsWidgetControls
from src.settings_window.style_actions import StyleActions

_BACKGROUNDS = (("solid", "Solid color"), ("mesh", "Mesh gradient"), ("blur", "Blurred art"))
_FRAMES = (("none", "No frame"), ("single", "Single glass frame"), ("double", "Double glass frame"))
_REFRESH_MS = 500  # the tray, the lyrics widget and the updater change things too
_BLUR_PRESETS = tuple(value for _name, value in StyleActions.BLUR_LEVELS)


class SettingsWindow(QWidget):
    """The app's settings, in a window that stays open while they change (a
    tray menu closes after every click). Qt thread only. Controls write through
    StyleActions, the lyrics widget and AppCommands, and refresh() reads the
    live state back, so a change made anywhere else shows up here."""

    def __init__(
        self,
        settings: Settings,
        style: StyleActions,
        lyrics: LyricsWidgetControls,
        commands: AppCommands,
    ) -> None:
        super().__init__()
        self._settings = settings
        self._style = style
        self._lyrics = lyrics
        self._commands = commands
        self.setWindowTitle("Spotify Wallpaper Engine")
        layout = QVBoxLayout(self)
        layout.addLayout(self._build_playback_row())
        layout.addWidget(self._build_style_box())
        layout.addWidget(self._build_lock_screen_box())
        layout.addWidget(self._build_lyrics_box())
        layout.addWidget(self._build_app_box())
        layout.addStretch(1)
        self._timer = QTimer(self)
        self._timer.setInterval(_REFRESH_MS)
        self._timer.timeout.connect(self.refresh)
        self.refresh()

    # --- building -------------------------------------------------------------

    def _build_playback_row(self) -> QHBoxLayout:
        row = QHBoxLayout()
        self._pause = self._button(row, "Pause", self._commands.toggle_pause)
        self._button(row, "Sync now", self._commands.force_sync)
        row.addStretch(1)
        return row

    def _build_style_box(self) -> QGroupBox:
        box = QGroupBox("Wallpaper style")
        layout = QVBoxLayout(box)
        self._backgrounds = self._radio_group(box, layout, _BACKGROUNDS, self._style.set_background)
        self._blur = QComboBox()
        for name, value in StyleActions.BLUR_LEVELS:
            self._blur.addItem(f"Blur: {name}", value)
        self._blur.activated.connect(lambda index: self._style.set_blur_strength(self._blur.itemData(index)))
        layout.addWidget(self._blur)
        self._glow = self._check(layout, "Art glow", self._style.set_glow)
        self._frames = self._radio_group(box, layout, _FRAMES, self._style.set_frame)
        self._glass = self._check(layout, "Glass card behind the text", self._style.set_glass_card)
        self._smooth = self._check(layout, "Smooth transition", self._style.set_smooth_transition)
        return box

    def _build_lock_screen_box(self) -> QGroupBox:
        box = QGroupBox("Lock screen")
        layout = QVBoxLayout(box)
        # Turning it on or off asks Windows for permission (UAC) and can be
        # declined, so the box shows what happened, not what was clicked.
        self._lock_sync = self._toggle(layout, "Sync the lock screen with the wallpaper", self._commands.toggle_lock_sync)
        return box

    def _build_lyrics_box(self) -> QGroupBox:
        box = QGroupBox("Lyrics widget")
        layout = QVBoxLayout(box)
        self._lyrics_show = self._toggle(layout, "Show", self._lyrics.toggle_widget)
        self._lyrics_locked = self._toggle(layout, "Lock position (clicks pass through)", self._lyrics.toggle_locked)
        self._lyrics_on_top = self._toggle(layout, "Always on top", self._lyrics.toggle_on_top)
        row = QHBoxLayout()
        self._button(row, "Reset position", self._lyrics.reset_position)
        row.addStretch(1)
        layout.addLayout(row)
        return box

    def _build_app_box(self) -> QGroupBox:
        box = QGroupBox(f"App {self._commands.version}".strip())
        layout = QVBoxLayout(box)
        self._update = self._button(layout, "Check for updates", self._commands.update_click)
        self._reauth = self._button(layout, "Re-authenticate with Spotify", self._commands.reauthenticate)
        self._button(layout, "Setup...", self._commands.setup)
        self._restart = self._button(layout, "Restart", self._commands.restart)
        self._exit = self._button(layout, "Exit", self._commands.exit)
        return box

    def _button(self, layout, label: str, action: Callable[[], None]) -> QPushButton:
        button = QPushButton(label)
        button.clicked.connect(lambda _checked=False: self._run(action))
        layout.addWidget(button)
        return button

    def _toggle(self, layout, label: str, action: Callable[[], None]) -> QCheckBox:
        box = QCheckBox(label)
        box.clicked.connect(lambda _checked: self._run(action))
        layout.addWidget(box)
        return box

    def _run(self, action: Callable[[], None]) -> None:
        action()
        self.refresh()

    @staticmethod
    def _radio_group(parent, layout, choices, on_pick) -> dict:
        group = QButtonGroup(parent)
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

    # --- showing --------------------------------------------------------------

    def showEvent(self, event) -> None:  # noqa: N802 - Qt override
        self.refresh()
        self._timer.start()
        super().showEvent(event)

    def hideEvent(self, event) -> None:  # noqa: N802 - Qt override
        self._timer.stop()
        super().hideEvent(event)

    def refresh(self) -> None:
        """Shows the current state; changes made elsewhere land here too."""
        s, c = self._settings, self._commands
        self._pause.setText("Resume" if c.is_paused() else "Pause")
        self._backgrounds[s.background_style].setChecked(True)
        self._frames[s.art_frame].setChecked(True)
        self._glow.setChecked(s.art_glow)
        self._glass.setChecked(s.text_card == "glass")
        self._smooth.setChecked(s.smooth_transition)
        self._show_blur(s.blur_strength)
        self._lock_sync.setChecked(s.sync_lock_screen)
        self._lock_sync.setEnabled(not c.lock_sync_busy())
        self._lyrics_show.setChecked(self._lyrics.is_widget_visible())
        self._lyrics_locked.setChecked(self._lyrics.is_locked())
        self._lyrics_on_top.setChecked(self._lyrics.is_on_top())
        self._update.setVisible(c.has_updater())
        self._update.setText(c.update_label())
        self._reauth.setVisible(c.needs_reauthentication())
        idle = not c.updating()  # a restart or exit would cut an update in half
        self._restart.setEnabled(idle)
        self._exit.setEnabled(idle)

    def _show_blur(self, strength: int) -> None:
        index = self._blur.findData(strength)
        if index < 0:  # a value typed into config.json: keep it selectable
            if self._blur.itemData(self._blur.count() - 1) not in _BLUR_PRESETS:
                self._blur.removeItem(self._blur.count() - 1)
            self._blur.addItem(f"Blur: Custom ({strength})", strength)
            index = self._blur.count() - 1
        self._blur.setCurrentIndex(index)
