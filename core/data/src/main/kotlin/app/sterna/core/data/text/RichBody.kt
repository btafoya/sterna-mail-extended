package app.sterna.core.data.text

/**
 * The model of a rich body (#131): plain text plus four inline style families, each a set of
 * normalised half-open spans, plus paragraph blocks (bullet and numbered lists), each a run of
 * whole lines, plus the hyperlinks it carries. Pure Kotlin, no Compose: the composer maps its
 * `TextFieldValue` onto this and back, and the HTML side is a closed subset
 * (`a b i u s br ul ol li` + a few entities) so a stored draft can be read back exactly, or
 * refused.
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
 * A hyperlink over a half-open [span] of the text (#131), carrying the [url] a recipient clicks.
 *
 * ⛔ A [Span] and not an `IntRange`: half-open is the ONE shape of range this model has, and the
 * two disagree about the last character. ⛔ And the URL is stored as the guard answered it. The
 * guard has THREE borders and TWO faces:
 *  - [normalizeLinkUrl], the DIALOG's face: a bare `example.org` becomes `https://example.org`,
 *    because that is what someone typing it means;
 *  - [linkUrlFromHref], the STRICT face, at every border where the URL was written by someone
 *    else — [fromHtml] for a stored or foreign body, and `decodeLinks` for a parked record. It
 *    invents nothing: no scheme, no link.
 */
data class Link(val span: Span, val url: String)

/**
 * Plain [text] plus, per style family, the spans it covers, plus the list [blocks], plus its
 * [links].
 *
 * [ranges] is always normalised: clamped to the text, non-empty, sorted, merged when adjacent or
 * overlapping, a family without spans absent — and cut at every line break a list stands for,
 * because `</li><li>` carries no character (see [hardBreaks]).
 *
 * [blocks] is normalised the same way: clamped to `[0, lineCount)`, empty runs dropped, sorted,
 * two contiguous runs of the same kind merged into one. Two kinds never overlap: on a disputed
 * line the block written LAST wins, so a caller can append a block to impose it.
 *
 * [links] is normalised too — clamped to the text, non-empty, sorted, and cut at those same line
 * breaks — but ⛔ NEVER merged: two touching links are two anchors, even under one URL, and
 * merging them would make `fromHtml(toHtml(x)) == x` false for a body the user can write in three
 * taps. Overlaps cannot be represented (one character belongs to one link), so the first one wins.
 *
 * Two bodies with the same text, the same styling, the same blocks and the same links are
 * `equals`.
 */
