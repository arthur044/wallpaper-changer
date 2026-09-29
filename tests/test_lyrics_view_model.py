from src.lyrics.text import TimedLine
from src.lyrics_widget.view_model import (
    ANCHOR,
    MAX_FONT_PX,
    MIN_FONT_PX,
    Phase,
    View,
    column_tops,
    current_line,
    font_px_for_width,
    min_width,
    progress,
    scroll_for_progress,
    scroll_to_line,
)

LINES = (TimedLine(5_000, "Line one"), TimedLine(9_000, ""), TimedLine(12_000, "Line two"))


def test_no_line_is_current_before_the_first_starts():
    assert current_line(LINES, 0) is None
    assert current_line(LINES, 4_999) is None


def test_the_current_line_is_the_last_one_started():
    assert current_line(LINES, 5_000) == 0
    assert current_line(LINES, 8_999) == 0
    assert current_line(LINES, 9_000) == 1  # a break is a line too
    assert current_line(LINES, 600_000) == 2


def test_progress_is_a_share_of_the_track_within_bounds():
    assert progress(60_000, 240_000) == 0.25
    assert progress(None, 240_000) == 0.0
    assert progress(60_000, None) == 0.0
    assert progress(300_000, 240_000) == 1.0


def test_the_font_grows_with_the_width_within_bounds():
    assert font_px_for_width(100) == MIN_FONT_PX
    assert font_px_for_width(420) == 21
    assert font_px_for_width(5_000) == MAX_FONT_PX
    assert font_px_for_width(500) >= font_px_for_width(400)


def test_the_minimum_width_fits_the_chosen_characters_plus_padding():
    assert min_width(7.0, chars=24, padding=20) == 7 * 24 + 40


def test_lines_are_stacked_with_spacing_between():
    assert column_tops([20.0, 10.0, 40.0], spacing=5.0) == [0.0, 25.0, 40.0]


def test_the_current_line_is_scrolled_to_the_anchor():
    tops, heights = [0.0, 30.0, 60.0], [20.0, 20.0, 20.0]
    view_height = 100.0

    scroll = scroll_to_line(tops, heights, 2, view_height)

    # Its middle, once scrolled, sits at the anchor.
    assert tops[2] + heights[2] / 2 - scroll == view_height * ANCHOR


def test_before_the_first_line_it_waits_at_the_anchor():
    tops, heights = [0.0, 30.0], [20.0, 20.0]
    assert scroll_to_line(tops, heights, None, 100.0) == scroll_to_line(tops, heights, 0, 100.0)


def test_scroll_to_line_is_safe_with_nothing_or_an_index_past_the_end():
    assert scroll_to_line([], [], 3, 100.0) == 0.0
    assert scroll_to_line([0.0], [20.0], 7, 100.0) == scroll_to_line([0.0], [20.0], 0, 100.0)


def test_unsynced_words_scroll_from_the_top_to_the_bottom_with_the_track():
    assert scroll_for_progress(500.0, 100.0, 0.0) == 0.0
    assert scroll_for_progress(500.0, 100.0, 0.5) == 200.0
    assert scroll_for_progress(500.0, 100.0, 1.0) == 400.0
    assert scroll_for_progress(80.0, 100.0, 0.7) == 0.0, "words that fit don't scroll"


def test_messages_belong_to_the_states_without_lines():
    assert View(Phase.NO_LYRICS).message
    assert View(Phase.LOADING).message
    assert View(Phase.SYNCED, ("a",), current=0).message is None
    assert View(Phase.HIDDEN).message is None


def test_only_unsynced_words_carry_the_not_synced_badge():
    assert View(Phase.TEXT, ("a",), progress=0.3).badge == "Not synced"
    for phase in Phase:
        if phase != Phase.TEXT:
            assert View(phase).badge is None, phase
