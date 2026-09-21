import socket

import pytest

from src.config.settings import Settings
from src.onboarding import steps
from src.spotify.client import (
    AuthExpiredError,
    NowPlaying,
    RateLimitedError,
    TransientNetworkError,
)

_VALID = "0123456789abcdef0123456789abcdef"


def _now_playing(is_playing=True, track_name="Afterlife", artist_name="Avenged Sevenfold"):
    return NowPlaying(
        is_playing=is_playing,
        track_id="t1",
        album_id="a1",
        art_url="http://x/y.jpg",
        track_name=track_name,
        artist_name=artist_name,
    )


def test_redirect_port_is_parsed_from_the_uri():
    assert steps.redirect_port("http://127.0.0.1:8888/callback") == 8888


def test_redirect_port_is_none_when_the_uri_has_no_port():
    assert steps.redirect_port("http://127.0.0.1/callback") is None


def test_port_is_reported_busy_while_something_holds_it():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as holder:
        holder.bind(("127.0.0.1", 0))
        holder.listen(1)
        port = holder.getsockname()[1]

        assert steps.is_redirect_port_free(f"http://127.0.0.1:{port}/callback") is False


def test_free_port_is_reported_free():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    assert steps.is_redirect_port_free(f"http://127.0.0.1:{port}/callback") is True


def test_save_client_id_normalizes_and_persists(monkeypatch):
    saved = []
    monkeypatch.setattr(steps, "save_settings", lambda settings: saved.append(settings))
    settings = Settings()

    steps.save_client_id(settings, f"  {_VALID}\n")

    assert settings.client_id == _VALID
    assert saved == [settings]


def test_verify_connection_reports_the_playing_track(monkeypatch):
    monkeypatch.setattr(steps, "fetch_now_playing", lambda client: _now_playing())

    outcome = steps.verify_connection(object())

    assert outcome.result is steps.VerifyResult.PLAYING
    assert outcome.track_name == "Afterlife"
    assert outcome.artist_name == "Avenged Sevenfold"


def test_verify_connection_treats_nothing_playing_as_success(monkeypatch):
    monkeypatch.setattr(steps, "fetch_now_playing", lambda client: None)

    assert steps.verify_connection(object()).result is steps.VerifyResult.NOTHING_PLAYING


def test_verify_connection_treats_paused_as_nothing_playing(monkeypatch):
    monkeypatch.setattr(steps, "fetch_now_playing", lambda client: _now_playing(is_playing=False))

    assert steps.verify_connection(object()).result is steps.VerifyResult.NOTHING_PLAYING


def test_verify_connection_maps_auth_failure(monkeypatch):
    def raise_auth(client):
        raise AuthExpiredError("token revoked")

    monkeypatch.setattr(steps, "fetch_now_playing", raise_auth)

    with pytest.raises(steps.AuthenticationFailedError):
        steps.verify_connection(object())


def test_verify_connection_maps_rate_limit_and_keeps_the_retry_window(monkeypatch):
    def raise_rate_limit(client):
        raise RateLimitedError(retry_after=42.0)

    monkeypatch.setattr(steps, "fetch_now_playing", raise_rate_limit)

    with pytest.raises(steps.RateLimitedOnboardingError) as excinfo:
        steps.verify_connection(object())

    # Available as a number, not buried in the message text.
    assert excinfo.value.retry_after == 42.0


def test_nothing_playing_cannot_carry_track_details():
    with pytest.raises(ValueError):
        steps.VerifyOutcome(result=steps.VerifyResult.NOTHING_PLAYING, track_name="Afterlife")


def test_description_joins_the_names_it_has():
    outcome = steps.VerifyOutcome(
        result=steps.VerifyResult.PLAYING, track_name="Afterlife", artist_name="Avenged Sevenfold"
    )

    assert outcome.description == "Afterlife - Avenged Sevenfold"


def test_description_skips_a_name_the_api_left_empty():
    # NowPlaying.track_name/artist_name are Optional all the way from the API,
    # so this used to render a literal "None" in the wizard.
    outcome = steps.VerifyOutcome(result=steps.VerifyResult.PLAYING, track_name="Afterlife")

    assert outcome.description == "Afterlife"


def test_verify_connection_maps_network_failure(monkeypatch):
    def raise_network(client):
        raise TransientNetworkError("dns down")

    monkeypatch.setattr(steps, "fetch_now_playing", raise_network)

    with pytest.raises(steps.OnboardingError):
        steps.verify_connection(object())


class _FakeLockscreen:
    def __init__(self, task_installed=False, install_succeeds=True):
        self.task_installed = task_installed
        self.install_succeeds = install_succeeds
        self.calls = []

    def is_task_installed(self):
        self.calls.append("is_task_installed")
        return self.task_installed

    def install_task(self):
        self.calls.append("install_task")
        return self.install_succeeds

    def uninstall_task(self):
        self.calls.append("uninstall_task")


def _patch_options(monkeypatch, lock, autostart_error=None):
    saved = []
    installed = []

    def fake_install_autostart():
        if autostart_error is not None:
            raise autostart_error
        installed.append(1)

    monkeypatch.setattr(steps, "lockscreen", lock)
    monkeypatch.setattr(steps, "install_autostart", fake_install_autostart)
    monkeypatch.setattr(steps, "save_settings", lambda settings: saved.append(settings))
    return saved, installed