class RichBody(
    val text: String,
    ranges: Map<Inline, List<Span>>,
    blocks: List<Block> = emptyList(),
    links: List<Link> = emptyList(),
) {
    /** Lines are separated by `\n`; an empty text still has one line. */
    val lineCount: Int = text.count { it == '\n' } + 1

    val blocks: List<Block> = normaliseBlocks(lineCount, blocks)

    val ranges: Map<Inline, List<Span>> = normaliseRanges(text, this.blocks, ranges)

    val links: List<Link> = normaliseLinks(text, this.blocks, links)

    /**
     * Neither a span, nor a block, nor a link: what `draftHtmlToSave` may store as text only.
     *
     * ⚠ The links count: a body whose ONLY styling is a link must still save an html part, or
     * the link dies at the next reopen in silence.
     */
    val isPlain: Boolean get() = ranges.isEmpty() && blocks.isEmpty() && links.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is RichBody && other.text == text && other.ranges == ranges &&
            other.blocks == blocks && other.links == links
    override fun hashCode(): Int =
        31 * (31 * (31 * text.hashCode() + ranges.hashCode()) + blocks.hashCode()) + links.hashCode()
    override fun toString(): String =
        "RichBody(text=$text, ranges=$ranges, blocks=$blocks, links=$links)"

    companion object {
        fun plain(text: String): RichBody = RichBody(text, emptyMap())
        fun of(
            text: String,
            ranges: Map<Inline, List<Span>>,
            blocks: List<Block> = emptyList(),
            links: List<Link> = emptyList(),
        ): RichBody = RichBody(text, ranges, blocks, links)
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

/**
 * Clamped, empty ones dropped, ⛔ CUT at every line break a list stands for, sorted — and ⛔ NOT
 * merged.
 *
 * The cut is [normaliseRanges]'s, for [normaliseRanges]'s reason: `</li><li>` carries no
 * character, an anchor may not stay open across it, and a link straddling two items would come
 * back from [fromHtml] as two — `fromHtml(toHtml(x)) == x` false for a body three taps produce
 * (link a phrase, then bullet it). Cut here, the two halves ARE what comes back.
 *
 * Not merged: a link is kept only when it starts at or after the end of the last one kept, so the
 * first of two overlapping links wins, which is arbitrary but total, and nothing in the editor can
 * produce an overlap anyway.
 */
private fun normaliseLinks(text: String, blocks: List<Block>, links: List<Link>): List<Link> {
    if (links.isEmpty()) return emptyList()
    val hard = hardBreaks(text, blocks).sorted()
    val sorted = links
        .map { Link(Span(it.span.start.coerceIn(0, text.length), it.span.end.coerceIn(0, text.length)), it.url) }
        .filter { !it.span.isEmpty }
        .flatMap { l ->
            var pieces = listOf(l.span)
            for (nl in hard) pieces = subtract(pieces, Span(nl, nl + 1))
            pieces.map { Link(it, l.url) }
        }
        .sortedWith(compareBy({ it.span.start }, { it.span.end }))
    val out = ArrayList<Link>(sorted.size)
    for (l in sorted) {
        val last = out.lastOrNull()
        if (last == null || l.span.start >= last.span.end) out.add(l)
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
 *
 * Links follow it too, by [remapLinks], which is the same arithmetic ⛔ WITHOUT the sticky rule.
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
    return RichBody(newText, out, remapBlocks(old, newText, a, removed, n), remapLinks(old.links, a, b, n))
}

/**
 * The links after the same edit — the removal exactly like a span, the insertion ⛔ WITHOUT the
 * sticky rule: text typed at either border of a link is written OUTSIDE it, and only a keystroke
 * strictly inside extends it. Sticky here is what turns "type a word after a link" into a link
 * over that word, which no reader can see coming and no button undoes without a selection. A link
 * left with no character disappears. `pending` has nothing to say: a link is never armed at a
 * caret, it is only ever put on characters that exist.
 */
private fun remapLinks(links: List<Link>, a: Int, b: Int, n: Int): List<Link> {
    val removed = b - a
    val out = ArrayList<Link>(links.size)
    for (l in links) {
        val s = l.span
        val afterRemoval = when {
            s.end <= a -> s
            s.start >= b -> Span(s.start - removed, s.end - removed)
            else -> Span(minOf(s.start, a), maxOf(s.end - removed, a))
        }
        if (afterRemoval.isEmpty) continue
        val st = afterRemoval.start
        val en = afterRemoval.end
        out.add(
            Link(
                when {
                    n == 0 || en <= a -> afterRemoval
                    st >= a -> Span(st + n, en + n)
                    else -> Span(st, en + n)
                },
                l.url,
            ),
        )
    }
    return out
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
    return RichBody(body.text, body.ranges + (kind to next), body.blocks, body.links)
}

/** Remove every family from the selection. An empty selection changes nothing. */
fun clear(body: RichBody, selection: Span): RichBody {
    if (selection.isEmpty) return body
    // ⛔ The blocks and the links stay: "clear formatting" takes the four families off. A list is
    // a paragraph shape, and a link is an ADDRESS, not a family — dropping it silently would lose
    // what the reader was to click.
    return RichBody(
        body.text,
        body.ranges.mapValues { (_, spans) -> subtract(spans, selection) },
        body.blocks,
        body.links,
    )
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
    return RichBody(text, body.ranges, blocksOfLines(kinds), body.links)
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
 * The body as HTML: escaped text, `\n` as `<br>`, and `<a> <b> <i> <u> <s>` always nested in that
 * order, so at a boundary the inner tags close and reopen around the one that changes.
 * A block becomes `<ul>` or `<ol>` with one `<li>` per line: between two lines of the SAME block
 * there is no `<br>` at all, `</li><li>` carries the break; everywhere else the `<br>` stays,
 * including just before `<ul>` and just after `</ul>`. Inline tags AND the anchor are written
 * inside the `<li>` and never cross it, so the nesting inside an item is `li > a > b > i > u > s`.
 * Without any span, block or link (and no [verbatim]) the output is byte for byte
 * [htmlEscapeMultiline] of the text. [verbatim] replaces the text of a span with the given string,
 * unescaped, for an HTML signature imported as is; a style boundary inside that span only yields
 * an empty tag pair.
 *
 * ⛔ The stack holds ELEMENTS, not families, and a link's element is the LINK — not its URL. Two
 * touching links of the same address must come out as two `<a>`s: compared by URL they would fuse
 * into one, and `fromHtml(toHtml(x))` would answer a body the user did not write.
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
    for (l in body.links) { cuts.add(l.span.start); cuts.add(l.span.end) }
    if (vSpan != null) { cuts.add(vSpan.start); cuts.add(vSpan.end) }
    for (nl in hard) { cuts.add(nl); cuts.add(nl + 1) }
    val positions = cuts.toList()

    val sb = StringBuilder(text.length + 16)
    kinds[0]?.let { sb.append('<').append(LIST_TAG.getValue(it)).append("><li>") }
    val stack = ArrayList<Any>()
    for ((i, p) in positions.withIndex()) {
        val active: List<Any> = listOfNotNull(body.links.firstOrNull { it.span.start <= p && p < it.span.end }) +
            Inline.entries.filter { kind ->
                body.ranges[kind].orEmpty().any { it.start <= p && p < it.end }
            }
        // Every element — the anchor included — closes at the end of the text and at a list break:
        // an `<li>` never ends with a tag still open, and `<ul>` never sits inside one.
        val target: List<Any> = if (p == text.length || p in hard) emptyList() else active
        var common = 0
        while (common < stack.size && common < target.size && stack[common] == target[common]) common++
        while (stack.size > common) sb.append(closeTagOf(stack.removeAt(stack.size - 1)))
        for (k in common until target.size) { stack.add(target[k]); sb.append(openTagOf(target[k])) }
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

/** `<b>` … or the anchor, whose URL is escaped for an ATTRIBUTE: the three text characters, plus
 *  the quote that would end the value and the two line breaks that would break the tag open. */
private fun openTagOf(element: Any): String = when (element) {
    is Inline -> "<" + TAG_OF.getValue(element) + ">"
    is Link -> "<a href=\"" + escapeAttribute(element.url) + "\">"
    else -> error("not an html element: $element")
}

private fun closeTagOf(element: Any): String =
    if (element is Inline) "</" + TAG_OF.getValue(element) + ">" else "</a>"

private fun escapeAttribute(url: String): String =
    htmlEscape(url).replace("\"", "&quot;").replace("\n", "&#10;").replace("\r", "&#13;")

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
 * ⛔ ONE attribute, named `href`, one space, and the value in matching quotes — that is the whole
 * of what an anchor may be here. `<a href="x" onclick="y">`, `<a class="c" href="x">`,
 * `<a href="x" target="_blank">` and `<a>` all fail to match and the body is refused whole, which
 * is what every foreign html has always got from this parser. The value may hold no `<` `>` nor
 * the quote that closes it; everything else in it is an entity or a literal character.
 */
private val ANCHOR = Regex("<[aA] [hH][rR][eE][fF]=(?:\"([^\"<>]*)\"|'([^'<>]*)')>")
private val ANCHOR_CLOSE = Regex("</[aA]>")

/** One tag waiting for its closer: a style family, or a link with the URL its `<a>` carried. */
private class OpenTag(val kind: Inline?, val url: String?, val start: Int)

/**
 * Strict parser of the closed subset [toHtml] writes, plus the obvious aliases: tags `b strong i
 * em u s strike del` without attributes, `<br>` `<br/>` `<br />`, `<ul> <ol> <li>` without
 * attributes, an `<a>` with exactly one `href` (see [ANCHOR]), the five named entities
 * `&amp; &lt; &gt; &quot; &#39;` and numeric references.
 * Anything else (another tag, an attribute, a comment, a doctype, an unknown or invalid entity, a
 * bare `<` `>` `&`, an unbalanced or crossed tag, a raw `\n` or `&#10;` where [toHtml] only ever
 * writes `<br>`) returns null: the caller falls back to plain text rather than guess. So does
 * anything unusual about a list: an `<li>` outside a list, a nested list, an empty list, a `<br>`
 * inside a list, text outside an `<li>`, an `<a>` outside an `<li>`, an inline tag or an anchor
 * still open at `</li>`, a list left open, and a list that does not stand on a line of its own
 * (`a<ul>…`, `…</ul>x`, `</ul><ol>`).
 * `\r` and `\t` pass, as [htmlEscape] lets them through.
 * Property: `fromHtml(toHtml(x)) == x` for every body `x`, blocks and links included.
 */
fun fromHtml(html: String): RichBody? {
    val text = StringBuilder(html.length)
    // ⛔ One stack for the four families AND the anchor: `open.isNotEmpty()` is what refuses a
    // list opened inside a tag and an item closed under one, and an anchor is no exception.
    val open = ArrayList<OpenTag>()
    val ranges = HashMap<Inline, MutableList<Span>>()
    val links = ArrayList<Link>()
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
                val anchor = ANCHOR.matchAt(html, i)
                if (anchor != null) {
                    // Inside a list, an anchor lives in an item like everything else: text, an
                    // entity and a style tag outside an `<li>` are all refused, and so is this.
                    if (list != null && !inItem) return null
                    // An `<a>` inside an `<a>`: no body can mean that, and guessing which one a
                    // character belongs to is exactly the guess this parser refuses to make.
                    if (open.any { it.url != null }) return null
                    val raw = anchor.groupValues[1].ifEmpty { anchor.groupValues[2] }
                    // ⛔ THE STRICT face of the guard, never the dialog's: a stored draft may not
                    // bring in a `javascript:`, and a RELATIVE href may not be turned into an
                    // address of our own invention. Refused here, the WHOLE body is refused, so
                    // the reopen falls back to the stored text and its original is kept.
                    val url = linkUrlFromHref(decodeEntities(raw) ?: return null) ?: return null
                    open.add(OpenTag(kind = null, url = url, start = text.length))
                    i += anchor.value.length
                    continue
                }
                val closer = ANCHOR_CLOSE.matchAt(html, i)
                if (closer != null) {
                    // `?: return null` twice: a `</a>` with nothing open, and one closing over a
                    // style tag opened inside it (`<a href="x"><b>t</a></b>`) — a crossed pair.
                    val top = open.removeLastOrNull() ?: return null
                    val url = top.url ?: return null
                    links.add(Link(Span(top.start, text.length), url))
                    i += closer.value.length
                    continue
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
                            open.add(OpenTag(kind = kind, url = null, start = text.length))
                        } else {
                            // `top.kind != kind` also refuses `<a href="x"><b>t</a></b>` read from
                            // the other side: the top is the anchor, and an anchor is not a `<b>`.
                            val top = open.removeLastOrNull() ?: return null
                            if (top.kind != kind) return null
                            ranges.getOrPut(kind) { ArrayList() }.add(Span(top.start, text.length))
                        }
                    }
                }
            }
            '&' -> {
                if (list != null && !inItem) return null
                val m = ENTITY.matchAt(html, i) ?: return null
                val decoded = decodeEntity(m.groupValues[1]) ?: return null
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
    // An `<a>` (or a `<b>`) never closed, or a list left open: the body is refused, never half read.
    if (open.isNotEmpty() || list != null) return null
    return RichBody(text.toString(), ranges, blocks, links)
}

/**
 * An attribute value: the same entities as the text, and ⭐ `&#10;` / `&#13;` are ALLOWED here.
 * In the text they are refused because [toHtml] writes a newline as `<br>` and nothing else; in an
 * attribute there is no `<br>`, so they are exactly how a URL carrying one comes back.
 */
private fun decodeEntities(value: String): String? {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        if (value[i] == '&') {
            val m = ENTITY.matchAt(value, i) ?: return null
            out.append(decodeEntity(m.groupValues[1]) ?: return null)
            i += m.value.length
        } else {
            out.append(value[i]); i++
        }
    }
    return out.toString()
}

private fun decodeEntity(body: String): String? = when {
    body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::scalarOrNull)
    body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::scalarOrNull)
    else -> when (body) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; else -> null }
}

