package app.sterna.core.data.text

/**
 * The model of a rich body (#131): plain text plus four inline style families, each a set of
 * normalised half-open spans, plus paragraph blocks (bullet and numbered lists), each a run of
 * whole lines. Pure Kotlin, no Compose: the composer maps its `TextFieldValue` onto this and back,
 * and the HTML side is a closed subset (`b i u s br ul ol li` + a few entities) so a stored draft
 * can be read back exactly, or refused.
 */

/** An inline style family. Declaration order is the canonical nesting order in HTML: b > i > u > s. */
enum class Inline { BOLD, ITALIC, UNDERLINE, STRIKE }

/** A paragraph block family: a run of lines rendered as one list. */
enum class BlockKind { BULLET, NUMBER }

/**
 * A run of consecutive lines, by INCLUSIVE line index (a line is what `\n` separates), rendered as
 * one list. No nesting, no indent, no checkbox: one flat run of lines, one list.
 */
data class Block(val kind: BlockKind, val lines: IntRange)

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
 * Plain [text] plus, per style family, the spans it covers, plus the list [blocks].
 *
 * [ranges] is always normalised: clamped to the text, non-empty, sorted, merged when adjacent or
 * overlapping, a family without spans absent — and cut at every line break a list stands for,
 * because `</li><li>` carries no character (see [hardBreaks]).
 *
 * [blocks] is normalised the same way: clamped to `[0, lineCount)`, empty runs dropped, sorted,
 * two contiguous runs of the same kind merged into one. Two kinds never overlap: on a disputed
 * line the block written LAST wins, so a caller can append a block to impose it.
 *
 * Two bodies with the same text, the same styling and the same blocks are `equals`.
 */
class RichBody(val text: String, ranges: Map<Inline, List<Span>>, blocks: List<Block> = emptyList()) {
    /** Lines are separated by `\n`; an empty text still has one line. */
    val lineCount: Int = text.count { it == '\n' } + 1

    val blocks: List<Block> = normaliseBlocks(lineCount, blocks)

    val ranges: Map<Inline, List<Span>> = normaliseRanges(text, this.blocks, ranges)

    /** Neither a span nor a block: what `draftHtmlToSave` may store as text only. */
    val isPlain: Boolean get() = ranges.isEmpty() && blocks.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is RichBody && other.text == text && other.ranges == ranges && other.blocks == blocks
    override fun hashCode(): Int = 31 * (31 * text.hashCode() + ranges.hashCode()) + blocks.hashCode()
    override fun toString(): String = "RichBody(text=$text, ranges=$ranges, blocks=$blocks)"

    companion object {
        fun plain(text: String): RichBody = RichBody(text, emptyMap())
        fun of(text: String, ranges: Map<Inline, List<Span>>, blocks: List<Block> = emptyList()): RichBody =
            RichBody(text, ranges, blocks)
    }
}

// --- normalisation --------------------------------------------------------------------------------

private fun normaliseRanges(
    text: String,
    blocks: List<Block>,
    ranges: Map<Inline, List<Span>>,
): Map<Inline, List<Span>> {
    val hard = hardBreaks(text, blocks)
    return Inline.entries.mapNotNull { kind ->
        var spans = normaliseSpans(text.length, ranges[kind].orEmpty())
        for (nl in hard.sorted()) spans = subtract(spans, Span(nl, nl + 1))
        if (spans.isEmpty()) null else kind to spans
    }.toMap()
}

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

private fun normaliseBlocks(lineCount: Int, blocks: List<Block>): List<Block> =
    if (blocks.isEmpty()) emptyList() else blocksOfLines(kindsByLine(lineCount, blocks))

/**
 * The kind covering each line, or null. A block outside the text is dropped, not clamped onto the
 * last line; on a line claimed twice the LAST block written wins.
 */
