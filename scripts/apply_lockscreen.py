"""Entry point of apply-lockscreen.exe, the exe build's lock screen helper.

The scheduled task runs elevated on every track change. Starting the whole app
for that (a one-file exe unpacks ~100 MB each time) is far too heavy, so this
imports only what writes the registry values. Built by scripts/build-exe.ps1.
"""
from src.os_integration import lockscreen
from src.utils.logger import setup_logging


def main() -> None:
    setup_logging()
    lockscreen.apply_pending()


if __name__ == "__main__":
    main()
