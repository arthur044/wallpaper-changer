package io.github.arthur044.wallpaperchanger.core.share

/** Consecutive lyrics lines, [first] to [last] inclusive; never starts or ends on a blank line. */
data class VerseSelection(val first: Int, val last: Int) {
    init {
        require(first in 0..last) { "bad selection $first..$last" }
    }

    val range: IntRange get() = first..last

    fun of(lines: List<String>): List<String> = lines.subList(first, last + 1)
}

/** What a tap did: the new selection (null = none) and whether a longer one was refused. */
data class TapResult(val selection: VerseSelection?, val refused: Boolean = false)

/**
 * The selection after tapping line [index] of [lines]:
 * - a blank line (a stanza break) can't be tapped;
 * - nothing selected: that line;
 * - the next verse after either end (across blank lines): the selection grows;
 * - either end: the selection shrinks by that line (nothing left: none);
 * - anywhere else: a new selection of that line.
 * A selection that [fits] refuses (too much for the card) is not taken.
 */
fun VerseSelection?.tap(index: Int, lines: List<String>, fits: (VerseSelection) -> Boolean): TapResult {
    if (index !in lines.indices || lines[index].isBlank()) return TapResult(this)
    val current = this ?: return offer(VerseSelection(index, index), null, fits)
    return when {
        current.first == current.last && index == current.first -> TapResult(null)
        index == current.first -> TapResult(current.copy(first = nextVerse(lines, index + 1, step = 1)))
        index == current.last -> TapResult(current.copy(last = nextVerse(lines, index - 1, step = -1)))
        index < current.first && blankBetween(lines, index + 1, current.first) ->
            offer(current.copy(first = index), current, fits)
        index > current.last && blankBetween(lines, current.last + 1, index) ->
            offer(current.copy(last = index), current, fits)
        else -> offer(VerseSelection(index, index), current, fits)
    }
}

private fun offer(wanted: VerseSelection, current: VerseSelection?, fits: (VerseSelection) -> Boolean): TapResult =
    if (fits(wanted)) TapResult(wanted) else TapResult(current, refused = true)

// Whether every line in [from, until) is blank (true when there are none).
private fun blankBetween(lines: List<String>, from: Int, until: Int): Boolean =
    (from until until).all { lines[it].isBlank() }

// The first non-blank line from [from] going [step]; the selection's other end stops it.
private fun nextVerse(lines: List<String>, from: Int, step: Int): Int {
    var i = from
    while (lines[i].isBlank()) i += step
    return i
}
