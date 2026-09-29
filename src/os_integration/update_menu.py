import logging
import threading
from typing import Callable, Optional

from src.os_integration.updater import Action, Decision, Updater

logger = logging.getLogger(__name__)

_IDLE_LABEL = "Check for updates"
_CHECKING_LABEL = "Checking for updates..."


def _spawn_thread(fn: Callable[[], None]) -> None:
    threading.Thread(target=fn, daemon=True, name="updater").start()


def _update_label(decision: Decision) -> str:
    noun = "commit" if decision.commits == 1 else "commits"
    return f"Update to {decision.target} ({decision.commits} {noun})"


class UpdateMenu:
    """The tray's update item. First click checks; when that finds desktop
    changes the item turns into "Update to ...", and the next click applies
    them and restarts. Nothing is ever applied without a click.

    Failures show on the item's own label, not as the app's ERROR status:
    that one means the Spotify session and brings up Re-authenticate."""

    def __init__(
        self,
        updater: Updater,
        on_restart: Callable[[], None],
        refresh: Callable[[], None],
        spawn: Callable[[Callable[[], None]], None] = _spawn_thread,
    ):
        self._updater = updater
        self._on_restart = on_restart
        self._refresh = refresh
        self._spawn = spawn  # git and pip take seconds: off the tray thread
        self._lock = threading.Lock()
        self._label = _IDLE_LABEL
        self._pending: Optional[Decision] = None
        self._busy = False
        self._applying = False

    def label(self) -> str:
        with self._lock:
            return self._label

    def is_applying(self) -> bool:
        """True while pip or the fast-forward run. Restart or Exit then would
        start half-updated code, or kill the update midway."""
        with self._lock:
            return self._applying

    def click(self) -> None:
        with self._lock:
            if self._busy:
                return
            apply = self._pending is not None
            self._busy = True
            self._applying = apply
            self._label = "Updating..." if apply else _CHECKING_LABEL
        self._refresh()
        self._spawn(self._apply if apply else self._check_and_pull_docs)

    def check_silently(self) -> None:
        """Startup check: may turn the item into "Update to ...", nothing else."""
        with self._lock:
            if self._busy:
                return
            self._busy = True
            self._label = _CHECKING_LABEL  # a click meanwhile is ignored: say why
        self._refresh()
        self._spawn(self._silent_check)

    def _finish(self, label: str, pending: Optional[Decision] = None) -> None:
        with self._lock:
            self._label = label
            self._pending = pending
            self._busy = False
            self._applying = False
        self._refresh()

    def _fail(self, reason: str) -> None:
        message = f"Update failed: {reason}"
        logger.warning(message)
        self._finish(message)

    def _silent_check(self) -> None:
        decision = self._updater.check()
        if decision.action == Action.UPDATE:
            self._finish(_update_label(decision), pending=decision)
        elif decision.action == Action.UP_TO_DATE:
            self._finish(f"Up to date ({decision.target})")
        else:  # errors and docs-only wait for a click
            self._finish(_IDLE_LABEL)

    def _check_and_pull_docs(self) -> None:
        decision = self._updater.check()
        if decision.action == Action.UP_TO_DATE:
            self._finish(f"Up to date ({decision.target})")
        elif decision.action == Action.UPDATE:
            self._finish(_update_label(decision), pending=decision)
        elif decision.action == Action.DOCS_ONLY:
            with self._lock:
                self._applying = True
            self._apply()
        else:
            self._fail(decision.reason)

    def _apply(self) -> None:
        result = self._updater.apply()
        if result.error:
            self._fail(result.error)
            return
        if result.restart:
            # Still "applying": Restart and Exit stay disabled until this
            # restart takes the tray down.
            with self._lock:
                self._label = "Restarting..."
            self._refresh()
            self._on_restart()
            return
        if result.decision.action == Action.DOCS_ONLY:
            self._finish(f"Up to date ({result.decision.target}), no desktop changes")
        else:
            self._finish(f"Up to date ({result.decision.target})")
