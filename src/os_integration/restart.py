import logging
import os
import subprocess
import sys
from typing import List

from src.os_integration import frozen

logger = logging.getLogger(__name__)


def relaunch_command() -> List[str]:
    """This app again, same interpreter (python or pythonw) and script; the exe
    alone when frozen. The CLI flags are all one-shot (--setup,
    --install-autostart...), so none carry over."""
    if frozen.is_frozen():
        return [sys.executable]
    return [sys.executable, os.path.abspath(sys.argv[0])]


def relaunch() -> None:
    """Starts a new instance; the caller is expected to exit right after."""
    command = relaunch_command()
    logger.info("Relaunching: %s", command)
    env = None
    if frozen.is_frozen():
        # A one-file exe unpacks itself to a temp folder and deletes it on exit.
        # The new instance must unpack its own, not borrow this one's.
        env = {**os.environ, "PYINSTALLER_RESET_ENVIRONMENT": "1"}
    subprocess.Popen(command, cwd=os.getcwd(), close_fds=True, env=env)
