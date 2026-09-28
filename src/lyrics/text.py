"""Name cleanup, matching and LRC parsing for the lyrics lookup.

Ported from the Android app's core/lyrics/LyricsText.kt (itself from
spotifast's src/lyrics.rs). Python indexes strings by code point, so only the
one length change that still matters (lower casing) needs offsets mapped back.
"""

import re
from dataclasses import dataclass
from typing import List, Tuple

# Words a title carries in brackets that a lyrics database does not.
_BRACKET_NOISE = (
    "remaster", "remastered", "remix", "live", "acoustic", "version", "edit", "mix", "mono",
    "stereo", "deluxe", "bonus", "expanded", "explicit", "anniversary", "feat", "featuring", "with",
)

# What a " - " suffix says when it is not part of the title.
_SUFFIX_NOISE = (
    "remaster", "remastered", "radio edit", "single version", "album version", "live", "mono",
    "stereo", "rerecorded", "re-recorded",
)

# Anything but letters, digits and '-' (\w also takes '_', so it goes too).
_NOT_A_WORD = re.compile(r"(?:[^\w-]|_)+")
_LINE_BREAK = re.compile(r"\r\n|\r|\n")

# A zero-width space, the word joiner and the invisible operators, and a byte
# order mark. Not the zero-width joiner or non-joiner, which hold emoji
# sequences and Indic and Persian words together.
_DISPOSABLE = {"​", "⁠", "⁡", "⁢", "⁣", "⁤", "﻿"}


def _drop_disposable(text: str) -> str:
    return "".join(c for c in text if c not in _DISPOSABLE)


def split_lines(text: str) -> List[str]:
    """Kotlin's lines(): only \\r\\n, \\r and \\n break a line (splitlines()
    also breaks on form feeds and Unicode separators)."""
    return _LINE_BREAK.split(text)


def _has_phrase(text: str, phrase: str) -> bool:
    """Whether [text] contains [phrase] as whole words, case-insensitively."""
    words = [w.strip("-") for w in _NOT_A_WORD.split(text.lower()) if w]
    wanted = phrase.split(" ")
    size = len(wanted)
    return any(words[i:i + size] == wanted for i in range(len(words) - size + 1))


def clean_title(title: str) -> str:
    """Strips what a player adds to a title and a database leaves out:
    "(Remastered 2011)", " - Live at Wembley", "feat. Someone". Never empty."""
    original = _drop_disposable(title)
    cleaned = []
    rest = original
    while True:
        opens = [i for i in (rest.find("("), rest.find("[")) if i >= 0]
        if not opens:
            break
        open_at = min(opens)
        close_at = rest.find(")" if rest[open_at] == "(" else "]", open_at)
        if close_at < 0:
            break
        inner = rest[open_at + 1:close_at]
        if any(_has_phrase(inner, noise) for noise in _BRACKET_NOISE):
            cleaned.append(rest[:open_at].rstrip())
        else:
            cleaned.append(rest[:close_at + 1])
        rest = rest[close_at + 1:]
    cleaned.append(rest)
    trimmed = _cut_noisy_suffix("".join(cleaned).strip())
    return strip_featuring(trimmed.strip()) or original.strip()


def _cut_noisy_suffix(title: str) -> str:
    # " - Remastered 2009" and friends, from the first dash whose tail is
    # noise; a dash inside a real title stays.
    dash = title.find(" - ")
    while dash >= 0:
        tail = title[dash + 3:]
        words = tail.strip().split()
        first_word = words[0] if words else ""
        year_version = len(first_word) == 4 and all("0" <= c <= "9" for c in first_word) and _has_phrase(tail, "version")
        if year_version or any(_has_phrase(tail, noise) for noise in _SUFFIX_NOISE):
            return title[:dash]
        dash = title.find(" - ", dash + 3)
    return title


def strip_featuring(text: str) -> str:
    """Everything from a standalone "feat", "ft", or "featuring" on."""
    # The marker is looked for in a lower-cased copy and the cut is made in
    # [text], and lower casing does not preserve length: Turkish 'İ' becomes
    # 'i' plus a combining dot. [starts] carries every offset in the copy back
    # to the same place in [text].
    lowered = []
    starts = []
    for at, char in enumerate(text):
        low = char.lower()
        starts.extend([at] * len(low))
        lowered.append(low)
    starts.append(len(text))
    copy = "".join(lowered)

    cut = None
    for marker in ("featuring", "feat", "ft"):
        search_from = 0
        while True:
            start = copy.find(marker, search_from)
            if start < 0:
                break
            end = start + len(marker)
            before = copy[:start].rstrip("-(").rstrip()
            preceded = len(before) < start and before != ""
            after = copy[end:]
            followed = (after[1:] if after.startswith(".") else after).startswith(" ")
            if preceded and followed:
                cut = len(before) if cut is None else min(cut, len(before))
                break
            search_from = end
    if cut is None:
        return text.strip()
    return text[:starts[cut]].strip()


