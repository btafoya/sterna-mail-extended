package app.sterna.ui.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the body field DRAWS for a rich body (#131): the text cut at every span boundary, one
 * `SpanStyle` per segment carrying every family active there. Two overlapping spans that each
 * carry a decoration do not combine on their own in Compose, which is why the cut is per segment.
 */
class RichBodyStyleTest {

    @Test
    fun `overlapping spans become one style per segment, decorations combined`() {
        val rich = RichBody("abcdefghij", mapOf(
            Inline.BOLD to listOf(Span(0, 6)),
            Inline.UNDERLINE to listOf(Span(3, 9)),
            Inline.STRIKE to listOf(Span(3, 9)),
        ))

        val out = toAnnotatedString(rich)

        assertEquals("the text itself must be untouched", "abcdefghij", out.text)
        val both = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        assertEquals(
            "three segments: bold alone, bold with both decorations, both decorations alone",
            listOf(
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 3),
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = both), 3, 6),
                AnnotatedString.Range(SpanStyle(textDecoration = both), 6, 9),
            ),
            out.spanStyles,
        )
    }

    @Test
    fun `italic is a font style, and a plain body carries no span at all`() {
        val out = toAnnotatedString(RichBody("abcd", mapOf(Inline.ITALIC to listOf(Span(1, 3)))))
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(fontStyle = FontStyle.Italic), 1, 3)),
            out.spanStyles,
        )
        assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), toAnnotatedString(RichBody.plain("abcd")).spanStyles)
    }

    @Test
    fun `a reversed selection reads left to right`() {
        assertEquals(Span(2, 7), span(TextRange(7, 2)))
        assertEquals(Span(4, 4), span(TextRange(4)))
    }
}