private fun kindsByLine(lineCount: Int, blocks: List<Block>): Array<BlockKind?> {
    val kinds = arrayOfNulls<BlockKind>(lineCount)
    for (b in blocks) {
        val from = maxOf(b.lines.first, 0)
        val to = minOf(b.lines.last, lineCount - 1)
        for (l in from..to) kinds[l] = b.kind
    }
    return kinds
}

/** The runs of equal kind, in order: this IS the normal form of a block list. */
private fun blocksOfLines(kinds: Array<BlockKind?>): List<Block> {
    val out = ArrayList<Block>()
    var l = 0
    while (l < kinds.size) {
        val k = kinds[l]
        if (k == null) { l++; continue }
        var e = l
        while (e + 1 < kinds.size && kinds[e + 1] == k) e++
        out.add(Block(k, l..e))
        l = e + 1
    }
    return out
}

/**
 * The offsets of the `\n` that a list stands for — a line break with a block on either side. HTML
 * writes them as `</li><li>` or as the end of one list and the start of the next, never as a plain
 * `<br>`, so no inline tag may stay open across them and no span may cover them.
 */
private fun hardBreaks(text: String, blocks: List<Block>): Set<Int> {
    if (blocks.isEmpty()) return emptySet()
    val kinds = kindsByLine(text.count { it == '\n' } + 1, blocks)
    val out = HashSet<Int>()
    var line = 0
    for (i in text.indices) {
        if (text[i] != '\n') continue
        if (kinds[line] != null || kinds[line + 1] != null) out.add(i)
        line++
    }
    return out
}

/** The index of the line holding [offset] (the `\n` at the end of a line belongs to that line). */
private fun lineAt(text: String, offset: Int): Int {
    val p = offset.coerceIn(0, text.length)
    var n = 0
    for (i in 0 until p) if (text[i] == '\n') n++
    return n
}

/** The offset each line starts at; its size is the line count. */
private fun lineStarts(text: String): List<Int> {
    val out = ArrayList<Int>()
    out.add(0)
    for (i in text.indices) if (text[i] == '\n') out.add(i + 1)
    return out
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
 *
 * Blocks follow the same edit: each NEW line takes the kind of the OLD line its start came from,
 * so typing inside an item splits it into two items of the same list and deleting the `\n` between
 * two items merges them. One exception, Gmail's: a bare Enter on an EMPTY item leaves the list.
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
    return RichBody(newText, out, remapBlocks(old, newText, a, removed, n))
}

/** Each new line takes the kind of the old line its start came from. See [remapAfterEdit]. */
private fun remapBlocks(old: RichBody, newText: String, a: Int, removed: Int, n: Int): List<Block> {
    if (old.blocks.isEmpty()) return emptyList()
    val oldText = old.text
    val oldKinds = kindsByLine(old.lineCount, old.blocks)
    val starts = lineStarts(newText)
    val newKinds = arrayOfNulls<BlockKind>(starts.size)
    for ((line, q) in starts.withIndex()) {
        val back = when {
            q < a -> q
            q >= a + n -> q - n + removed
            else -> a
        }
        newKinds[line] = oldKinds[lineAt(oldText, back)]
    }
    // Gmail: Enter on an EMPTY item ends the list. ⚠ Gmail also deletes the blank line; we may
    // not — remapAfterEdit has no right to change the text. Assumed debt, already arbitrated.
    if (removed == 0 && n == 1 && a < newText.length && newText[a] == '\n') {
        val l0 = lineAt(oldText, a)
        val oldStarts = lineStarts(oldText)
        val end = if (l0 + 1 < oldStarts.size) oldStarts[l0 + 1] - 1 else oldText.length
        if (oldStarts[l0] == end && oldKinds[l0] != null) {
            newKinds[l0] = null
            newKinds[l0 + 1] = null
        }
    }
    return blocksOfLines(newKinds)
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
    val next = if (coversAcrossBreaks(body, spans, selection)) subtract(spans, selection) else spans + selection
    return RichBody(body.text, body.ranges + (kind to next), body.blocks)
}

