package app.sterna.core.data.text

/**
 * The model of a rich body (#131): plain text plus four inline style families, each a set of
 * normalised half-open spans. Pure Kotlin, no Compose: the composer maps its `TextFieldValue`
 * onto this and back, and the HTML side is a closed subset (`b i u s br` + a few entities) so a
 * stored draft can be read back exactly, or refused.
 */

/** An inline style family. Declaration order is the canonical nesting order in HTML: b > i > u > s. */
enum class Inline { BOLD, ITALIC, UNDERLINE, STRIKE }

/** A half-open character range `[start, end)`. Unlike Kotlin's `IntRange`, `end` is exclusive. */
data class Span(val start: Int, val end: Int) {
    init { require(start <= end) { "Span start $start > end $end" } }
    val isEmpty: Boolean get() = start == end
    val length: Int get() = end - start

    companion object {
        /** A span from two positions in either order (a selection may be reversed). */
        fun between(a: Int, b: Int): Span = Span(minOf(a, b), maxOf(a, b))
    }
}

/**
 * Plain [text] plus, per style family, the spans it covers. [ranges] is always normalised:
 * clamped to the text, non-empty, sorted, merged when adjacent or overlapping, and a family
 * without spans is absent. Two bodies with the same text and the same styling are `equals`.
 */
class RichBody(val text: String, ranges: Map<Inline, List<Span>>) {
    val ranges: Map<Inline, List<Span>> = normalise(text.length, ranges)

    val isPlain: Boolean get() = ranges.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is RichBody && other.text == text && other.ranges == ranges
    override fun hashCode(): Int = 31 * text.hashCode() + ranges.hashCode()
    override fun toString(): String = "RichBody(text=$text, ranges=$ranges)"

    companion object {
        fun plain(text: String): RichBody = RichBody(text, emptyMap())
        fun of(text: String, ranges: Map<Inline, List<Span>>): RichBody = RichBody(text, ranges)

        private fun normalise(length: Int, ranges: Map<Inline, List<Span>>): Map<Inline, List<Span>> =
            Inline.entries.mapNotNull { kind ->
                val spans = normaliseSpans(length, ranges[kind].orEmpty())
                if (spans.isEmpty()) null else kind to spans
            }.toMap()

        private fun normaliseSpans(length: Int, spans: List<Span>): List<Span> {
            val sorted = spans
                .map { Span(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }
                .filter { !it.isEmpty }
                .sortedBy { it.start }
            val out = ArrayList<Span>(sorted.size)
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.start <= last.end) {
                    out[out.size - 1] = Span(last.start, maxOf(last.end, s.end))
                } else {
                    out.add(s)
                }
            }
            return out
        }
    }
}

// --- editing -----------------------------------------------------------------------------------

/**
 * Carry [old]'s styling over to [newText] after one edit, whatever produced it (a keystroke, a
 * paste, an autocorrect). The edit is found by diffing the texts: one replaced segment
 * `[a, b)` of the old text became `[a, a + n)` of the new one. Spans before it stay, spans after
 * it shift, a span cut by the removal is trimmed, one buried in it disappears. An insertion is
 * absorbed by a span that contains the point or ends exactly there (Gmail's "sticky" style), not
 * by one that starts there. A non-null [pending], EVEN EMPTY, is an instruction for a PURE
 * insertion (`b == a`): the inserted segment carries exactly those families and no other (empty =
 * the user switched every style off at the cursor). When text is REPLACED (`b > a` and `n > 0`)
 * [pending] is IGNORED: the insertion inherits exactly the families that covered the whole of
 * `[a, b)`, as Gmail keeps the style of a selection typed over, and if none did the sticky rule
 * decides. Why: an IME commits `helo` + space as ONE replacement `[3, 4) → "lo "`, and a style
 * armed after `helo` was typed must not reach back over letters typed before the button; the
 * caller keeps [pending] armed, so the next pure keystroke receives it. [newSelection] settles
 * an ambiguous diff (typing `a` in `aaa`): the cut that ends the insertion at the cursor wins;
 * [oldSelection] is the second hint, and an unusable hint falls back to the maximal prefix.
 */
