import subprocess
import sys

from src.os_integration.no_window import NO_WINDOW


def test_no_window_is_windows_flag_there_and_nothing_elsewhere():
    # Outside Windows, subprocess rejects any creationflags but 0.
    expected = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
    assert NO_WINDOW == expected


def test_a_console_program_runs_with_it():
    result = subprocess.run(
        [sys.executable, "-c", "print('ok')"], capture_output=True, text=True, creationflags=NO_WINDOW
    )
    assert result.stdout.strip() == "ok"