/** Remove every family from the selection. An empty selection changes nothing. */
fun clear(body: RichBody, selection: Span): RichBody {
    if (selection.isEmpty) return body
    return RichBody(body.text, body.ranges.mapValues { (_, spans) -> subtract(spans, selection) }, body.blocks)
}

/**
 * Turn every line the selection touches into a list of [kind]; if they ALL carry that kind
 * already, turn them back into text. ⚠ Unlike [toggle], an EMPTY selection is not a no-op: the
 * cursor's line alone is the target, because that is the gesture in Gmail.
 */
fun toggleBlock(body: RichBody, selection: Span, kind: BlockKind): RichBody {
    val text = body.text
    val first = lineAt(text, selection.start)
    // A selection stopping exactly at the start of a line does not reach into it.
    val last = lineAt(text, if (selection.isEmpty) selection.start else selection.end - 1)
    val kinds = kindsByLine(body.lineCount, body.blocks)
    val already = (first..last).all { kinds[it] == kind }
    for (l in first..last) kinds[l] = if (already) null else kind
    return RichBody(text, body.ranges, blocksOfLines(kinds))
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
        body.ranges.filterValues { spans -> coversAcrossBreaks(body, spans, selection) }.keys
    }

/** The whole of [target] lies inside one span (normalised spans never touch, so one is enough). */
private fun covers(spans: List<Span>, target: Span): Boolean =
    spans.any { it.start <= target.start && it.end >= target.end }

/**
 * The whole of [target] carries the style, the line breaks a list stands for set aside.
 *
 * ⛔ Not just [covers]. `</li><li>` carries no character, so a span is cut at every such newline
 * ([normaliseRanges]) and a selection running over TWO items of a list sits inside no single span.
 * Read as "not covered", two bold items selected together leave the Bold button dark and the tap
 * APPLIES bold instead of removing it: inside a list, bold could no longer be taken off at all,
 * with no way out but "clear formatting".
 */
private fun coversAcrossBreaks(body: RichBody, spans: List<Span>, target: Span): Boolean {
    if (covers(spans, target)) return true
    val hard = hardBreaks(body.text, body.blocks)
    if (hard.isEmpty()) return false
    var p = target.start
    while (p < target.end) {
        if (p in hard) { p++; continue }
        p = (spans.firstOrNull { it.start <= p && p < it.end } ?: return false).end
    }
    return true
}

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

private val LIST_TAG = mapOf(BlockKind.BULLET to "ul", BlockKind.NUMBER to "ol")

/**
 * The body as HTML: escaped text, `\n` as `<br>`, and `<b> <i> <u> <s>` always nested in that
 * order, so at a span boundary the inner tags close and reopen around the one that changes.
 * A block becomes `<ul>` or `<ol>` with one `<li>` per line: between two lines of the SAME block
 * there is no `<br>` at all, `</li><li>` carries the break; everywhere else the `<br>` stays,
 * including just before `<ul>` and just after `</ul>`. Inline tags are written inside the `<li>`
 * and never cross it. Without any span or block (and no [verbatim]) the output is byte for byte
 * [htmlEscapeMultiline] of the text. [verbatim] replaces the text of a span with the given string,
 * unescaped, for an HTML signature imported as is; a style boundary inside that span only yields
 * an empty tag pair.
 */