def test_disabling_lock_screen_sync_uninstalls_the_scheduled_task(monkeypatch):
    # Regression: the wizard used to only flip the setting, leaving the
    # elevated scheduled task registered and firing at every logon while
    # the config claimed the feature was off.
    lock = _FakeLockscreen(task_installed=True)
    saved, _ = _patch_options(monkeypatch, lock)
    settings = Settings(sync_lock_screen=True)

    result = steps.apply_options(settings, autostart=False, sync_lock_screen=False)

    assert "uninstall_task" in lock.calls
    assert settings.sync_lock_screen is False
    assert saved == [settings]
    assert result.finished is True


def test_enabling_lock_screen_sync_installs_the_task(monkeypatch):
    lock = _FakeLockscreen(task_installed=False, install_succeeds=True)
    saved, _ = _patch_options(monkeypatch, lock)
    settings = Settings(sync_lock_screen=False)

    result = steps.apply_options(settings, autostart=False, sync_lock_screen=True)

    assert "install_task" in lock.calls
    assert settings.sync_lock_screen is True
    assert saved == [settings]
    assert result.finished is True


def test_declined_lock_screen_task_is_reported_and_not_persisted(monkeypatch):
    lock = _FakeLockscreen(task_installed=False, install_succeeds=False)
    saved, _ = _patch_options(monkeypatch, lock)
    settings = Settings(sync_lock_screen=False)

    result = steps.apply_options(settings, autostart=False, sync_lock_screen=True)

    assert result.lockscreen_declined is True
    assert result.finished is False
    assert settings.sync_lock_screen is False
    assert saved == []  # nothing persisted for a feature that wasn't authorized


def test_already_installed_task_is_not_reinstalled(monkeypatch):
    lock = _FakeLockscreen(task_installed=True)
    saved, _ = _patch_options(monkeypatch, lock)
    settings = Settings(sync_lock_screen=False)

    steps.apply_options(settings, autostart=False, sync_lock_screen=True)

    assert "install_task" not in lock.calls
    assert settings.sync_lock_screen is True


def test_autostart_failure_stops_before_touching_settings(monkeypatch):
    lock = _FakeLockscreen()
    saved, _ = _patch_options(monkeypatch, lock, autostart_error=OSError("registry denied"))
    settings = Settings(sync_lock_screen=False)

    result = steps.apply_options(settings, autostart=True, sync_lock_screen=True)

    assert result.autostart_error == "registry denied"
    assert result.finished is False
    assert saved == []
    assert lock.calls == []


def test_autostart_is_installed_when_requested(monkeypatch):
    lock = _FakeLockscreen()
    saved, installed = _patch_options(monkeypatch, lock)

    steps.apply_options(Settings(), autostart=True, sync_lock_screen=False)

    assert installed == [1]
    assert len(saved) == 1


def test_authenticate_refuses_when_the_redirect_port_is_busy(monkeypatch):
    monkeypatch.setattr(steps, "is_redirect_port_free", lambda uri: False)

    with pytest.raises(steps.PortBusyError):
        steps.authenticate(Settings(client_id=_VALID))


def test_spotify_redirect_rejection_is_recognized():
    assert steps.looks_like_redirect_mismatch("INVALID_CLIENT: Invalid redirect URI") is True
    assert steps.looks_like_redirect_mismatch("error: invalid_client, redirect_uri mismatch") is True


def test_unrelated_redirect_wording_is_not_mistaken_for_a_dashboard_problem():
    # requests raises this for a redirect loop; telling the user to go fix
    # their dashboard would send them chasing the wrong thing entirely.
    assert steps.looks_like_redirect_mismatch("Exceeded 30 redirects.") is False
    assert steps.looks_like_redirect_mismatch("") is False


class _FakeAuthManager:
    def __init__(self, error=None):
        self._error = error

    def get_access_token(self):
        if self._error is not None:
            raise self._error
        return {"access_token": "token"}


def _patch_auth(monkeypatch, error=None):
    monkeypatch.setattr(steps, "is_redirect_port_free", lambda uri: True)
    monkeypatch.setattr(
        steps, "build_auth_manager", lambda client_id, redirect_uri, scope: _FakeAuthManager(error)
    )


def test_authenticate_maps_a_redirect_rejection(monkeypatch):
    _patch_auth(monkeypatch, error=Exception("INVALID_CLIENT: Invalid redirect URI"))

    with pytest.raises(steps.RedirectUriMismatchError) as excinfo:
        steps.authenticate(Settings(client_id=_VALID))

    # The original text must survive; it's the only record of the real cause.
    assert "INVALID_CLIENT: Invalid redirect URI" in str(excinfo.value)


def test_authenticate_maps_other_failures_generically(monkeypatch):
    _patch_auth(monkeypatch, error=Exception("Exceeded 30 redirects."))

    with pytest.raises(steps.AuthenticationFailedError) as excinfo:
        steps.authenticate(Settings(client_id=_VALID))

    assert "Exceeded 30 redirects." in str(excinfo.value)


def test_authenticate_returns_a_client_on_success(monkeypatch):
    _patch_auth(monkeypatch)

    client = steps.authenticate(Settings(client_id=_VALID))

    assert client is not None