private fun scalarOrNull(cp: Int): String? =
    if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

// --- links -------------------------------------------------------------------------------------

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

/** The only schemes a link may carry. Everything else is refused, whatever it looks like. */
private val LINK_SCHEMES = setOf("http", "https", "mailto")

/**
 * ⛔ THE STRICT FACE OF THE GUARD, for every URL this app did not just watch someone type: an
 * `href` out of a stored or foreign body ([fromHtml]) and a URL out of a parked composer record
 * (`decodeLinks`). The scheme must be THERE and be one of [LINK_SCHEMES]; it is lowercased, and
 * nothing else is touched. `null` refuses the whole body or the whole record.
 *
 * ⛔ IT MAY NOT PREFIX. [normalizeLinkUrl] turns a bare `example.org` into `https://example.org`,
 * which is right under a finger and WRONG here: `href="x"`, `href="#top"`, `href="/prix"` are
 * RELATIVE addresses, and prefixing them mints a link to somewhere else entirely. And the harm is
 * not cosmetic — `draftHtmlIsLossy` is the licence to EXPUNGE a server original: a foreign draft
 * with a relative anchor used to be unreadable, so its original was kept; read with a scheme put
 * in front of it, it is judged reproducible and the original is destroyed in favour of a copy
 * whose link points elsewhere. Refusing is the only side of that error that keeps the message.
 *
 * ⚠ The html round trip is untouched: [toHtml] only ever writes URLs that already went through
 * one of the two faces, so they all carry a scheme.
 */
