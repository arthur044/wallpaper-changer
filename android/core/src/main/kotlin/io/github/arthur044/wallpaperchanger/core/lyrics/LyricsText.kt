package io.github.arthur044.wallpaperchanger.core.lyrics

import java.util.Locale

// Name cleanup and matching for the lyrics lookup, ported from spotifast's
// src/lyrics.rs (crmne/spotifast). Offsets there are UTF-8 bytes, here UTF-16
// units; the one place where that matters (lower casing that changes length)
// maps offsets back the same way.

/** Words a title carries in brackets that a lyrics database does not. */
private val BRACKET_NOISE = listOf(
    "remaster", "remastered", "remix", "live", "acoustic", "version", "edit", "mix", "mono",
    "stereo", "deluxe", "bonus", "expanded", "explicit", "anniversary", "feat", "featuring", "with",
)

/** What a " - " suffix says when it is not part of the title. */
private val SUFFIX_NOISE = listOf(
    "remaster", "remastered", "radio edit", "single version", "album version", "live", "mono",
    "stereo", "rerecorded", "re-recorded",
)

private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}-]+")
private val WHITESPACE = Regex("\\s+")

/**
 * Invisible characters that carry no meaning: a zero-width space, the word
 * joiner and the invisible operators, and a byte order mark. Not the
 * zero-width joiner or non-joiner, which hold emoji sequences and Indic and
 * Persian words together.
 */
private fun isDisposable(c: Char): Boolean = c == '​' || c in '⁠'..'⁤' || c == '﻿'

/** Whether [text] contains [phrase] as whole words, case-insensitively. */
private fun hasPhrase(text: String, phrase: String): Boolean {
    val words = text.lowercase(Locale.ROOT).split(NOT_A_WORD).filter { it.isNotEmpty() }.map { it.trim('-') }
    val wanted = phrase.split(' ')
    return words.windowed(wanted.size).any { it == wanted }
}

/**
 * Strips what a player adds to a title and a database leaves out:
 * "(Remastered 2011)", " - Live at Wembley", "feat. Someone". Never empty.
 */
fun cleanTitle(title: String): String {
    val original = title.filterNot(::isDisposable)
    val cleaned = StringBuilder(original.length)
    var rest = original
    while (true) {
        val open = rest.indexOfAny(charArrayOf('(', '['))
        if (open < 0) break
        val close = rest.indexOf(if (rest[open] == '(') ')' else ']', open)
        if (close < 0) break
        val inner = rest.substring(open + 1, close)
        if (BRACKET_NOISE.any { hasPhrase(inner, it) }) {
            cleaned.append(rest.substring(0, open).trimEnd())
        } else {
            cleaned.append(rest, 0, close + 1)
        }
        rest = rest.substring(close + 1)
    }
    cleaned.append(rest)
    val trimmed = cutNoisySuffix(cleaned.toString().trim())
    return stripFeaturing(trimmed.trim()).ifEmpty { original.trim() }
}

// " - Remastered 2009" and friends, from the first dash whose tail is noise;
// a dash inside a real title stays.
private fun cutNoisySuffix(title: String): String {
    var dash = title.indexOf(" - ")
    while (dash >= 0) {
        val tail = title.substring(dash + 3)
        val firstWord = tail.trim().split(WHITESPACE).firstOrNull().orEmpty()
        val yearVersion = firstWord.length == 4 && firstWord.all { it in '0'..'9' } && hasPhrase(tail, "version")
        if (SUFFIX_NOISE.any { hasPhrase(tail, it) } || yearVersion) return title.substring(0, dash)
        dash = title.indexOf(" - ", dash + 3)
    }
    return title
}

