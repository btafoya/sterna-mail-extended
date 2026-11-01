package app.sterna.ui.compose

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyTextDirectionTest {

    @Test
    fun `an English body keeps its signature line on the left`() {
        assertEquals(TextDirection.ContentOrLtr, bodyTextDirection("Hello Sam,\n\nSee you.\n-- \nAlex"))
    }

    @Test
    fun `an Arabic body keeps its signature line on the right`() {
        assertEquals(TextDirection.ContentOrRtl, bodyTextDirection("مرحبا\n-- \nسلام"))
    }

    @Test
    fun `a body without a strong letter keeps the content default`() {
        assertEquals(TextDirection.Content, bodyTextDirection("--\n123"))
        assertEquals(TextDirection.Content, bodyTextDirection(""))
    }

    @Test
    fun `a quote mark before the first letter does not decide`() {
        assertEquals(TextDirection.ContentOrLtr, bodyTextDirection("> hello"))
        assertEquals(TextDirection.ContentOrRtl, bodyTextDirection("> مرحبا"))
    }

    @Test
    fun `digits before an Arabic letter do not decide`() {
        assertEquals(TextDirection.ContentOrRtl, bodyTextDirection("2026 مرحبا"))
    }

    @Test
    fun `a letter outside the BMP is read as one code point`() {
        // U+10900 Phoenician letter alf, right-to-left, a surrogate pair in UTF-16.
        assertEquals(TextDirection.ContentOrRtl, bodyTextDirection("12 𐤀 abc"))
        // U+1D400 mathematical bold capital a, left-to-right.
        assertEquals(TextDirection.ContentOrLtr, bodyTextDirection("-- 𝐀 مرحبا"))
    }
}