fun linkUrlFromHref(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val match = SCHEME.find(trimmed) ?: return null
    val scheme = match.value.dropLast(1).lowercase()
    if (scheme !in LINK_SCHEMES) return null
    return scheme + ":" + trimmed.substring(match.value.length)
}

/**
 * ⛔ THE GUARD as the DIALOG asks it — one of its two faces. The answer is what gets stored, so
 * it is what a recipient's client is handed. The other face is [linkUrlFromHref], and it is the
 * one every border that did not watch the user type must use ([fromHtml], `decodeLinks`): only
 * this one may put a scheme in front of a bare host.
 *
 * Trimmed; empty is null. A recognised scheme (`^[A-Za-z][A-Za-z0-9+.-]*:`) is LOWERCASED and must
 * be one of [LINK_SCHEMES] — `javascript:`, `data:`, `file:`, `vbscript:` and every other one are
 * refused, in any case. With no scheme at all, `https://` is put in front: that is what the user
 * typing `example.org` means, and it is the safe direction.
 *
 * ⛔ Nothing else is validated: no DNS, no domain regex, no path rules. A typo is the writer's to
 * see, and the click is the reader's; refusing a URL we merely dislike is the failure mode of
 * every "smart" link box.
 *
 * ⚠ IDEMPOTENT, and the html round trip rests on it: `toHtml` writes the answer, `fromHtml` runs
 * this again on the way back, so a second pass that changed anything would make the round trip
 * false for the body it changed.
 *
 * ⚠ Stated debt: `example.org:8080` reads as the scheme `example.org` and is REFUSED. A heuristic
 * ("a scheme of more than one character followed by digits is a port") would make this function
 * guess, and a guess at this border is exactly what a guard may not do. The user's way out is to
 * type `https://example.org:8080`, which is accepted.
 */