fun remapAfterEdit(
    old: RichBody,
    newText: String,
    oldSelection: Span,
    newSelection: Span,
    pending: Set<Inline>?,
): RichBody {
    val oldText = old.text
    if (newText == oldText) return old
    val (a, b, n) = diffEdit(oldText, newText, oldSelection, newSelection)
    val removed = b - a
    // A replacement keeps the style of what it replaced — the families covering all of [a, b) —
    // and pending is ignored: the replaced letters were typed before the button was pressed. A
    // pure insertion is what pending is for. null = no instruction, the sticky rule decides.
    val carried: Set<Inline>? =
        if (removed > 0 && n > 0) stylesAt(old, Span(a, b)).takeIf { it.isNotEmpty() } else pending
    val out = LinkedHashMap<Inline, List<Span>>()
    for ((kind, spans) in old.ranges) {
        val next = ArrayList<Span>(spans.size + 1)
        for (s in spans) {
            // 1. the removal of [a, b)
            val afterRemoval = when {
                s.end <= a -> s
                s.start >= b -> Span(s.start - removed, s.end - removed)
                else -> Span(minOf(s.start, a), maxOf(s.end - removed, a))
            }
            if (afterRemoval.isEmpty) continue
            // 2. the insertion of n characters at a
            val st = afterRemoval.start
            val en = afterRemoval.end
            val carries = carried == null || kind in carried
            when {
                n == 0 || en < a -> next.add(afterRemoval)
                en == a -> next.add(if (carries) Span(st, a + n) else afterRemoval)
                st >= a -> next.add(Span(st + n, en + n))
                carries -> next.add(Span(st, en + n))
                else -> { next.add(Span(st, a)); next.add(Span(a + n, en + n)) }
            }
        }
        out[kind] = next
    }
    if (n > 0) {
        for (kind in carried.orEmpty()) out[kind] = out[kind].orEmpty() + Span(a, a + n)
    }
    return RichBody(newText, out)
}

/** The replaced segment: `[a, b)` of the old text became `[a, a + n)` of the new one. */
private data class Edit(val a: Int, val b: Int, val n: Int)

private fun diffEdit(oldText: String, newText: String, oldSelection: Span, newSelection: Span): Edit {
    val shortest = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < shortest && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    val maxSuffix = shortest - prefix
    while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
    val a = prefix
    val b = oldText.length - suffix
    val n = newText.length - suffix - prefix
    // The prefix is maximal, so an alternative cut can only sit further left, by d characters,
    // and is valid when the d characters it stops removing equal the d it starts keeping.
    fun shiftedLeft(candidate: Int): Edit? {
        val d = a - candidate
        if (d <= 0 || candidate < 0) return null
        return if (oldText.regionMatches(b - d, newText, candidate + n, d)) Edit(candidate, b - d, n) else null
    }
    return shiftedLeft(newSelection.end - n)
        ?: (if (!oldSelection.isEmpty) shiftedLeft(oldSelection.start) else null)
        ?: Edit(a, b, n)
}

/**
 * Toggle [kind] on a selection: removed when the whole selection already carries it, applied to
 * the whole selection otherwise. An empty selection changes nothing.
 */
fun toggle(body: RichBody, selection: Span, kind: Inline): RichBody {
    if (selection.isEmpty) return body
    val spans = body.ranges[kind].orEmpty()
    val next = if (covers(spans, selection)) subtract(spans, selection) else spans + selection
    return RichBody(body.text, body.ranges + (kind to next))
}

/** Remove every family from the selection. An empty selection changes nothing. */
fun clear(body: RichBody, selection: Span): RichBody {
    if (selection.isEmpty) return body
    return RichBody(body.text, body.ranges.mapValues { (_, spans) -> subtract(spans, selection) })
}

/**
 * The families a toolbar should show as active. For a selection, those covering all of it. For
 * a cursor `c`, those with a span containing `c` or ending exactly at `c` (the sticky rule of
 * [remapAfterEdit]: what a keystroke there would inherit), not those starting at `c`.
 */
fun stylesAt(body: RichBody, selection: Span): Set<Inline> =
    if (selection.isEmpty) {
        val c = selection.start
        body.ranges.filterValues { spans -> spans.any { it.start < c && c <= it.end } }.keys
    } else {
        body.ranges.filterValues { spans -> covers(spans, selection) }.keys
    }

/** The whole of [target] lies inside one span (normalised spans never touch, so one is enough). */
private fun covers(spans: List<Span>, target: Span): Boolean =
    spans.any { it.start <= target.start && it.end >= target.end }

private fun subtract(spans: List<Span>, cut: Span): List<Span> =
    spans.flatMap { s ->
        when {
            s.end <= cut.start || s.start >= cut.end -> listOf(s)
            else -> listOf(Span(s.start, maxOf(s.start, cut.start)), Span(minOf(s.end, cut.end), s.end))
        }
    }.filter { !it.isEmpty }

// --- HTML --------------------------------------------------------------------------------------

private val TAG_OF = mapOf(
    Inline.BOLD to "b", Inline.ITALIC to "i", Inline.UNDERLINE to "u", Inline.STRIKE to "s",
)

/**
 * The body as HTML: escaped text, `\n` as `<br>`, and `<b> <i> <u> <s>` always nested in that
 * order, so at a span boundary the inner tags close and reopen around the one that changes.
 * Without any span (and no [verbatim]) the output is byte for byte [htmlEscapeMultiline] of the
 * text. [verbatim] replaces the text of a span with the given string, unescaped, for an HTML
 * signature imported as is; a style boundary inside that span only yields an empty tag pair.
 */
