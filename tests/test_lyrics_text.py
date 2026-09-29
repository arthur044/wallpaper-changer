"""Ported from the Android LyricsTextTest (itself spotifast's src/lyrics.rs
tests), plus the LRC times the desktop widget keeps."""

from src.lyrics.text import TimedLine, clean_artist, clean_title, loose_match, lrc_text, parse_lrc, tidy


def test_a_featuring_is_cut_where_it_starts_whatever_lower_casing_costs():
    # Turkish 'İ' lower-cases to two characters ('i' + combining dot), so an
    # offset found in the lower-cased copy points one too far in the original.
    assert clean_title("İİ feat. Someone") == "İİ"
    assert clean_artist("İzel feat. Someone") == "İzel"
    assert clean_title("ẞ café feat. Someone") == "ẞ café"
    assert clean_title("\U0001f3b5 Song ft. Someone") == "\U0001f3b5 Song"

    assert clean_title("Song feat. Someone") == "Song"
    assert clean_title("Song ft. Someone") == "Song"
    assert clean_artist("Artist featuring Other") == "Artist"


def test_titles_lose_what_a_database_leaves_out():
    assert clean_title("Song (Remastered 2011)") == "Song"
    assert clean_title("Song - Live at Wembley") == "Song"
    assert clean_title("Song - 2009 Remaster") == "Song"
    assert clean_title("Song - 2011 Version") == "Song"
    assert clean_title("Song (feat. Someone)") == "Song"
    assert clean_title("Song [Radio Edit]") == "Song"
    assert clean_title("Song (Part One)") == "Song (Part One)"
    assert clean_title("Hyphen - Ated") == "Hyphen - Ated"
    assert clean_title("(Remastered)") == "(Remastered)", "never empty"
    assert clean_title("Feat​ure") == "Feature"
    assert clean_title("Left Behind") == "Left Behind", "a 'ft' inside a word is no marker"


def test_a_title_with_quotes_and_a_colon_is_kept_whole():
    title = 'Metropolis - Part I: "The Miracle and the Sleeper"'
    assert clean_title(title) == title


def test_artists_keep_their_first_name():
    assert clean_artist("TOOL;Tool") == "TOOL"
    assert clean_artist("Artist feat. Guest") == "Artist"
    assert clean_artist("Artist (feat. Guest)") == "Artist"
    assert clean_artist("Beyoncé") == "Beyoncé"


def test_matching_is_loose_about_case_accents_and_punctuation():
    assert loose_match("Beyoncé", "beyonce")
    assert loose_match("Rock & Roll", "rock and roll")
    assert loose_match("Don't Stop", "dont stop")
    assert loose_match("Song (Live)", "Song")
    assert not loose_match("Something", "Else")
    assert not loose_match("", "Else")
    assert not loose_match("!!!", "Else"), "punctuation alone normalizes to nothing"


def test_lrclib_spellings_of_metropolis_all_match_the_spotify_title():
    spotify = clean_title('Metropolis - Part I: "The Miracle and the Sleeper"')
    for spelling in (
        "Metropolis, Part I: The Miracle and the Sleeper",
        "Metropolis—Part I “The Miracle and the Sleeper”",
        "Metropolis - Part I (The Miracle And The Sleeper)",
    ):
        assert loose_match(spelling, spotify), spelling


def test_lrc_lines_lose_their_stamps_and_come_in_time_order():
    text = lrc_text("[ar:Someone]\n[00:12.50]First\n[00:05]Early\n[01:00.1][02:00.123]Twice\n\nNo stamp\n")
    assert text == ["Early", "First", "Twice", "Twice"]


def test_lrc_times_are_kept_in_milliseconds():
    lines = parse_lrc("[00:12.50]First\n[00:05]Early\n[01:00.1][02:00.123]Twice\n[03:07:25]Colon\n")
    assert lines == [
        TimedLine(5_000, "Early"),
        TimedLine(12_500, "First"),
        TimedLine(60_100, "Twice"),
        TimedLine(120_123, "Twice"),
        TimedLine(187_250, "Colon"),
    ]


def test_lrc_fractions_longer_than_milliseconds_are_cut_not_rounded():
    assert parse_lrc("[00:01.9999]Words") == [TimedLine(1_999, "Words")]


def test_an_empty_timed_line_is_kept_as_a_break():
    lines = parse_lrc("[00:01.00] Words\r\n[00:04.00]\r\n[00:06.00] More words")
    assert lines == [TimedLine(1_000, "Words"), TimedLine(4_000, ""), TimedLine(6_000, "More words")]


def test_lines_stamped_at_the_same_time_keep_their_file_order():
    assert lrc_text("[00:02]B\n[00:01]A\n[00:02]C") == ["A", "B", "C"]


def test_tidy_keeps_one_blank_line_between_stanzas_and_none_at_the_ends():
    assert tidy(["", "a  ", "", "  ", "", "b", "c", "", ""]) == ["a", "", "b", "c"]