fun toHtml(body: RichBody, verbatim: Pair<Span, String>? = null): String {
    val text = body.text
    if (body.isPlain && verbatim == null) return htmlEscapeMultiline(text)
    val vSpan = verbatim?.first
    if (vSpan != null) require(vSpan.start >= 0 && vSpan.end <= text.length) { "verbatim $vSpan outside the text" }
    val kinds = kindsByLine(body.lineCount, body.blocks)
    val hard = hardBreaks(text, body.blocks)
    val cuts = sortedSetOf(0, text.length)
    for (spans in body.ranges.values) for (s in spans) { cuts.add(s.start); cuts.add(s.end) }
    if (vSpan != null) { cuts.add(vSpan.start); cuts.add(vSpan.end) }
    for (nl in hard) { cuts.add(nl); cuts.add(nl + 1) }
    val positions = cuts.toList()

    val sb = StringBuilder(text.length + 16)
    kinds[0]?.let { sb.append('<').append(LIST_TAG.getValue(it)).append("><li>") }
    val stack = ArrayList<Inline>()
    for ((i, p) in positions.withIndex()) {
        val active = Inline.entries.filter { kind ->
            body.ranges[kind].orEmpty().any { it.start <= p && p < it.end }
        }
        // Every inline tag closes at the end of the text and at a list break: an `<li>` never
        // ends with a tag still open, and `<ul>` never sits inside one.
        val target = if (p == text.length || p in hard) emptyList() else active
        var common = 0
        while (common < stack.size && common < target.size && stack[common] == target[common]) common++
        while (stack.size > common) sb.append("</").append(TAG_OF.getValue(stack.removeAt(stack.size - 1))).append('>')
        for (k in common until target.size) { stack.add(target[k]); sb.append('<').append(TAG_OF.getValue(target[k])).append('>') }
        if (verbatim != null && p == verbatim.first.start) sb.append(verbatim.second)
        if (i + 1 < positions.size) {
            val q = positions[i + 1]
            if (p in hard) {
                sb.append(listBreak(kinds, lineAt(text, p)))
            } else if (vSpan == null || p < vSpan.start || p >= vSpan.end) {
                sb.append(htmlEscapeMultiline(text.substring(p, q)))
            }
        }
    }
    kinds[kinds.size - 1]?.let { sb.append("</li></").append(LIST_TAG.getValue(it)).append('>') }
    return sb.toString()
}

/** What stands between line [line] and the next one when a list is involved. */
private fun listBreak(kinds: Array<BlockKind?>, line: Int): String {
    val prev = kinds[line]
    val next = kinds[line + 1]
    if (prev != null && prev == next) return "</li><li>"
    val sb = StringBuilder()
    if (prev != null) sb.append("</li></").append(LIST_TAG.getValue(prev)).append('>')
    sb.append("<br>")
    if (next != null) sb.append('<').append(LIST_TAG.getValue(next)).append("><li>")
    return sb.toString()
}

private val FAMILY_OF_TAG = mapOf(
    "b" to Inline.BOLD, "strong" to Inline.BOLD,
    "i" to Inline.ITALIC, "em" to Inline.ITALIC,
    "u" to Inline.UNDERLINE,
    "s" to Inline.STRIKE, "strike" to Inline.STRIKE, "del" to Inline.STRIKE,
)
private val LIST_OF_TAG = mapOf("ul" to BlockKind.BULLET, "ol" to BlockKind.NUMBER)
private val TAG = Regex("<(/?)([A-Za-z]+)>")
private val BR = Regex("<[bB][rR]( ?/)?>")
private val ENTITY = Regex("&(amp|lt|gt|quot|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});")

/**
 * Strict parser of the closed subset [toHtml] writes, plus the obvious aliases: tags `b strong i
 * em u s strike del` without attributes, `<br>` `<br/>` `<br />`, `<ul> <ol> <li>` without
 * attributes, the five named entities `&amp; &lt; &gt; &quot; &#39;` and numeric references.
 * Anything else (another tag, an attribute, a comment, a doctype, an unknown or invalid entity, a
 * bare `<` `>` `&`, an unbalanced or crossed tag, a raw `\n` or `&#10;` where [toHtml] only ever
 * writes `<br>`) returns null: the caller falls back to plain text rather than guess. So does
 * anything unusual about a list: an `<li>` outside a list, a nested list, an empty list, a `<br>`
 * inside a list, text outside an `<li>`, an inline tag still open at `</li>`, a list left open, and
 * a list that does not stand on a line of its own (`a<ul>…`, `…</ul>x`, `</ul><ol>`).
 * `\r` and `\t` pass, as [htmlEscape] lets them through.
 * Property: `fromHtml(toHtml(x)) == x` for every body `x`, blocks included.
 */
