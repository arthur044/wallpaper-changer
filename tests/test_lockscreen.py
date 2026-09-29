import json
import subprocess
from types import SimpleNamespace

import pytest

from src.os_integration import lockscreen


@pytest.fixture(autouse=True)
def _never_elevate_for_real(monkeypatch):
    # _run_elevated shows a real UAC prompt and runs schtasks as admin on the
    # real task. A test that reaches it by accident (a fake /query saying the
    # task survived) once deleted the user's task. Tests that need it patch
    # it themselves, after this.
    def refuse(exe, params):
        raise AssertionError(f"test reached the real elevation: {exe} {params}")

    monkeypatch.setattr(lockscreen, "_run_elevated", refuse)


def _fake_completed(returncode=0, stderr=b""):
    return SimpleNamespace(returncode=returncode, stdout=b"", stderr=stderr)


def test_request_update_writes_pending_path_and_triggers_task(monkeypatch, tmp_path):
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)
    calls = []

    def fake_run(args, **kwargs):
        calls.append(args)
        return _fake_completed()

    monkeypatch.setattr(subprocess, "run", fake_run)

    target = tmp_path / "wallpaper_a.png"
    lockscreen.request_update(target)

    pending = json.loads((tmp_path / "lockscreen_pending.json").read_text(encoding="utf-8"))
    assert pending["path"] == str(target.resolve())
    assert calls == [["schtasks", "/run", "/tn", lockscreen._TASK_NAME]]


def test_no_schtasks_call_opens_a_console_window(monkeypatch, tmp_path):
    # The app runs under pythonw, which has no console: without CREATE_NO_WINDOW
    # Windows gives each schtasks.exe a console of its own, a terminal that
    # flashes on screen on every track change.
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)
    flags = []

    def fake_run(args, **kwargs):
        flags.append(kwargs.get("creationflags", 0))
        gone = "/query" in args and len(flags) > 2  # after the /delete
        return _fake_completed(returncode=1 if gone else 0)

    monkeypatch.setattr(subprocess, "run", fake_run)

    lockscreen.request_update(tmp_path / "wallpaper_a.png")
    lockscreen.is_task_installed()
    lockscreen.uninstall_task()  # the /delete succeeds, so no elevated retry

    assert len(flags) == 4  # /run, /query, /delete, /query to confirm
    assert all(f & subprocess.CREATE_NO_WINDOW for f in flags)


def test_request_update_logs_and_skips_run_when_write_fails(monkeypatch, tmp_path):
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path / "does-not-exist")
    calls = []
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: calls.append(a) or _fake_completed())

    lockscreen.request_update(tmp_path / "wallpaper_a.png")

    assert calls == []


def test_apply_pending_writes_registry_values_from_pending_file(monkeypatch, tmp_path):
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)
    image_path = str(tmp_path / "wallpaper_a.png")
    (tmp_path / "lockscreen_pending.json").write_text(json.dumps({"path": image_path}), encoding="utf-8")

    written = []
    monkeypatch.setattr(lockscreen, "_write_registry", lambda path: written.append(path))

    lockscreen.apply_pending()

    assert written == [image_path]


def test_apply_pending_noop_when_no_pending_file(monkeypatch, tmp_path):
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)
    written = []
    monkeypatch.setattr(lockscreen, "_write_registry", lambda path: written.append(path))

    lockscreen.apply_pending()

    assert written == []


def test_apply_pending_noop_on_corrupt_pending_file(monkeypatch, tmp_path):
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)
    (tmp_path / "lockscreen_pending.json").write_text("not json", encoding="utf-8")
    written = []
    monkeypatch.setattr(lockscreen, "_write_registry", lambda path: written.append(path))

    lockscreen.apply_pending()

    assert written == []


def test_is_task_installed_reflects_schtasks_return_code(monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _fake_completed(returncode=0))
    assert lockscreen.is_task_installed() is True

    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _fake_completed(returncode=1))
    assert lockscreen.is_task_installed() is False


def test_install_task_returns_false_when_elevation_declined(monkeypatch):
    monkeypatch.setattr(lockscreen, "_run_elevated", lambda exe, params: False)
    assert lockscreen.install_task() is False


