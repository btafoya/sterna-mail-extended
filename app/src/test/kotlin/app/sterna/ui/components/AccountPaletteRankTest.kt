package app.sterna.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rank an account-colour swatch is described by: one-based, and absent for a colour the palette
 * does not hold. This executes [AccountPalette.rankOf] only; that `ColourSwatch` hands the rank to
 * its semantics, and what a screen reader then says, are not covered here.
 */
class AccountPaletteRankTest {

    @Test fun `the first colour of the palette is rank one`() {
        assertEquals(1, AccountPalette.rankOf(AccountPalette.colors.first()))
    }

    @Test fun `the last colour of the palette carries the palette's size`() {
        assertEquals(AccountPalette.colors.size, AccountPalette.rankOf(AccountPalette.colors.last()))
    }

    @Test fun `the yellow is the third colour`() {
        assertEquals(3, AccountPalette.rankOf(Color(0xFFFDD835)))
    }

    @Test fun `a colour outside the palette has no rank`() {
        assertNull(AccountPalette.rankOf(Color(0xFF123456)))
    }
}
