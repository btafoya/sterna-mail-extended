package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span

/**
 * What the body field DRAWS in front of a list's lines (#131): `• ` on every line of a bullet
 * block, `1. `, `2. `… on a numbered one, the numbering starting again at 1 for each block.
 *
 * NOT A CHARACTER OF THIS ENTERS THE MODEL. `body.text` is what was typed; the markers exist in
 * the TEXT LAYER only, inside the `VisualTransformation` handed to the `BasicTextField`
 * ([bodyTransformation]). That is what
 * makes the dirty check, the save, the send and the HTML see the same text they saw before a list
 * was ever toggled — and it is why an [OffsetMapping] is required: the caret, the selection, the
 * drag handles and the IME all speak in ORIGINAL offsets and must be moved across the markers.
 *
 * No `ParagraphStyle` / `TextIndent` (arbitrated, spec § 4): their boundaries fall on the `\n`,
 * which Compose renders as a phantom empty line. A wrapped line coming back to the margin is the
 * price paid, deliberately.
 */
fun markedText(text: AnnotatedString, blocks: List<Block>): TransformedText {
    // FIRST LINE, and that is the whole of it: a body with no list is handed straight back with
    // the identity mapping, so nothing in the field's caret arithmetic changes and NOTHING is
    // walked. Placed after `lineStarts`, this "free" path scanned every character of the body and
    // allocated a list per line on every recomposition — on a 324 kB reply (the body this screen
    // is sized for, see [ComposerResumeSlot]) that is the frequent path made expensive, in a field
    // whose smoothness is already the subject of #5, #6 and #26.
    if (blocks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val starts = lineStarts(text.text)
    val markers = markersOf(starts.size, blocks)
    // A block that falls entirely outside this text marks nothing: same answer, same mapping.
    if (markers.all { it.isEmpty() }) return TransformedText(text, OffsetMapping.Identity)
    val out = AnnotatedString.Builder()
    for (line in starts.indices) {
        out.append(markers[line])
        // A SUBSEQUENCE of the original, never its plain `text`: the inline styling (#131) rides
        // along on its own, so a bold word inside a list item stays bold and stays put.
        val from = starts[line]
        val to = if (line + 1 < starts.size) starts[line + 1] else text.length
        out.append(text.subSequence(from, to))
    }
    return TransformedText(out.toAnnotatedString(), MarkerOffsets(starts, markers, text.length))
}

/**
 * THE body field's one and only `VisualTransformation`, and it carries BOTH halves (#131): the
 * inline styling ([styledBody]) laid on the text, then the list markers ([markedText]) inserted
 * in front of the lines.
 *
 * Both halves, because a field has exactly ONE of these and the styling has nowhere else left
 * to go: a span in the field's VALUE destroys the IME's composing region on every keystroke (the
 * mechanism is written out over [styledBody]). So the styling came down here, to the layer the
 * IME never sees.
 *
 * The ORDER is not a taste. The spans are put on first, at the model's own offsets, and
 * [markedText] then rebuilds the text out of `subSequence`s, which carries them across the
 * markers: a bold word inside a bullet item stays bold and stays put. Laid the other way round,
 * every span after the first marker would sit two or three characters to the left of its word.
 *
 * And the styling adds NO arithmetic: a span moves no character, so the [OffsetMapping] is
 * still `MarkerOffsets` (or the identity), untouched — the half that governs the caret, the drag
 * handles and the IME keeps being pinned by exactly the same property test as before.
 */
fun bodyTransformation(rich: RichBody, linkColor: Color): VisualTransformation =
    object : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText =
            markedText(styledBody(text, rich, linkColor), rich.blocks)
    }

/**
 * The list kind covering EVERY line the selection touches, or null — what the two list buttons
 * show as `selected`.
 *
 * Like [app.sterna.core.data.text.toggleBlock] and unlike the inline styles, an EMPTY selection
 * is not "nothing": the cursor's own line is the target, because that is the gesture in Gmail.
 * The two must agree line for line, or the lit button would not be the one that turns the list
 * off; `RichBodyMarkersTest` pins that agreement by running both.
 */
fun listKindAt(body: RichBody, selection: Span): BlockKind? {
    val starts = lineStarts(body.text)
    val first = lineOf(starts, selection.start)
    // A selection stopping exactly at the start of a line does not reach into it.
    val last = lineOf(starts, if (selection.isEmpty) selection.start else selection.end - 1)
    val kind = kindOfLine(body.blocks, first) ?: return null
    for (line in first..last) if (kindOfLine(body.blocks, line) != kind) return null
    return kind
}

/** The marker each line is drawn with, `""` for a line no block covers. */
private fun markersOf(lineCount: Int, blocks: List<Block>): List<String> {
    val out = MutableList(lineCount) { "" }
    for (block in blocks) {
        var n = 1
        for (line in block.lines) {
            if (line in 0 until lineCount) {
                out[line] = if (block.kind == BlockKind.BULLET) BULLET_MARKER else "$n. "
            }
            n++
        }
    }
    return out
}

/**
 * The two halves of the mapping, and the half that breaks everything visible if it is wrong.
 *
 * `originalToTransformed(o)` = `o` plus every marker up to AND INCLUDING the line holding `o`, so
 * an offset at the start of a line lands AFTER that line's marker — the caret sits on the text,
 * never between the bullet and the word.
 *
 * `transformedToOriginal(t)` sends anything INSIDE a marker back to its line's first character,
 * which is the only original offset that marker stands for.
 *
 * Property, pinned in `RichBodyMarkersTest`: the round trip is the identity for every offset of
 * the text, and both directions are monotonic.
 */
private class MarkerOffsets(
    private val starts: List<Int>,
    private val markers: List<String>,
    private val length: Int,
) : OffsetMapping {
    /** How many marker characters stand before line `l` in the transformed text. */
    private val before: IntArray = IntArray(starts.size + 1).also {
        for (l in starts.indices) it[l + 1] = it[l] + markers[l].length
    }

    /** Where each line starts in the TRANSFORMED text (its marker included). */
    private val transformedStarts: List<Int> = starts.mapIndexed { l, s -> s + before[l] }

    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, length)
        val line = lineOf(starts, o)
        return o + before[line] + markers[line].length
    }

    override fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, length + before[starts.size])
        val line = lineOf(transformedStarts, t)
        val d = t - transformedStarts[line]
        return if (d <= markers[line].length) starts[line]
        else (starts[line] + d - markers[line].length).coerceAtMost(length)
    }
}

/** The offset each line starts at; its size is the line count. */
private fun lineStarts(text: String): List<Int> {
    val out = ArrayList<Int>()
    out.add(0)
    for (i in text.indices) if (text[i] == '\n') out.add(i + 1)
    return out
}

/** The index of the line holding [offset], given the line starts; the `\n` belongs to its line. */
private fun lineOf(starts: List<Int>, offset: Int): Int {
    val found = starts.binarySearch(offset.coerceAtLeast(0))
    return if (found >= 0) found else (-found - 2).coerceIn(0, starts.size - 1)
}

private fun kindOfLine(blocks: List<Block>, line: Int): BlockKind? =
    blocks.firstOrNull { line in it.lines }?.kind

/** Two characters, marker plus one space, exactly like the `- ` of `toPlainText`. */
private const val BULLET_MARKER = "• "