def test_install_task_returns_true_when_elevation_succeeds(monkeypatch):
    captured = {}

    def fake_run_elevated(exe, params):
        captured["exe"] = exe
        captured["params"] = params
        return True

    monkeypatch.setattr(lockscreen, "_run_elevated", fake_run_elevated)

    assert lockscreen.install_task() is True
    assert captured["exe"] == "schtasks.exe"
    assert lockscreen._TASK_NAME in captured["params"]
    assert "/rl highest" in captured["params"]
    assert '/tr "\\"' in captured["params"], "the whole launch command must be one quoted /tr argument"


def _task_xml(command, arguments):
    # Shape of `schtasks /query /xml` piped to a file (entities as schtasks writes them).
    return (
        '<?xml version="1.0" encoding="UTF-16"?>\r\n<Task><Actions Context="Author"><Exec>\r\n'
        f"      <Command>{command}</Command>\r\n      <Arguments>{arguments}</Arguments>\r\n"
        "</Exec></Actions></Task>"
    ).encode("cp1252")


def _split_launch_command():
    command = lockscreen._launch_command()
    exe_end = command.index('"', 1) + 1
    return command[:exe_end], command[exe_end + 1 :]


def test_ensure_task_keeps_a_task_that_runs_this_folder(monkeypatch):
    exe, args = _split_launch_command()
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout=_task_xml(exe, args)))
    installs = []
    monkeypatch.setattr(lockscreen, "install_task", lambda: installs.append(1) or True)

    assert lockscreen.ensure_task() is True
    assert installs == []


def test_ensure_task_replaces_a_task_left_by_another_folder(monkeypatch):
    # Regression: after the app moved to its own clone, re-enabling Sync Lock
    # Screen kept the old task, which went on running the dev folder's code.
    xml = _task_xml('"C:\old\.venv\Scripts\pythonw.exe"', '"C:\old\main.py" --apply-lockscreen')
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout=xml))
    installs = []
    monkeypatch.setattr(lockscreen, "install_task", lambda: installs.append(1) or True)

    assert lockscreen.ensure_task() is True
    assert installs == [1]


def test_ensure_task_installs_when_there_is_none(monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=1, stdout=b""))
    monkeypatch.setattr(lockscreen, "install_task", lambda: False)

    assert lockscreen.ensure_task() is False  # declined UAC comes back as False


def test_registered_command_reads_command_and_arguments(monkeypatch):
    xml = _task_xml('"C:\a b\pythonw.exe"', '"C:\a b\main.py" --apply-lockscreen')
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout=xml))

    assert lockscreen._registered_command() == '"C:\a b\pythonw.exe" "C:\a b\main.py" --apply-lockscreen'


def test_uninstall_that_works_unelevated_skips_uac(monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _fake_completed(returncode=1 if "/query" in a[0] else 0))
    elevated = []
    monkeypatch.setattr(lockscreen, "_run_elevated", lambda exe, params: elevated.append(params) or True)

    assert lockscreen.uninstall_task() is True
    assert elevated == []


def test_uninstall_retries_elevated_when_the_task_survives(monkeypatch):
    # Regression: the plain /delete fails on a task created elevated, and the
    # log still said "removed" while the task stayed registered.
    state = {"installed": True}

    def fake_run(args, **kwargs):
        if "/query" in args:
            return _fake_completed(returncode=0 if state["installed"] else 1)
        return _fake_completed(returncode=1)  # access denied

    def fake_elevated(exe, params):
        state["installed"] = False
        return True

    monkeypatch.setattr(subprocess, "run", fake_run)
    monkeypatch.setattr(lockscreen, "_run_elevated", fake_elevated)

    assert lockscreen.uninstall_task() is True
    assert state["installed"] is False


def test_uninstall_reports_a_declined_uac(monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _fake_completed(returncode=0 if "/query" in a[0] else 1))
    monkeypatch.setattr(lockscreen, "_run_elevated", lambda exe, params: False)

    assert lockscreen.uninstall_task() is False
