package app.sterna.ui.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.Normalizer
import java.util.Locale

/**
 * The words of the bar a result line is highlighted with, cut by the same rule as the local
 * index's query (`ftsTokens` in `LocalSearchMatch`): runs of letters and digits. The sheet's From
 * and Subject fields are not in it, they match the sender and subject lines, not the preview.
 */
fun searchTerms(typed: String): List<String> =
    typed.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

/**
 * The ranges of [text] that match one of [terms], case and accents folded, overlaps merged, in
 * order. The ranges index [text] itself: folding is done one code point at a time and every folded
 * character remembers the code point it came from, because NFD changes lengths ("é" is two chars
 * once decomposed) and a match found in the folded string would otherwise land shifted.
 */
fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
    val (haystack, origin) = foldWithOrigins(text)
    val found = terms.map { foldWithOrigins(it).first }.filter { it.isNotBlank() }.flatMap { needle ->
        generateSequence(haystack.indexOf(needle).takeIf { it >= 0 }) { from ->
            haystack.indexOf(needle, from + 1).takeIf { it >= 0 }
        }.map { start ->
            // Up to the next folded character's origin, so a combining mark that folded to
            // nothing, in text already decomposed, stays with the letter it sits on.
            val end = start + needle.length
            origin[start] until (if (end < origin.size) origin[end] else text.length)
        }.toList()
    }.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (range in found) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/** [text] with every match of [terms] ([highlightRanges]) drawn in [style]. */
fun highlightTerms(text: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        highlightRanges(text, terms).forEach { addStyle(style, it.first, it.last + 1) }
    }
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * [value] folded one code point at a time, with the index in [value] each folded char comes from.
 * Text and terms both go through here: folded whole, a word ending in "Σ" takes the final "ς" and
 * no longer meets the same word folded letter by letter.
 */
private fun foldWithOrigins(value: String): Pair<String, List<Int>> {
    val folded = StringBuilder()
    val origin = ArrayList<Int>()
    var at = 0
    while (at < value.length) {
        val next = value.offsetByCodePoints(at, 1)
        val piece = fold(value.substring(at, next))
        folded.append(piece)
        repeat(piece.length) { origin += at }
        at = next
    }
    return folded.toString() to origin
}

/**
 * Root locale: under a Turkish locale "I" would fold to a dotless "ı" and miss "invoice". "ς" is
 * the same letter as "σ", written at the end of a word, so a typed "οδος" must meet "ΟΔΟΣ".
 */
private fun fold(codePoint: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(codePoint, Normalizer.Form.NFD).lowercase(Locale.ROOT), "")
        .replace('ς', 'σ')
