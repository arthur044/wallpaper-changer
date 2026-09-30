from dataclasses import dataclass
from typing import Callable, Protocol


class LyricsWidgetControls(Protocol):
    """What the settings window can do to the lyrics widget (QtHost)."""

    def toggle_widget(self) -> None: ...

    def is_widget_visible(self) -> bool: ...

    def toggle_locked(self) -> None: ...

    def is_locked(self) -> bool: ...

    def toggle_on_top(self) -> None: ...

    def is_on_top(self) -> bool: ...

    def reset_position(self) -> None: ...


@dataclass(frozen=True)
class AppCommands:
    """The app-level actions the settings window offers, owned by the tray
    (they need its icon and its callbacks). Every one is safe to call from the
    Qt thread: the slow ones (UAC, git, the wizard, the poller's shutdown) run
    on their own threads and return at once."""

    version: str
    is_paused: Callable[[], bool]
    toggle_pause: Callable[[], None]
    force_sync: Callable[[], None]
    lock_sync_busy: Callable[[], bool]
    toggle_lock_sync: Callable[[], None]
    has_updater: Callable[[], bool]
    update_label: Callable[[], str]
    update_click: Callable[[], None]
    updating: Callable[[], bool]
    needs_reauthentication: Callable[[], bool]
    reauthenticate: Callable[[], None]
    setup: Callable[[], None]
    restart: Callable[[], None]
    exit: Callable[[], None]
