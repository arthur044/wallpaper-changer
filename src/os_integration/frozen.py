import sys
from pathlib import Path
from typing import Optional


def is_frozen() -> bool:
    """True in the PyInstaller exe, where there is no python, main.py or git clone."""
    return bool(getattr(sys, "frozen", False))


def bundled_file(name: str) -> Optional[Path]:
    """A data file packed into the exe (PyInstaller unpacks it to _MEIPASS)."""
    root = getattr(sys, "_MEIPASS", None)
    return Path(root) / name if root else None