/** Everything from a standalone "feat", "ft", or "featuring" on. */
internal fun stripFeaturing(text: String): String {
    // The marker is looked for in a lower-cased copy and the cut is made in
    // [text], and lower casing does not preserve length: Turkish 'İ' becomes
    // 'i' plus a combining dot. [starts] carries every offset in the copy back
    // to the same place in [text], so no offset is used in the string it was
    // not measured in.
    val lower = StringBuilder(text.length)
    val starts = ArrayList<Int>(text.length + 1)
    var at = 0
    while (at < text.length) {
        val next = at + Character.charCount(text.codePointAt(at))
        val lowered = text.substring(at, next).lowercase(Locale.ROOT)
        repeat(lowered.length) { starts += at }
        lower.append(lowered)
        at = next
    }
    starts += text.length
    val copy = lower.toString()
    var cut: Int? = null
    for (marker in listOf("featuring", "feat", "ft")) {
        var from = 0
        while (true) {
            val start = copy.indexOf(marker, from)
            if (start < 0) break
            val end = start + marker.length
            val before = copy.substring(0, start).trimEnd('-', '(').trimEnd()
            val preceded = before.length < start && before.isNotEmpty()
            val followed = copy.substring(end).removePrefix(".").startsWith(' ')
            if (preceded && followed) {
                cut = minOf(cut ?: before.length, before.length)
                break
            }
            from = end
        }
    }
    return cut?.let { text.substring(0, starts[it]).trim() } ?: text.trim()
}

/**
 * Players report collaborations in ways a database does not file them, and
 * LRCLIB itself sometimes stores "TOOL;Tool" for one artist. Never empty.
 */
fun cleanArtist(artist: String): String {
    val original = artist.filterNot(::isDisposable)
    return stripFeaturing(original).substringBefore(';').trim().ifEmpty { original.trim() }
}

/** Lowercase, accents folded, punctuation gone, one space between words. */
internal fun normalize(text: String): String {
    val out = StringBuilder(text.length)
    var space = false
    fun push(c: Int) {
        if (Character.isLetterOrDigit(c)) {
            out.append(String(Character.toChars(c)).lowercase(Locale.ROOT))
            space = false
        } else if (!space) {
            out.append(' ')
            space = true
        }
    }
    text.codePoints().forEach { raw ->
        when (val c = fold(raw)) {
            '\''.code, '’'.code, '`'.code -> Unit
            '&'.code -> " and ".forEach { push(it.code) }
            else -> push(c)
        }
    }
    return out.toString().trim()
}

/** The plain letter behind the Latin accents titles most often carry. */
private fun fold(c: Int): Int {
    if (c > 0xFF) return c
    val plain = when (c.toChar()) {
        in 'À'..'Å', in 'à'..'å' -> 'a'
        'Ç', 'ç' -> 'c'
        in 'È'..'Ë', in 'è'..'ë' -> 'e'
        in 'Ì'..'Ï', in 'ì'..'ï' -> 'i'
        'Ñ', 'ñ' -> 'n'
        in 'Ò'..'Ö', 'Ø', in 'ò'..'ö', 'ø' -> 'o'
        in 'Ù'..'Ü', in 'ù'..'ü' -> 'u'
        'Ý', 'ý', 'ÿ' -> 'y'
        'ß' -> 's'
        else -> return c
    }
    return plain.code
}

/** Same words after [normalize], or one containing the other. */
internal fun looseMatch(left: String, right: String): Boolean {
    val a = normalize(left)
    val b = normalize(right)
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || a.contains(b) || b.contains(a)
}

private val STAMP = Regex("""^\[(\d+):(\d+)(?:[.:](\d+))?]""")

/**
 * The words of LRC-synced lyrics in time order, stamps dropped. A line may open
 * with several stamps when the same words repeat; tags such as `[ar:...]` carry
 * no digits and are skipped.
 */
internal fun lrcText(lrc: String): List<String> {
    val timed = mutableListOf<Pair<Int, String>>()
    for (raw in lrc.lines()) {
        var rest = raw.trimStart()
        val times = mutableListOf<Int>()
        while (true) {
            val stamp = STAMP.find(rest) ?: break
            val (minutes, seconds, fraction) = stamp.destructured
            val fractionMs = if (fraction.isEmpty()) 0 else fraction.take(3).padEnd(3, '0').toInt()
            times += minutes.toInt() * 60_000 + seconds.toInt() * 1_000 + fractionMs
            rest = rest.substring(stamp.value.length)
        }
        val body = rest.trim()
        times.forEach { timed += it to body }
    }
    return timed.sortedBy { it.first }.map { it.second }
}
