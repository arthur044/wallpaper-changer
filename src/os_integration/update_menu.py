import logging
import threading
from typing import Callable, Optional

from src.os_integration.updater import Action, Decision, Updater
from src.utils.app_state import AppState

logger = logging.getLogger(__name__)

_IDLE_LABEL = "Check for updates"


def _spawn_thread(fn: Callable[[], None]) -> None:
    threading.Thread(target=fn, daemon=True, name="updater").start()


def _update_label(decision: Decision) -> str:
    noun = "commit" if decision.commits == 1 else "commits"
    return f"Update to {decision.target} ({decision.commits} {noun})"


class UpdateMenu:
    """The tray's update item. First click checks; when that finds desktop
    changes the item turns into "Update to ...", and the next click applies
    them and restarts. Nothing is ever applied without a click."""

    def __init__(
        self,
        updater: Updater,
        app_state: AppState,
        on_restart: Callable[[], None],
        refresh: Callable[[], None],
        spawn: Callable[[Callable[[], None]], None] = _spawn_thread,
    ):
        self._updater = updater
        self._app_state = app_state
        self._on_restart = on_restart
        self._refresh = refresh
        self._spawn = spawn  # git and pip take seconds: off the tray thread
        self._lock = threading.Lock()
        self._label = _IDLE_LABEL
        self._pending: Optional[Decision] = None
        self._busy = False

    def label(self) -> str:
        with self._lock:
            return self._label

    def click(self) -> None:
        with self._lock:
            if self._busy:
                return
            apply = self._pending is not None
            self._set_busy_locked("Updating..." if apply else "Checking for updates...")
        self._refresh()
        self._spawn(self._apply if apply else self._check_and_pull_docs)

    def check_silently(self) -> None:
        """Startup check: may turn the item into "Update to ...", nothing else."""
        with self._lock:
            if self._busy:
                return
            self._busy = True
        self._spawn(self._silent_check)

    def _set_busy_locked(self, label: str) -> None:
        self._busy = True
        self._label = label

    def _finish(self, label: str, pending: Optional[Decision] = None) -> None:
        with self._lock:
            self._label = label
            self._pending = pending
            self._busy = False
        self._refresh()

    def _fail(self, reason: str) -> None:
        message = f"Update failed: {reason}"
        logger.warning(message)
        self._app_state.set_error(message)
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
            self._apply()
        else:
            self._fail(decision.reason)

    def _apply(self) -> None:
        result = self._updater.apply()
        if result.error:
            self._fail(result.error)
            return
        if result.restart:
            self._finish("Restarting...")
            self._on_restart()
            return
        if result.decision.action == Action.DOCS_ONLY:
            self._finish(f"Up to date ({result.decision.target}), no desktop changes")
        else:
            self._finish(f"Up to date ({result.decision.target})")
