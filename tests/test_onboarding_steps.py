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


def test_verify_connection_maps_rate_limit(monkeypatch):
    def raise_rate_limit(client):
        raise RateLimitedError(retry_after=42.0)

    monkeypatch.setattr(steps, "fetch_now_playing", raise_rate_limit)

    with pytest.raises(steps.OnboardingError):
        steps.verify_connection(object())


def test_verify_connection_maps_network_failure(monkeypatch):
    def raise_network(client):
        raise TransientNetworkError("dns down")

    monkeypatch.setattr(steps, "fetch_now_playing", raise_network)

    with pytest.raises(steps.OnboardingError):
        steps.verify_connection(object())


def test_authenticate_refuses_when_the_redirect_port_is_busy(monkeypatch):
    monkeypatch.setattr(steps, "is_redirect_port_free", lambda uri: False)

    with pytest.raises(steps.PortBusyError):
        steps.authenticate(Settings(client_id=_VALID))
