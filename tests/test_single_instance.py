import os
import sys
import threading
import uuid

import pytest

from src.os_integration import single_instance
from src.os_integration.single_instance import (
    WAIT_ABANDONED,
    WAIT_FAILED,
    WAIT_OBJECT_0,
    WAIT_TIMEOUT,
    acquire,
    wait_outcome,
)


def test_owned_mutex_is_acquired():
    assert wait_outcome(WAIT_OBJECT_0) == "acquired"


def test_mutex_left_by_a_dead_instance_is_acquired():
    # The old instance exits (Restart) without releasing: Windows hands the
    # mutex over as abandoned, which is still ours.
    assert wait_outcome(WAIT_ABANDONED) == "acquired"


def test_timeout_means_another_instance_is_running():
    assert wait_outcome(WAIT_TIMEOUT) == "busy"


def test_a_failed_wait_runs_anyway():
    # A broken guard must not keep the app from starting.
    assert wait_outcome(WAIT_FAILED) == "unguarded"


class _FakeApi:
    def __init__(self, handle=7, wait_result=WAIT_OBJECT_0):
        self.handle = handle
        self.wait_result = wait_result
        self.waited_ms = None
        self.closed = []
        self.released = []

    def create_mutex(self, name):
        return self.handle

    def wait(self, handle, timeout_ms):
        self.waited_ms = timeout_ms
        return self.wait_result

    def release(self, handle):
        self.released.append(handle)

    def close(self, handle):
        self.closed.append(handle)


def test_acquire_waits_the_given_time_for_the_old_instance():
    api = _FakeApi()

    lock = acquire("name", timeout_s=20.0, api=api)

    assert lock is not None
    assert api.waited_ms == 20_000


def test_busy_mutex_returns_none_and_closes_the_handle():
    api = _FakeApi(wait_result=WAIT_TIMEOUT)

    assert acquire("name", timeout_s=0.1, api=api) is None
    assert api.closed == [7]


def test_mutex_that_cannot_be_created_runs_unguarded():
    api = _FakeApi(handle=0)

    lock = acquire("name", timeout_s=0.1, api=api)

    assert lock is not None
    lock.release()  # nothing to release, must not raise
    assert api.released == [] and api.closed == []


def test_release_gives_the_mutex_back_once():
    api = _FakeApi()
    lock = acquire("name", timeout_s=0.1, api=api)

    lock.release()
    lock.release()

    assert api.released == [7]
    assert api.closed == [7]


@pytest.mark.skipif(sys.platform != "win32", reason="a real Win32 mutex")
def test_real_mutex_lets_only_one_holder_through():
    name = f"Local\\SpotifyWallpaperEngineTest-{uuid.uuid4()}"
    first = acquire(name, timeout_s=0.1)
    assert first is not None

    # Win32 mutexes are recursive per thread, so the rival has to be another thread.
    rival = []
    thread = threading.Thread(target=lambda: rival.append(acquire(name, timeout_s=0.1)))
    thread.start()
    thread.join()
    assert rival == [None]

    first.release()
    after = []

    def take_and_give_back():
        lock = acquire(name, timeout_s=0.1)
        after.append(lock)
        if lock is not None:
            lock.release()

    thread = threading.Thread(target=take_and_give_back)
    thread.start()
    thread.join()
    assert after[0] is not None


def test_mutex_name_is_per_session():
    assert single_instance.MUTEX_NAME == "Local\\SpotifyWallpaperEngine"


# --- Linux: flock -----------------------------------------------------------------

linux_only = pytest.mark.skipif(sys.platform == "win32", reason="flock: not on Windows")


@linux_only
def test_flock_lets_only_one_holder_through(tmp_path):
    api = single_instance._Flock(tmp_path)
    first = acquire("Local\\Test", timeout_s=0.1, api=api)
    assert first is not None

    # A second open of the file is a second lock, even in the same process.
    assert acquire("Local\\Test", timeout_s=0.2, api=api) is None

    first.release()
    again = acquire("Local\\Test", timeout_s=0.1, api=api)
    assert again is not None
    again.release()


@linux_only
def test_flock_waits_for_the_old_instance_of_a_restart(tmp_path):
    api = single_instance._Flock(tmp_path)
    old = acquire("Local\\Test", timeout_s=0.1, api=api)
    threading.Timer(0.3, old.release).start()

    new = acquire("Local\\Test", timeout_s=5.0, api=api)

    assert new is not None
    new.release()


@linux_only
def test_a_dead_holder_leaves_the_lock_free(tmp_path):
    import subprocess

    lock_file = tmp_path / "Test.lock"
    # Another process takes the lock and dies without releasing it.
    subprocess.run(
        [sys.executable, "-c", f"import fcntl, os; fd = os.open({str(lock_file)!r}, os.O_RDWR | os.O_CREAT); fcntl.flock(fd, fcntl.LOCK_EX); os._exit(0)"],
        check=True,
    )

    lock = acquire("Local\\Test", timeout_s=0.5, api=single_instance._Flock(tmp_path))

    assert lock is not None
    lock.release()


@linux_only
def test_the_lock_is_not_inherited_by_the_instance_a_restart_launches(tmp_path):
    api = single_instance._Flock(tmp_path)
    handle = api.create_mutex("Local\\Test")
    try:
        assert os.get_inheritable(handle) is False
    finally:
        api.close(handle)


@linux_only
def test_the_lock_file_lives_in_the_runtime_dir(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))

    assert single_instance._Flock()._lock_path(single_instance.MUTEX_NAME) == tmp_path / "SpotifyWallpaperEngine.lock"


@linux_only
def test_without_a_runtime_dir_the_lock_file_goes_to_the_state_dir(monkeypatch, tmp_path):
    from src.config import paths

    monkeypatch.setenv("XDG_RUNTIME_DIR", "relative/is/invalid")
    monkeypatch.setattr(paths, "state_dir", lambda: tmp_path)

    assert single_instance._Flock()._lock_path("Local\\Test") == tmp_path / "Test.lock"