def clean_artist(artist: str) -> str:
    """Players report collaborations in ways a database does not file them,
    and LRCLIB itself sometimes stores "TOOL;Tool" for one artist. Never empty."""
    original = _drop_disposable(artist)
    return strip_featuring(original).split(";", 1)[0].strip() or original.strip()


def _fold_table() -> dict:
    table = {}

    def add(chars: str, plain: str) -> None:
        for c in chars:
            table[c] = plain

    add("ÀÁÂÃÄÅàáâãäå", "a")
    add("Çç", "c")
    add("ÈÉÊËèéêë", "e")
    add("ÌÍÎÏìíîï", "i")
    add("Ññ", "n")
    add("ÒÓÔÕÖØòóôõöø", "o")
    add("ÙÚÛÜùúûü", "u")
    add("Ýýÿ", "y")
    add("ß", "s")
    return table


# The plain letter behind the Latin accents titles most often carry.
_FOLD = _fold_table()


def normalize(text: str) -> str:
    """Lowercase, accents folded, punctuation gone, one space between words."""
    out = []
    space = False

    def push(c: str) -> None:
        nonlocal space
        if c.isalnum():
            out.append(c.lower())
            space = False
        elif not space:
            out.append(" ")
            space = True

    for raw in text:
        c = _FOLD.get(raw, raw)
        if c in ("'", "’", "`"):
            continue
        if c == "&":
            for part in " and ":
                push(part)
        else:
            push(c)
    return "".join(out).strip()


def loose_match(left: str, right: str) -> bool:
    """Same words after [normalize], or one containing the other. Containment is
    by characters, not whole words ("love" is in "lovesong"), as on Android and
    in spotifast. What makes a wrong hit unlikely is the rest of the score: the
    right artist is +1000 and a length more than 30 s off rules it out."""
    a = normalize(left)
    b = normalize(right)
    if not a or not b:
        return False
    return a == b or b in a or a in b


def tidy(lines: List[str]) -> List[str]:
    """Trailing spaces gone, one blank line between stanzas, none at either end."""
    out: List[str] = []
    for line in (raw.rstrip() for raw in lines):
        blank = not line.strip()
        if blank and (not out or out[-1] == ""):
            continue
        out.append("" if blank else line)
    if out and out[-1] == "":
        out.pop()
    return out


@dataclass(frozen=True)
class TimedLine:
    """One LRC line: when it starts (ms from the start of the track) and its
    words. Empty words are an instrumental break."""

    time_ms: int
    text: str


_STAMP = re.compile(r"^\[([0-9]+):([0-9]+)(?:[.:]([0-9]+))?\]")


def parse_lrc(lrc: str) -> List[TimedLine]:
    """The lines of LRC-synced lyrics in time order. A line may open with
    several stamps when the same words repeat; tags such as [ar:...] carry no
    digits and are skipped, as are lines with no stamp. Same rules as the
    Android lrcText, keeping the times it drops."""
    timed: List[Tuple[int, str]] = []
    for raw in split_lines(lrc):
        rest = raw.lstrip()
        times = []
        while True:
            stamp = _STAMP.match(rest)
            if stamp is None:
                break
            minutes, seconds, fraction = stamp.groups()
            fraction_ms = int(fraction[:3].ljust(3, "0")) if fraction else 0
            times.append(int(minutes) * 60_000 + int(seconds) * 1_000 + fraction_ms)
            rest = rest[stamp.end():]
        body = rest.strip()
        timed.extend((time_ms, body) for time_ms in times)
    # sorted() is stable: words stamped at the same time keep their file order.
    return [TimedLine(time_ms, text) for time_ms, text in sorted(timed, key=lambda pair: pair[0])]


def lrc_text(lrc: str) -> List[str]:
    """The words of LRC-synced lyrics in time order, stamps dropped."""
    return [line.text for line in parse_lrc(lrc)]