fun normalizeLinkUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    // ⛔ The ONE thing this face adds, and the ONE place it may be added: a scheme, for a string
    // someone has just typed into the dialog. Everything else is [linkUrlFromHref]'s answer, so
    // the two faces can never disagree about which schemes are allowed.
    if (SCHEME.find(trimmed) == null) return "https://" + trimmed
    return linkUrlFromHref(trimmed)
}

/**
 * The link a [selection] is inside of, or null.
 *
 * For a cursor `c`: the link with `start < c < end`, ⛔ STRICTLY — the same bounds the remap's
 * non-stickiness uses, so "the button is lit" and "what I type here joins the link" are one
 * answer. At either border the cursor is outside, and typing there writes plain text.
 * For a real selection: the link that covers ALL of it, so the dialog cannot offer to edit a link
 * that only overlaps what is selected — the line breaks a list stands for set aside, exactly as
 * [stylesAt] sets them aside (see [linkAcrossBreaks]).
 */
fun linkAt(body: RichBody, selection: Span): Link? =
    if (selection.isEmpty) {
        val c = selection.start
        body.links.firstOrNull { it.span.start < c && c < it.span.end }
    } else {
        body.links.firstOrNull { it.span.start <= selection.start && it.span.end >= selection.end }
            ?: linkAcrossBreaks(body, selection)
    }