fun toHtml(body: RichBody, verbatim: Pair<Span, String>? = null): String {
    val text = body.text
    if (body.isPlain && verbatim == null) return htmlEscapeMultiline(text)
    val vSpan = verbatim?.first
    if (vSpan != null) require(vSpan.start >= 0 && vSpan.end <= text.length) { "verbatim $vSpan outside the text" }
    val cuts = sortedSetOf(0, text.length)
    for (spans in body.ranges.values) for (s in spans) { cuts.add(s.start); cuts.add(s.end) }
    if (vSpan != null) { cuts.add(vSpan.start); cuts.add(vSpan.end) }
    val positions = cuts.toList()

    val sb = StringBuilder(text.length + 16)
    val stack = ArrayList<Inline>()
    for ((i, p) in positions.withIndex()) {
        val active = Inline.entries.filter { kind ->
            body.ranges[kind].orEmpty().any { it.start <= p && p < it.end }
        }
        val target = if (p == text.length) emptyList() else active
        var common = 0
        while (common < stack.size && common < target.size && stack[common] == target[common]) common++
        while (stack.size > common) sb.append("</").append(TAG_OF.getValue(stack.removeAt(stack.size - 1))).append('>')
        for (k in common until target.size) { stack.add(target[k]); sb.append('<').append(TAG_OF.getValue(target[k])).append('>') }
        if (verbatim != null && p == verbatim.first.start) sb.append(verbatim.second)
        if (i + 1 < positions.size) {
            val q = positions[i + 1]
            if (vSpan == null || p < vSpan.start || p >= vSpan.end) sb.append(htmlEscapeMultiline(text.substring(p, q)))
        }
    }
    return sb.toString()
}

private val FAMILY_OF_TAG = mapOf(
    "b" to Inline.BOLD, "strong" to Inline.BOLD,
    "i" to Inline.ITALIC, "em" to Inline.ITALIC,
    "u" to Inline.UNDERLINE,
    "s" to Inline.STRIKE, "strike" to Inline.STRIKE, "del" to Inline.STRIKE,
)
private val TAG = Regex("<(/?)([A-Za-z]+)>")
private val BR = Regex("<[bB][rR]( ?/)?>")
private val ENTITY = Regex("&(amp|lt|gt|quot|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});")

/**
 * Strict parser of the closed subset [toHtml] writes, plus the obvious aliases: tags `b strong i
 * em u s strike del` without attributes, `<br>` `<br/>` `<br />`, the five named entities
 * `&amp; &lt; &gt; &quot; &#39;` and numeric references. Anything else (another tag, an attribute,
 * a comment, a doctype, an unknown or invalid entity, a bare `<` `>` `&`, an unbalanced or crossed
 * tag, a raw `\n` or `&#10;` where [toHtml] only ever writes `<br>`) returns null: the caller falls
 * back to plain text rather than guess. `\r` and `\t` pass, as [htmlEscape] lets them through.
 * Property: `fromHtml(toHtml(x)) == x` for every body `x`.
 */
fun fromHtml(html: String): RichBody? {
    val text = StringBuilder(html.length)
    val open = ArrayList<Pair<Inline, Int>>()
    val ranges = HashMap<Inline, MutableList<Span>>()
    var i = 0
    while (i < html.length) {
        val c = html[i]
        when (c) {
            '<' -> {
                val br = BR.matchAt(html, i)
                if (br != null) {
                    text.append('\n'); i += br.value.length; continue
                }
                val m = TAG.matchAt(html, i) ?: return null
                val kind = FAMILY_OF_TAG[m.groupValues[2].lowercase()] ?: return null
                if (m.groupValues[1].isEmpty()) {
                    open.add(kind to text.length)
                } else {
                    val (openKind, start) = open.removeLastOrNull() ?: return null
                    if (openKind != kind) return null
                    ranges.getOrPut(kind) { ArrayList() }.add(Span(start, text.length))
                }
                i += m.value.length
            }
            '&' -> {
                val m = ENTITY.matchAt(html, i) ?: return null
                val body = m.groupValues[1]
                val decoded = when {
                    body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::scalarOrNull)
                    body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::scalarOrNull)
                    else -> when (body) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; else -> null }
                } ?: return null
                if (decoded == "\n") return null
                text.append(decoded)
                i += m.value.length
            }
            '>', '\n' -> return null
            else -> { text.append(c); i++ }
        }
    }
    if (open.isNotEmpty()) return null
    return RichBody(text.toString(), ranges)
}

private fun scalarOrNull(cp: Int): String? =
    if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

/** The body stripped of its styling. */
fun toPlainText(body: RichBody): String = body.text
