import logging
import os
import subprocess
import sys
from typing import List

logger = logging.getLogger(__name__)


def relaunch_command() -> List[str]:
    """This app again, same interpreter (python or pythonw) and script. The CLI
    flags are all one-shot (--setup, --install-autostart...), so none carry over."""
    return [sys.executable, os.path.abspath(sys.argv[0])]


def relaunch() -> None:
    """Starts a new instance; the caller is expected to exit right after."""
    command = relaunch_command()
    logger.info("Relaunching: %s", command)
    subprocess.Popen(command, cwd=os.getcwd(), close_fds=True)