/**
 * The one link a selection running over SEVERAL items carries, or null.
 *
 * ⛔ Not a nicety, the counterpart of [normaliseLinks]'s cut. `</li><li>` carries no character, so
 * a link over two whole items is STORED as two, and no single one of them covers the selection.
 * Read as "no link", selecting both items and reopening the dialog offers to CREATE one with an
 * empty address, and "remove the link" is not offered at all — while [removeLink], which only
 * needs an overlap, would have done it. This is [coversAcrossBreaks]'s defect, at the same border.
 *
 * ⛔ And the URL must be the SAME all the way: two addresses selected together are not "a link"
 * to re-address, and answering one of them would let the dialog overwrite the other in one tap.
 * The FIRST piece is the answer, so the dialog shows the address the selection starts on.
 */
private fun linkAcrossBreaks(body: RichBody, target: Span): Link? {
    val hard = hardBreaks(body.text, body.blocks)
    if (hard.isEmpty()) return null
    var first: Link? = null
    var p = target.start
    while (p < target.end) {
        if (p in hard) { p++; continue }
        val piece = body.links.firstOrNull { it.span.start <= p && p < it.span.end } ?: return null
        if (first == null) first = piece else if (piece.url != first.url) return null
        p = piece.span.end
    }
    return first
}

/**
 * Put [url] on the body, in this ORDER — the order IS the rule:
 *  1. a real selection takes the link, and every link it overlaps goes ([text] is ignored: the
 *     characters are already there and replacing them would destroy what the user selected);
 *  2. otherwise a cursor inside a link only changes that link's URL, its span untouched — which is
 *     "edit this link", the second thing the dialog is for;
 *  3. otherwise [text] is INSERTED at the cursor and the link is put on exactly those characters;
 *  4. otherwise (a cursor, no link, no text) nothing happens at all.
 *
 * ⚠ [url] is stored as given: it is [normalizeLinkUrl]'s answer that must reach here, and the two
 * call sites (the dialog, [fromHtml]) are where that is done.
 */
