import pytest

from src.graphics import renderer
from src.graphics.renderer import download_art, is_spotify_art_url


class _FakeResponse:
    def __init__(self, url: str, content: bytes = b"img") -> None:
        self.url = url
        self.content = content

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def raise_for_status(self) -> None:
        pass


def test_spotify_cdn_hosts_are_accepted():
    assert is_spotify_art_url("https://i.scdn.co/image/ab67616d0000b273")
    assert is_spotify_art_url("https://mosaic.scdn.co/640/abc")
    assert is_spotify_art_url("https://image-cdn-ak.spotifycdn.com/image/abc")


def test_plain_http_and_look_alike_hosts_are_refused():
    assert not is_spotify_art_url("http://i.scdn.co/image/abc")
    assert not is_spotify_art_url("https://evil.example/image/abc")
    assert not is_spotify_art_url("https://notscdn.co/image/abc")
    assert not is_spotify_art_url("https://i.scdn.co.evil.example/image/abc")
    assert not is_spotify_art_url("not a url")


def test_a_foreign_url_is_refused_without_any_request(monkeypatch):
    calls = []
    monkeypatch.setattr(renderer.requests, "get", lambda url, **kw: calls.append(url))
    with pytest.raises(ValueError):
        download_art("https://evil.example/image/abc")
    assert calls == []


def test_a_redirect_out_of_the_cdn_is_refused(monkeypatch):
    monkeypatch.setattr(renderer.requests, "get", lambda url, **kw: _FakeResponse("https://evil.example/x"))
    with pytest.raises(ValueError):
        download_art("https://i.scdn.co/image/abc")


def test_spotify_art_is_downloaded(monkeypatch):
    # Control for the two tests above: the same fake, left on the CDN, goes through.
    monkeypatch.setattr(renderer.requests, "get", lambda url, **kw: _FakeResponse(url, b"bytes"))
    assert download_art("https://i.scdn.co/image/abc") == b"bytes"