fun fromHtml(html: String): RichBody? {
    val text = StringBuilder(html.length)
    val open = ArrayList<Pair<Inline, Int>>()
    val ranges = HashMap<Inline, MutableList<Span>>()
    val blocks = ArrayList<Block>()
    var list: BlockKind? = null   // the list currently open, if any
    var listFrom = 0              // the line it started on
    var items = 0                 // how many <li> it has held
    var inItem = false
    var line = 0                  // how many lines the text holds so far
    var i = 0
    while (i < html.length) {
        val c = html[i]
        when (c) {
            '<' -> {
                val br = BR.matchAt(html, i)
                if (br != null) {
                    // Inside a list the break is `</li><li>`, never a `<br>`.
                    if (list != null) return null
                    text.append('\n'); line++; i += br.value.length; continue
                }
                val m = TAG.matchAt(html, i) ?: return null
                val closing = m.groupValues[1].isNotEmpty()
                val name = m.groupValues[2].lowercase()
                i += m.value.length
                val listKind = LIST_OF_TAG[name]
                when {
                    listKind != null -> {
                        if (closing) {
                            if (list != listKind || inItem || items == 0) return null
                            blocks.add(Block(listKind, listFrom..line))
                            list = null
                            // …and a list ENDS a line: nothing may follow `</ul>` but the end of
                            // the input or the `<br>` that opens the next one. `</ul>x` and
                            // `</ul><ol>` would glue two lines into one — see below.
                            if (i < html.length && BR.matchAt(html, i) == null) return null
                        } else {
                            if (list != null || open.isNotEmpty()) return null
                            // ⛔ A list STARTS a line: [toHtml] only ever opens one at the start of
                            // the text or right after the `<br>` that ended the previous line.
                            // Reading `a<ul><li>b</li></ul>` as "ab" on one line is worse than
                            // refusing it: `draftHtmlIsLossy` would then call that draft faithful,
                            // and the next save DESTROYS the original the glued text came from.
                            if (text.isNotEmpty() && text.last() != '\n') return null
                            list = listKind; listFrom = line; items = 0
                        }
                    }
                    name == "li" -> {
                        if (list == null) return null
                        if (closing) {
                            if (!inItem || open.isNotEmpty()) return null
                            inItem = false
                        } else {
                            if (inItem) return null
                            if (items > 0) { text.append('\n'); line++ }
                            items++; inItem = true
                        }
                    }
                    else -> {
                        if (list != null && !inItem) return null
                        val kind = FAMILY_OF_TAG[name] ?: return null
                        if (!closing) {
                            open.add(kind to text.length)
                        } else {
                            val (openKind, start) = open.removeLastOrNull() ?: return null
                            if (openKind != kind) return null
                            ranges.getOrPut(kind) { ArrayList() }.add(Span(start, text.length))
                        }
                    }
                }
            }
            '&' -> {
                if (list != null && !inItem) return null
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
            else -> {
                if (list != null && !inItem) return null
                text.append(c); i++
            }
        }
    }
    if (open.isNotEmpty() || list != null) return null
    return RichBody(text.toString(), ranges, blocks)
}

private fun scalarOrNull(cp: Int): String? =
    if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

/**
 * The body stripped of its styling, with the list markers spelled out: `- ` on every line of a
 * bullet block, `1. `, `2. `… on a numbered one, the numbering starting again at 1 for each block.
 * ⛔ These markers exist HERE and nowhere else: `body.text` is what was typed, it carries none of
 * them, and it is what the editor shows and what the dirty check compares.
 */
fun toPlainText(body: RichBody): String {
    if (body.blocks.isEmpty()) return body.text
    val lines = body.text.split('\n')
    val marker = arrayOfNulls<String>(lines.size)
    for (b in body.blocks) {
        var n = 1
        for (l in b.lines) {
            marker[l] = if (b.kind == BlockKind.BULLET) "- " else "$n. "
            n++
        }
    }
    return lines.mapIndexed { i, s -> (marker[i] ?: "") + s }.joinToString("\n")
}