fun setLink(body: RichBody, selection: Span, url: String, text: String? = null): RichBody {
    if (!selection.isEmpty) {
        val kept = body.links.filterNot { overlaps(it.span, selection) }
        return RichBody(body.text, body.ranges, body.blocks, kept + Link(selection, url))
    }
    val existing = linkAt(body, selection)
    if (existing != null) {
        return RichBody(
            body.text,
            body.ranges,
            body.blocks,
            body.links.map { if (it === existing) Link(it.span, url) else it },
        )
    }
    if (text.isNullOrEmpty()) return body
    val c = selection.start
    val newText = body.text.substring(0, c) + text + body.text.substring(c)
    // ⛔ Through the ordinary edit path, with an EMPTY pending: the styling around the caret must
    // follow the insertion exactly as it does for a keystroke, and the inserted label must not
    // inherit the bold that ends where it starts — the user asked for a link, not for a style.
    // ⚠ And the BLOCKS come from that same remap: inserting a label inside an item must leave the
    // item a list item, which only `remapBlocks` knows how to say.
    val moved = remapAfterEdit(body, newText, Span(c, c), Span(c + text.length, c + text.length), emptySet())
    return RichBody(newText, moved.ranges, moved.blocks, moved.links + Link(Span(c, c + text.length), url))
}

/**
 * Take the link off: every link the selection touches, or the one the cursor is strictly inside.
 * ⛔ The TEXT stays — this is "remove the link", never "delete the linked words".
 */
fun removeLink(body: RichBody, selection: Span): RichBody {
    val kept = if (selection.isEmpty) {
        val at = linkAt(body, selection) ?: return body
        body.links.filterNot { it === at }
    } else {
        body.links.filterNot { overlaps(it.span, selection) }
    }
    return RichBody(body.text, body.ranges, body.blocks, kept)
}

/** Two half-open spans share at least one character. */
private fun overlaps(a: Span, b: Span): Boolean = a.start < b.end && b.start < a.end


/**
 * The body's plain-text alternative: the styling stripped, the list markers spelled out, and every
 * link written the way Thunderbird writes one — `the text <the url>` — so a reader with no HTML
 * still gets the address.
 *
 * The markers are `- ` on every line of a bullet block, `1. `, `2. `… on a numbered one, the
 * numbering starting again at 1 for each block. ⛔ These markers exist HERE and nowhere else:
 * `body.text` is what was typed, it carries none of them, and it is what the editor shows and what
 * the dirty check compares.
 *
 * ⭐ The address is written ONCE when the text of the link IS the URL: `https://x` and not
 * `https://x <https://x>`, which is what pasting an address and linking it gives.
 *
 * ⛔ ONE pass, and the URL goes where the link ENDS, not at the end of the line: a link may stop
 * in the middle of an item, and `- le site <https://x> et la suite` is the sentence, while
 * `- le site et la suite <https://x>` is another one.
 *
 * ⛔ This is the ONE plain-text answer for everything that leaves the composer — the send's text
 * alternative and the draft's text part alike. Two rules would let a message and its draft say
 * different things about the same link.
 */
fun toPlainText(body: RichBody): String {
    if (body.blocks.isEmpty() && body.links.isEmpty()) return body.text
    val text = body.text
    val marker = arrayOfNulls<String>(body.lineCount)
    for (b in body.blocks) {
        var n = 1
        for (l in b.lines) {
            marker[l] = if (b.kind == BlockKind.BULLET) "- " else "$n. "
            n++
        }
    }
    // The links are normalised: sorted and never overlapping, so no two of them end at one offset.
    val endsAt = body.links.associateBy { it.span.end }
    val out = StringBuilder(text.length + 16)
    var line = 0
    marker[0]?.let { out.append(it) }
    for (i in 0..text.length) {
        endsAt[i]?.let { l ->
            val label = text.substring(l.span.start, l.span.end)
            if (label != l.url) out.append(" <").append(l.url).append('>')
        }
        if (i == text.length) break
        val c = text[i]
        out.append(c)
        if (c == '\n') { line++; marker[line]?.let { out.append(it) } }
    }
    return out.toString()
}
