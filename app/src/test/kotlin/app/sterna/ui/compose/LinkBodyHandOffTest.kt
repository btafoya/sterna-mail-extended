package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkBodyHandOffTest {

    @Test fun `a body is handed back once for its token, then never again`() {
        val handOff = LinkBodyHandOff()
        val token = handOff.put("shared text")
        assertNotNull(token)
        assertEquals("shared text", handOff.take(token))
        assertNull(
            "a second take must find nothing: the hand-off holds a ~100 kB body for as long as it " +
                "is not removed, and a token that answers twice hands one share to two composers",
            handOff.take(token),
        )
    }

    @Test fun `a token the hand-off never issued gets nothing, and does not use up the real one`() {
        val handOff = LinkBodyHandOff()
        val token = handOff.put("shared text")
        assertNull(handOff.take("a token from before a process death"))
        assertEquals("shared text", handOff.take(token))
    }

    @Test fun `two composers stacked on each other each take their own body`() {
        val handOff = LinkBodyHandOff()
        val first = handOff.put("first share")
        val second = handOff.put("second share")
        assertNotEquals(first, second)
        assertEquals("first share", handOff.take(first))
        assertEquals("second share", handOff.take(second))
    }

    @Test fun `a claim still answers once the hand-off is empty, as a rotation asks it to`() {
        val handOff = LinkBodyHandOff()
        val token = handOff.put("shared text")
        val claim = LinkBodyClaim(handOff)
        assertEquals("shared text", claim.body(token))
        assertNull(handOff.take(token))
        assertEquals("shared text", claim.body(token))
    }

    @Test fun `a claim on a token the hand-off lost is null, not an error`() {
        assertNull(LinkBodyClaim(LinkBodyHandOff()).body("issued before a process death"))
        assertNull(LinkBodyClaim(LinkBodyHandOff()).body(null))
    }

    @Test fun `a blank body parks nothing and yields no token`() {
        val handOff = LinkBodyHandOff()
        assertNull(handOff.put(""))
        assertNull(handOff.put(" \n"))
        assertNull(handOff.take(null))
    }
}
