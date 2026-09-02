package app.sterna.ui.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span

/** A `TextFieldValue` selection as a [Span]: a reversed selection still reads left to right. */
internal fun span(selection: TextRange): Span = Span(selection.min, selection.max)

/**
 * The body as the text field draws it: the text cut at EVERY span boundary, and one `SpanStyle`
 * per segment carrying every family active there. ⚠ Per segment, not per span: two overlapping
 * spans that each carry a `textDecoration` do not combine on their own — the later one replaces
 * the earlier — so underline and strike-through on the same characters must be handed over as one
 * combined decoration.
 */
fun toAnnotatedString(rich: RichBody): AnnotatedString {
    if (rich.isPlain) return AnnotatedString(rich.text)
    val cuts = sortedSetOf(0, rich.text.length)
    for (spans in rich.ranges.values) for (s in spans) { cuts.add(s.start); cuts.add(s.end) }
    val positions = cuts.toList()
    return buildAnnotatedString {
        append(rich.text)
        for (i in 0 until positions.size - 1) {
            val p = positions[i]
            val q = positions[i + 1]
            val active = rich.ranges.filterValues { spans -> spans.any { it.start <= p && q <= it.end } }.keys
            if (active.isEmpty()) continue
            val decorations = listOfNotNull(
                TextDecoration.Underline.takeIf { Inline.UNDERLINE in active },
                TextDecoration.LineThrough.takeIf { Inline.STRIKE in active },
            )
            addStyle(
                SpanStyle(
                    fontWeight = if (Inline.BOLD in active) FontWeight.Bold else null,
                    fontStyle = if (Inline.ITALIC in active) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                ),
                p, q,
            )
        }
    }
}
