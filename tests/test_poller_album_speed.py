from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying, RateLimitedError
from src.spotify.poller import Poller
from src.utils.app_state import AppState


class _Clock:
    def __init__(self):
        self.now = 1000.0

    def monotonic(self):
        return self.now


class _Smtc:
    def __init__(self, title):
        self.snapshot = _snapshot(title)

    def get_snapshot(self):
        return self.snapshot


def _snapshot(title):
    return SmtcNowPlaying(title=title, artist="Angra", album_title="?", album_artist="Angra", is_playing=True)


def _api(title, album):
    return NowPlaying(
        is_playing=True,
        track_id=f"id-{title}",
        album_id=album,
        art_url=f"http://cdn.example/{album}.jpg",
        track_name=title,
        artist_name="Angra",
    )


class _Harness:
    """A poller on a fake clock; the Web API answers and tracklists are set per test."""

    def __init__(self, monkeypatch, title="Rebirth"):
        self.clock = _Clock()
        monkeypatch.setattr(poller_module, "time", self.clock)
        self.events = []
        self.api_answers = []
        self.tracklists = {}

        def fake_now_playing(client):
            self.events.append("now_playing")
            answer = self.api_answers.pop(0) if len(self.api_answers) > 1 else self.api_answers[0]
            if isinstance(answer, Exception):
                raise answer
            return answer

        def fake_album_tracks(client, album_id):
            self.events.append(f"tracks:{album_id}")
            answer = self.tracklists.get(album_id, [])
            if isinstance(answer, Exception):
                raise answer
            return answer

        monkeypatch.setattr(poller_module, "fetch_now_playing", fake_now_playing)
        monkeypatch.setattr(poller_module, "fetch_album_tracks", fake_album_tracks)
        self.smtc = _Smtc(title)
        self.rendered = []
        self.poller = Poller(
            client=object(),
            settings=Settings(poll_interval_seconds=4.0, fallback_poll_interval_seconds=25.0),
            app_state=AppState(),
            render_fn=self._render,
            reauth_fn=lambda: object(),
            smtc_watcher=self.smtc,
        )

    def _render(self, now_playing):
        self.events.append(f"render:{now_playing.album_id}")
        self.rendered.append(now_playing)

    def play(self, title):
        self.smtc.snapshot = _snapshot(title)

    def cycle(self, after=0.0):
        self.clock.now += after
        self.poller._run_one_cycle()

    def api_calls(self):
        return [e for e in self.events if not e.startswith("render")]


def test_a_new_album_right_after_another_waits_seconds_not_the_poll_interval(monkeypatch):
    h = _Harness(monkeypatch)
    h.api_answers = [_api("Rebirth", "rebirth")]
    h.cycle()
    h.play("Carry On")
    h.api_answers = [_api("Carry On", "angels_cry")]

    h.cycle(after=4.0)
    assert [r.album_id for r in h.rendered] == ["rebirth"], "still inside the 5 s spacing"

    h.cycle(after=1.5)
    assert [r.album_id for r in h.rendered] == ["rebirth", "angels_cry"], "not held for the 25 s poll interval"


def test_album_lookups_are_never_closer_than_five_seconds(monkeypatch):
    h = _Harness(monkeypatch)
    h.api_answers = [_api("Something Else", "other")]  # never matches: keeps retrying
    for _ in range(20):
        h.cycle(after=0.5)  # 10 s of cycles every half second

    now_playing_calls = [e for e in h.events if e == "now_playing"]
    assert len(now_playing_calls) <= 3  # t=0, 5, 10


def test_an_answer_still_about_the_previous_track_is_not_drawn_with_the_new_one(monkeypatch):
    # The Web API trails the desktop app by a second or two: asked right as the
    # song changes, it may still describe the old one, and its album art would
    # land on the new song's wallpaper and stay there.
    h = _Harness(monkeypatch, title="Carry On")
    h.api_answers = [_api("Rebirth", "rebirth"), _api("Carry On", "angels_cry")]

    h.cycle()
    assert h.rendered == []

    h.cycle(after=5.0)
    assert [r.album_id for r in h.rendered] == ["angels_cry"]


def test_a_title_that_never_matches_is_drawn_after_a_few_tries(monkeypatch):
    # The desktop app and the Web API can spell a title differently; never
    # drawing it at all would be worse than the old, unchecked behavior.
    h = _Harness(monkeypatch, title="Nothing To Say (Remastered)")
    h.api_answers = [_api("Nothing To Say - Remastered", "holy_land")]

    for _ in range(3):
        h.cycle(after=5.0)

    assert [r.album_id for r in h.rendered] == ["holy_land"]


def test_the_tracklist_is_fetched_after_the_wallpaper_is_drawn(monkeypatch):
    h = _Harness(monkeypatch)
    h.api_answers = [_api("Rebirth", "rebirth")]
    h.tracklists = {"rebirth": [("Rebirth", "Angra"), ("Heroes of Sand", "Angra")]}

    h.cycle()

    assert h.events == ["now_playing", "render:rebirth", "tracks:rebirth"]


def test_each_album_tracklist_is_fetched_once(monkeypatch):
    h = _Harness(monkeypatch)
    h.api_answers = [_api("Rebirth", "rebirth")]
    h.tracklists = {"rebirth": [("Rebirth", "Angra")]}  # "Heroes of Sand" missing: resolved again
    h.cycle()
    h.play("Heroes of Sand")
    h.api_answers = [_api("Heroes of Sand", "rebirth")]

    h.cycle(after=5.0)

    assert h.events.count("tracks:rebirth") == 1


def test_a_rate_limit_on_the_tracklist_holds_every_call(monkeypatch):
    h = _Harness(monkeypatch)
    h.api_answers = [_api("Rebirth", "rebirth")]
    h.tracklists = {"rebirth": RateLimitedError(60.0)}
    h.cycle()
    h.play("Carry On")
    h.api_answers = [_api("Carry On", "angels_cry")]

    h.cycle(after=30.0)
    assert h.api_calls() == ["now_playing", "tracks:rebirth"], "a 429 is not answered with more requests"

    h.cycle(after=31.0)
    assert h.api_calls()[2] == "now_playing", "once the wait is over, the new album is looked up"
