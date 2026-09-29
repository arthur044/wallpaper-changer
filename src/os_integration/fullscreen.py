import ctypes
import logging
from typing import Optional

logger = logging.getLogger(__name__)

_S_OK = 0


def notification_state() -> Optional[int]:
    """Windows' QUERY_USER_NOTIFICATION_STATE: whether a full-screen app, a
    game or a presentation owns the screen. None when it can't be asked."""
    try:
        state = ctypes.c_int(0)
        if ctypes.windll.shell32.SHQueryUserNotificationState(ctypes.byref(state)) != _S_OK:
            return None
        return state.value
    except (AttributeError, OSError) as exc:
        logger.debug("SHQueryUserNotificationState failed: %s", exc)
        return None
