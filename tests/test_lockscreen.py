import json
import subprocess
from types import SimpleNamespace

from src.os_integration import lockscreen


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
        return _fake_completed()

    monkeypatch.setattr(subprocess, "run", fake_run)

    lockscreen.request_update(tmp_path / "wallpaper_a.png")
    lockscreen.is_task_installed()
    lockscreen.uninstall_task()

    assert len(flags) == 3
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
