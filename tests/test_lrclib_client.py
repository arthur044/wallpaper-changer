"""Ported from the Android LrclibClientTest. The fixtures in fixtures/lrclib
are saved LRCLIB answers (real field layout, titles and durations) with the
words replaced by placeholder lines."""

import json
import logging
from pathlib import Path

import pytest
import requests

from src.lyrics.lrclib import (
    Instrumental,
    LrclibClient,
    LrclibRecord,
    LyricsQuery,
    LyricsUnavailableError,
    NotFound,
    SyncedLyrics,
    TextLyrics,
    parse_record,
    pick,
)
from src.lyrics.text import TimedLine

_FIXTURES = Path(__file__).parent / "fixtures" / "lrclib"

METROPOLIS = LyricsQuery(
    artist="Dream Theater",
    title='Metropolis - Part I: "The Miracle and the Sleeper"',
    album="Images and Words",
    duration_ms=571_000,
)
YYZ = LyricsQuery("Rush", "YYZ", "Moving Pictures", 266_000)
# The fixtures' synced uploads stamp three placeholder lines.
PLACEHOLDERS = ("Placeholder line 1", "Placeholder line 2", "Placeholder line 3")


def _fixture(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


class _Response:
    def __init__(self, status_code: int, body: str):
        self.status_code = status_code
        self._body = body

    def json(self):
        return json.loads(self._body)


class _FakeHttp:
    """Answers queued responses in order and records every request."""

    def __init__(self):
        self.queue = []
        self.requests = []

    def respond(self, status: int, body: str = "") -> None:
        self.queue.append(_Response(status, body))

    def fail(self, exc: Exception) -> None:
        self.queue.append(exc)

    def get(self, url, params=None, headers=None, timeout=None):
        self.requests.append({"url": url, "params": dict(params or {}), "headers": dict(headers or {}), "timeout": timeout})
        answer = self.queue.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer


@pytest.fixture
def http():
    return _FakeHttp()


@pytest.fixture
def client(http):
    return LrclibClient(user_agent="WallpaperChanger-test", base_url="http://lrclib.test/api/", http=http)


def _words(lyrics):
    if isinstance(lyrics, SyncedLyrics):
        return tuple(line.text for line in lyrics.lines)
    return lyrics.lines


def test_the_exact_lookup_sends_all_four_fields_quotes_and_colon_intact(client, http):
    http.respond(200, _fixture("get_metropolis.json"))

    lyrics = client.lyrics(METROPOLIS)

    assert isinstance(lyrics, SyncedLyrics)
    assert _words(lyrics) == PLACEHOLDERS
    request = http.requests[0]
    assert request["url"] == "http://lrclib.test/api/get"
    assert request["params"] == {
        "artist_name": "Dream Theater",
        "track_name": 'Metropolis - Part I: "The Miracle and the Sleeper"',
        "album_name": "Images and Words",
        "duration": "571",
    }
    assert request["headers"]["User-Agent"] == "WallpaperChanger-test"
    assert request["timeout"] is not None, "a lookup must never hang the widget's thread"
    assert len(http.requests) == 1


def test_the_synced_lyrics_keep_their_times(client, http):
    http.respond(200, _fixture("get_metropolis.json"))

    lyrics = client.lyrics(METROPOLIS)

    times = [line.time_ms for line in lyrics.lines]
    assert times == sorted(times) and times[0] >= 0 and len(set(times)) == len(times)


def test_with_several_artists_the_lookup_asks_by_the_first_as_spotify_lists_them(client, http):
    http.respond(404)
    http.respond(200, "[]")
    duet = LyricsQuery("Dream Theater, Guest", METROPOLIS.title, METROPOLIS.album, METROPOLIS.duration_ms, ("Dream Theater", "Guest"))

    client.lyrics(duet)

    assert [r["params"]["artist_name"] for r in http.requests] == ["Dream Theater", "Dream Theater"]


def test_an_artist_with_a_comma_in_the_name_is_never_split(client, http):
    http.respond(404)
    http.respond(200, "[]")

    client.lyrics(LyricsQuery("Tyler, The Creator", "Song", "Album", 200_000))

    assert http.requests[0]["params"]["artist_name"] == "Tyler, The Creator"


def test_a_miss_on_the_exact_lookup_falls_back_to_the_search(client, http):
    http.respond(404, _fixture("not_found.json"))
    http.respond(200, _fixture("search_metropolis.json"))

    assert _words(client.lyrics(METROPOLIS)) == PLACEHOLDERS

    get, search = http.requests
    assert get["url"].endswith("/get")
    assert search["url"] == "http://lrclib.test/api/search"
    assert search["params"] == {"artist_name": "Dream Theater", "track_name": METROPOLIS.title}


def test_a_bad_request_on_the_exact_lookup_also_falls_back_to_the_search(client, http):
    http.respond(400)
    http.respond(200, _fixture("search_metropolis.json"))

    assert _words(client.lyrics(METROPOLIS)) == PLACEHOLDERS
    assert len(http.requests) == 2


def test_an_exact_hit_without_words_falls_back_to_the_search(client, http):
    http.respond(200, '{"trackName": "Metropolis", "artistName": "Dream Theater", "instrumental": false}')
    http.respond(200, _fixture("search_metropolis.json"))

    assert _words(client.lyrics(METROPOLIS)) == PLACEHOLDERS
    assert len(http.requests) == 2


def test_without_a_duration_the_lookup_goes_straight_to_the_search(client, http):
    http.respond(200, _fixture("search_metropolis.json"))

    query = LyricsQuery(METROPOLIS.artist, METROPOLIS.title, METROPOLIS.album, None)
    assert _words(client.lyrics(query)) == PLACEHOLDERS
    assert [r["url"].rsplit("/", 1)[1] for r in http.requests] == ["search"]


def test_without_an_album_the_lookup_goes_straight_to_the_search(client, http):
    http.respond(200, _fixture("search_metropolis.json"))

    query = LyricsQuery(METROPOLIS.artist, METROPOLIS.title, " ", METROPOLIS.duration_ms)
    assert _words(client.lyrics(query)) == PLACEHOLDERS
    assert [r["url"].rsplit("/", 1)[1] for r in http.requests] == ["search"]


def test_nobody_has_it_means_no_lyrics(client, http):
    http.respond(404, _fixture("not_found.json"))
    http.respond(404, _fixture("not_found.json"))

    assert client.lyrics(METROPOLIS) == NotFound()


def test_a_search_with_no_fitting_candidate_means_no_lyrics(client, http):
    http.respond(404)
    http.respond(200, "[]")

    assert client.lyrics(METROPOLIS) == NotFound()


def test_an_instrumental_is_reported_as_such(client, http):
    http.respond(404, _fixture("not_found.json"))
    http.respond(200, _fixture("search_yyz_instrumental.json"))

    assert client.lyrics(YYZ) == Instrumental()


_PLAIN_ONLY = '{"id": 1, "trackName": "Metropolis", "artistName": "Dream Theater", "duration": 571, "plainLyrics": "Plain words"}'


def _synced_search(synced_duration=571):
    return json.dumps(
        [
            {
                "id": 2,
                "trackName": "Metropolis - Part I",
                "artistName": "Dream Theater",
                "duration": synced_duration,
                "syncedLyrics": "[00:05.00] Timed words",
            }
        ]
    )


def test_an_exact_hit_with_only_plain_words_looks_for_a_synced_upload_first(client, http):
    """The widget needs times to follow the song: a plain-only exact hit is
    kept as the fallback, not returned before the search had its say."""
    http.respond(200, _PLAIN_ONLY)
    http.respond(200, _synced_search())

    lyrics = client.lyrics(METROPOLIS)

    assert lyrics == SyncedLyrics((TimedLine(5_000, "Timed words"),))
    assert [r["url"].rsplit("/", 1)[1] for r in http.requests] == ["get", "search"]


def test_the_plain_exact_hit_stands_when_the_search_has_nothing_synced_that_fits(client, http):
    http.respond(200, _PLAIN_ONLY)
    http.respond(200, _synced_search(synced_duration=700))  # another recording: 129 s off

    assert client.lyrics(METROPOLIS) == TextLyrics(("Plain words",))


def test_a_synced_upload_a_few_seconds_off_beats_the_plain_one_in_the_search(client, http):
    """Review of b283c43: the plain upload of the same recording is usually in
    /search too, with no drift, and outranked a synced one 20 s off (1350 vs
    1300 points). Only synced candidates compete after a plain /get."""
    http.respond(200, _PLAIN_ONLY)
    plain_again = json.loads(_PLAIN_ONLY)
    synced_off = json.loads(_synced_search(synced_duration=591))[0]  # 20 s drift
    http.respond(200, json.dumps([plain_again, synced_off]))

    assert client.lyrics(METROPOLIS) == SyncedLyrics((TimedLine(5_000, "Timed words"),))


def test_the_plain_exact_hit_stands_when_the_search_finds_nothing(client, http):
    http.respond(200, _PLAIN_ONLY)
    http.respond(200, "[]")

    assert client.lyrics(METROPOLIS) == TextLyrics(("Plain words",))


def test_the_plain_exact_hit_stands_when_the_search_fails(client, http):
    http.respond(200, _PLAIN_ONLY)
    http.fail(requests.ConnectionError("offline"))

    assert client.lyrics(METROPOLIS) == TextLyrics(("Plain words",))


def test_a_synced_exact_hit_needs_no_search(client, http):
    http.respond(200, _fixture("get_metropolis.json"))

    assert isinstance(client.lyrics(METROPOLIS), SyncedLyrics)
    assert len(http.requests) == 1


def test_a_blank_title_asks_nobody(client, http):
    assert client.lyrics(LyricsQuery("Artist", "  ", None, None)) == NotFound()
    assert http.requests == []


def test_a_server_error_is_not_the_same_as_no_lyrics(client, http):
    http.respond(503)

    with pytest.raises(LyricsUnavailableError):
        client.lyrics(METROPOLIS)


def test_an_answer_that_is_not_lrclibs_is_not_the_same_as_no_lyrics(client, http):
    http.respond(200, "<html>captive portal</html>")

    with pytest.raises(LyricsUnavailableError):
        client.lyrics(METROPOLIS)


def test_a_field_of_the_wrong_type_is_not_lrclibs_answer(client, http):
    http.respond(200, '{"trackName": 5, "plainLyrics": "x"}')

    with pytest.raises(LyricsUnavailableError):
        client.lyrics(METROPOLIS)


def test_no_network_is_not_the_same_as_no_lyrics(client, http):
    http.fail(requests.ConnectionError("offline"))

    with pytest.raises(LyricsUnavailableError):
        client.lyrics(METROPOLIS)


def test_a_timeout_is_not_the_same_as_no_lyrics(client, http):
    http.fail(requests.Timeout("slow"))

    with pytest.raises(LyricsUnavailableError):
        client.lyrics(METROPOLIS)


def test_the_words_never_reach_the_log_or_the_disk(client, http, caplog, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    caplog.set_level(logging.DEBUG)
    http.respond(404)
    http.respond(200, _fixture("search_metropolis.json"))

    client.lyrics(METROPOLIS)

    assert "Placeholder" not in caplog.text
    assert list(tmp_path.iterdir()) == []


# --- ranking -------------------------------------------------------------------


def _search_fixture(name: str):
    return [parse_record(raw) for raw in json.loads(_fixture(name))]


def test_the_search_picks_the_recording_of_the_same_length_not_a_live_or_cut_version():
    # The fixture holds 774 s and 636 s live cuts, a 233 s game edit and
    # several 572 s uploads; the 571 s one is the album track.
    assert pick(_search_fixture("search_metropolis.json"), METROPOLIS).id == 34660130


def test_a_candidate_more_than_30_s_off_is_another_recording():
    too_long = LrclibRecord(track_name="YYZ", artist_name="Rush", duration=297.0, plain_lyrics="x")
    assert pick([too_long], YYZ) is None
    no_duration = LyricsQuery(YYZ.artist, YYZ.title, YYZ.album, None)
    assert pick([too_long], no_duration) == too_long, "no duration, no drift check"


def test_the_closest_length_wins_and_synced_breaks_ties():
    query = LyricsQuery("Artist", "Song", None, 200_000)

    def record(track, duration, synced):
        return LrclibRecord(
            track_name=track,
            artist_name="Artist",
            duration=duration,
            plain_lyrics="a",
            synced_lyrics="[00:01.00] a" if synced else None,
        )

    picked = pick(
        [record("Song", 260.0, True), record("Song", 201.0, False), record("Song", 202.0, True), record("Other", 200.0, True)],
        query,
    )
    assert picked.duration == 202.0


def test_the_right_artist_outweighs_a_closer_length():
    query = LyricsQuery("Artist", "Song", None, 200_000)
    cover = LrclibRecord(track_name="Song", artist_name="Someone Else", duration=200.0, synced_lyrics="[00:01]a")
    original = LrclibRecord(track_name="Song", artist_name="Artist", duration=225.0, plain_lyrics="a")
    assert pick([cover, original], query) == original


def test_a_duet_filed_under_its_second_artist_still_counts_as_the_right_artist():
    query = LyricsQuery("Artist, Guest", "Song", None, 200_000, ("Artist", "Guest"))
    cover = LrclibRecord(track_name="Song", artist_name="Someone Else", duration=200.0, synced_lyrics="[00:01]a")
    duet = LrclibRecord(track_name="Song", artist_name="Guest", duration=225.0, plain_lyrics="a")
    assert pick([cover, duet], query) == duet


# --- what a record holds -------------------------------------------------------


def test_synced_words_come_with_their_times_when_the_record_has_them():
    record = LrclibRecord(plain_lyrics="Plain words", synced_lyrics="[00:10.00] Second\n[00:05.00] First")
    assert record.lyrics() == SyncedLyrics((TimedLine(5_000, "First"), TimedLine(10_000, "Second")))


def test_the_plain_upload_is_the_fallback_without_a_synced_one():
    record = LrclibRecord(plain_lyrics="First\n\n\nSecond\n")
    assert record.lyrics() == TextLyrics(("First", "", "Second"))


def test_a_synced_upload_with_only_breaks_falls_back_to_the_plain_one():
    record = LrclibRecord(plain_lyrics="Plain words", synced_lyrics="[00:01.00]\n[00:02.00]")
    assert record.lyrics() == TextLyrics(("Plain words",))


def test_a_record_without_words_is_nothing():
    assert LrclibRecord(plain_lyrics="  ", synced_lyrics="").lyrics() is None


def test_primary_artist_is_the_first_listed_or_the_joined_text():
    assert LyricsQuery("A, B", "t", None, None, ("A", "B")).primary_artist == "A"
    assert LyricsQuery("A, B", "t", None, None, (" ", "B")).primary_artist == "B"
    assert LyricsQuery("A, B", "t", None, None).primary_artist == "A, B"
