"""Ported from the Android LyricsSlotTest; threads stand in for coroutines."""

import threading

import pytest

from src.lyrics.lrclib import LyricsQuery, LyricsUnavailableError, NotFound, TextLyrics
from src.lyrics.slot import LyricsSlot

AIRBAG = LyricsQuery("Radiohead", "Airbag", "OK Computer", 287_000)
LUCKY = LyricsQuery("Radiohead", "Lucky", "OK Computer", 259_000)
WORDS = TextLyrics(("Placeholder line 1",))


class _FakeSource:
    def __init__(self):
        self.calls = 0
        self.answer = lambda: NotFound()

    def __call__(self, query):
        self.calls += 1
        return self.answer()


@pytest.fixture
def source():
    return _FakeSource()


@pytest.fixture
def slot(source):
    return LyricsSlot(source)


def _gated(source):
    """The source blocks until [release] is set; [asked] is set once it's called."""
    asked, release = threading.Event(), threading.Event()

    def answer():
        asked.set()
        assert release.wait(timeout=5.0)
        return WORDS

    source.answer = answer
    return asked, release


def test_an_answer_is_kept_for_its_track_no_lyrics_included(slot, source):
    assert slot.lyrics_for("t1", AIRBAG) == NotFound()
    assert slot.lyrics_for("t1", AIRBAG) == NotFound()

    assert source.calls == 1
    assert slot.peek("t1") == NotFound()


def test_another_track_drops_the_kept_answer(slot, source):
    source.answer = lambda: WORDS
    slot.lyrics_for("t1", AIRBAG)

    slot.on_track("t2")
    assert slot.peek("t1") is None

    # Back to the first track: only one position, so it is asked again.
    slot.lyrics_for("t2", LUCKY)
    slot.lyrics_for("t1", AIRBAG)
    assert source.calls == 3


def test_the_same_track_showing_again_keeps_the_answer(slot, source):
    source.answer = lambda: WORDS
    slot.lyrics_for("t1", AIRBAG)

    slot.on_track("t1")

    assert slot.peek("t1") == WORDS


def test_a_failed_lookup_is_not_kept_so_the_next_ask_tries_again(slot, source):
    def offline():
        raise LyricsUnavailableError("offline")

    source.answer = offline
    with pytest.raises(LyricsUnavailableError):
        slot.lyrics_for("t1", AIRBAG)
    assert slot.peek("t1") is None

    source.answer = lambda: WORDS
    assert slot.lyrics_for("t1", AIRBAG) == WORDS
    assert source.calls == 2


def test_an_answer_arriving_after_the_track_changed_is_not_kept(slot, source):
    asked, release = _gated(source)
    results = []
    late = threading.Thread(target=lambda: results.append(slot.lyrics_for("t1", AIRBAG)))
    late.start()
    assert asked.wait(timeout=5.0)

    slot.on_track("t2")
    release.set()
    late.join(timeout=5.0)

    assert results == [WORDS], "its caller still gets it"
    assert slot.peek("t1") is None
    assert slot.peek("t2") is None


def test_asking_twice_while_the_first_lookup_is_out_makes_one_call(slot, source):
    asked, release = _gated(source)
    results = []
    first = threading.Thread(target=lambda: results.append(slot.lyrics_for("t1", AIRBAG)))
    first.start()
    assert asked.wait(timeout=5.0)
    # The second ask is made while the first lookup is still out: it can only
    # be answered by waiting for it, since nothing is kept yet.
    second = threading.Thread(target=lambda: results.append(slot.lyrics_for("t1", AIRBAG)))
    second.start()

    release.set()
    first.join(timeout=5.0)
    second.join(timeout=5.0)

    assert results == [WORDS, WORDS]
    assert source.calls == 1


def test_a_track_with_nothing_to_look_up_by_asks_nobody(slot, source):
    assert slot.lyrics_for(None, AIRBAG) == NotFound()
    assert slot.lyrics_for("t1", None) == NotFound()
    assert source.calls == 0
