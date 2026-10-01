package app.sterna.core.data.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** How many searches are kept. */
const val SEARCH_HISTORY_LIMIT = 20

/** How many are offered when nothing is typed yet. */
const val SUGGESTIONS_WHEN_EMPTY = 8

/** How many are offered while typing, above the live results. */
const val SUGGESTIONS_WHILE_TYPING = 3

/**
 * [history] after the search [typed]: the text trimmed, at the head, and any earlier entry that
 * differs from it only by case or accents dropped, so a search moves up with its latest spelling
 * instead of being kept twice. A blank search changes nothing. Text only, never the advanced
 * screen's filters: their dates are absolute bounds that mean nothing replayed a week later.
 */
fun withSearch(history: List<String>, typed: String): List<String> {
    val text = typed.trim()
    if (text.isEmpty()) return history
    val key = folded(text)
    return (listOf(text) + history.filterNot { folded(it) == key }).take(SEARCH_HISTORY_LIMIT)
}

/**
 * [history] without [entry], matched folded exactly as [withSearch] folds, so the cross on a
 * suggestion also takes away a kept spelling that differs only by case or accents: [withSearch]
 * never keeps two of those, and one left behind would come straight back as the same suggestion.
 * The others keep their order. A blank or absent entry changes nothing.
 */
fun withoutSearch(history: List<String>, entry: String): List<String> {
    val removed = folded(entry.trim())
    if (removed.isEmpty()) return history
    return history.filterNot { kept -> folded(kept) == removed }
}

/**
 * What to offer under a search field holding [typed]: the most recent searches when it is blank,
 * otherwise the few in which a word starts with what is typed, case and accents ignored. The
 * search being typed is not offered back to itself.
 */
fun searchHistorySuggestions(history: List<String>, typed: String): List<String> {
    val text = typed.trim()
    if (text.isEmpty()) return history.take(SUGGESTIONS_WHEN_EMPTY)
    val key = folded(text)
    return history
        .filter { entry -> folded(entry).let { it != key && startsAWord(it, key) } }
        .take(SUGGESTIONS_WHILE_TYPING)
}

/** True when [prefix] occurs in [text] at its start or right after a character that is not part
 *  of a word. Several typed words therefore match across the words of an entry. */
private fun startsAWord(text: String, prefix: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(prefix, from)
        if (at < 0) return false
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        from = at + 1
    }
}

// NFD then combining marks dropped: "é" and "e" compare equal, and so do the two spellings of a
// precomposed and a decomposed "é" a keyboard may produce. ROOT, or a Turkish locale lowercases
// "I" to a dotless "ı" and "FIX I" stops matching "fix i".
private fun folded(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

private val listCodec = ListSerializer(String.serializer())

// JSON rather than a string set: a set loses the order, which is the whole list here, and any
// separator character could be part of a search.
internal fun encodeSearchHistory(history: List<String>): String = Json.encodeToString(listCodec, history)

/** The stored list, or none when [stored] is absent or not a list of strings. */
internal fun decodeSearchHistory(stored: String?): List<String> =
    stored?.let { runCatching { Json.decodeFromString(listCodec, it) }.getOrNull() }.orEmpty()
