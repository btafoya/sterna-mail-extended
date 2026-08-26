package app.sterna.ui.message

import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [originalSenderToShow], EXECUTED — whether the reader gets an "Original sender" line at all, and
 * what it says (#160).
 *
 * ⛔ Nothing here recomputes the rule. Header values and `from` lists are literals, and every
 * expectation is written out field by field: an expectation derived from the code under test is a
 * copy of the decision, and inverting the delivered condition would leave it green.
 *
 * ⭐ The decision is a plain function and not a branch inside the panel's composable, and this
 * test is why that matters: R8 has already inlined a small `@Composable` of this module until it
 * painted nothing at all (`Monogram`, kept by an explicit rule), so a guard living in the painting
 * is only ever proven by a bench run on a release build. Here it is proven by the build itself.
 */
class OriginalSenderToShowTest {

    private val alias = listOf(EmailAddress(name = "emon", email = "alias@alias.example"))

    @Test fun `a name and an angle-bracket address are split`() {
        val shown = originalSenderToShow("emon <noreply@codeberg.org>", alias)

        assertNotNull(shown)
        assertEquals("emon", shown!!.name)
        assertEquals("noreply@codeberg.org", shown.email)
    }

    @Test fun `a bare address with no name is read whole`() {
        val shown = originalSenderToShow("noreply@codeberg.org", alias)

        assertNotNull(shown)
        assertNull("nothing precedes the address, so there is no name", shown!!.name)
        assertEquals("noreply@codeberg.org", shown.email)
    }

    /**
     * ⛔ Case is not identity. Addresses come back from a relay in whatever casing it felt like,
     * and showing the SAME address twice under two labels is not a signal — it is noise on the
     * one screen this feature exists to make legible.
     */
    @Test fun `an origin equal to the From, in another casing, shows nothing`() {
        assertNull(
            originalSenderToShow(
                "NoReply@Codeberg.org",
                listOf(EmailAddress(name = "emon", email = "noreply@codeberg.org")),
            ),
        )
    }

    /**
     * ⛔ THE refusal. This header is entirely sender-controlled, and the line it feeds is read as
     * "here is who really wrote to you". A value with nothing address-shaped in it must produce no
     * line at all rather than present arbitrary text under that promise.
     */
    @Test fun `a value with no address in it shows nothing`() {
        assertNull(originalSenderToShow("(aucune adresse ici)", alias))
        assertNull(originalSenderToShow("unknown", alias))
    }

    @Test fun `an absent or blank header shows nothing`() {
        assertNull(originalSenderToShow(null, alias))
        assertNull(originalSenderToShow("   ", alias))
        assertNull(originalSenderToShow("", alias))
    }

    /** Nothing to compare against is not a reason to stay silent: a cold cache has no From yet. */
    @Test fun `an empty From does not suppress the line`() {
        val shown = originalSenderToShow("Ann Lee <ann@example.org>", emptyList())

        assertNotNull(shown)
        assertEquals("Ann Lee", shown!!.name)
        assertEquals("ann@example.org", shown.email)
    }
}
