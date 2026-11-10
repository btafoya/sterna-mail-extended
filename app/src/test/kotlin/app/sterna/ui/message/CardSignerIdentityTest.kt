package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signer's identity as the status card prints it inside the verdict line ([cardSignerIdentity]). */
class CardSignerIdentityTest {

    private val longIdentity =
        "Maximilian von Hohenzollern-Sigmaringen, Head of Accounting and Treasury " +
            "<maximilian.von.hohenzollern-sigmaringen@accounting.example.test>"

    @Test fun `a short identity is left whole`() {
        assertEquals("Alice <alice@example.com>", cardSignerIdentity("Alice <alice@example.com>"))
    }

    @Test fun `an identity of exactly the bound is left whole`() {
        val atBound = "a".repeat(CARD_SIGNER_IDENTITY_MAX - "@example.test".length) + "@example.test"
        assertEquals(CARD_SIGNER_IDENTITY_MAX, atBound.length)
        assertEquals(atBound, cardSignerIdentity(atBound))
    }

    @Test fun `a long identity is cut to the bound in the middle, keeping its head and its domain`() {
        val shown = cardSignerIdentity(longIdentity)!!
        assertEquals(CARD_SIGNER_IDENTITY_MAX, shown.length)
        assertEquals("one ellipsis", 1, shown.count { it == '…' })
        val cut = shown.indexOf('…')
        assertTrue("the ellipsis sits in the middle, was at $cut of $shown", cut in 20..44)
        assertTrue("head kept, was $shown", longIdentity.startsWith(shown.substring(0, cut)))
        assertTrue("tail kept, was $shown", longIdentity.endsWith(shown.substring(cut + 1)))
        assertTrue("the domain stays in the tail, was $shown", shown.endsWith("accounting.example.test>"))
    }

    @Test fun `one past the bound is already cut`() {
        val overBound = "b".repeat(CARD_SIGNER_IDENTITY_MAX + 1 - "@example.test".length) + "@example.test"
        val shown = cardSignerIdentity(overBound)!!
        assertEquals(CARD_SIGNER_IDENTITY_MAX, shown.length)
        assertTrue("was $shown", shown.endsWith("@example.test"))
    }

    /** Both parities on each side, so the head and the tail each meet a cut that lands mid-pair. */
    @Test fun `the cut never splits a character outside the basic plane`() {
        val emoji = "\uD83D\uDD12"
        for (prefix in listOf("", "x")) for (suffix in listOf(" <s@example.test>", " <si@example.test>")) {
            val identity = prefix + emoji.repeat(40) + suffix
            val shown = cardSignerIdentity(identity)!!
            assertTrue("was ${shown.length} long", shown.length in CARD_SIGNER_IDENTITY_MAX - 2..CARD_SIGNER_IDENTITY_MAX)
            val units = shown.toCharArray()
            units.forEachIndexed { i, c ->
                if (Character.isHighSurrogate(c)) assertTrue("lone high surrogate at $i in $shown", i + 1 < units.size && Character.isLowSurrogate(units[i + 1]))
                if (Character.isLowSurrogate(c)) assertTrue("lone low surrogate at $i in $shown", i > 0 && Character.isHighSurrogate(units[i - 1]))
            }
            assertTrue("was $shown", shown.endsWith(suffix))
        }
    }

    @Test fun `line breaks, tabs and line separators become a single space`() {
        assertEquals(
            "Alice Liddell <alice@example.com>",
            cardSignerIdentity("Alice\n\n\r\nLiddell\t\u2028\u2029 <alice@example.com>"),
        )
        assertEquals("Bob <bob@example.com>", cardSignerIdentity("Bob\u0000\u001B\u007F\u0085<bob@example.com>"))
    }

    @Test fun `direction controls are removed`() {
        assertEquals("Mallory <m@example.com>", cardSignerIdentity("Mallory <m@example.com>\u202E"))
        assertEquals(
            "Mallory <m@example.com>",
            cardSignerIdentity("\u202AMal\u202B\u202C\u202Dlory\u2066\u2067\u2068\u2069 <m@\u200E\u200F\u061Cexample.com>"),
        )
    }

    @Test fun `spaces at the edges are trimmed`() {
        assertEquals("Carol <c@example.com>", cardSignerIdentity("\n   Carol <c@example.com>  \t"))
        assertEquals("Carol <c@example.com>", cardSignerIdentity("\u3000Carol\u00A0\u2003 <c@example.com>\u00A0"))
    }

    @Test fun `an identity with nothing left to show is absent, so the caller prints its placeholder`() {
        assertNull(cardSignerIdentity(""))
        assertNull(cardSignerIdentity("   "))
        assertNull(cardSignerIdentity("\n\n\r\n\t \u2028"))
        assertNull(cardSignerIdentity("\u202E"))
        assertNull(cardSignerIdentity("\n \u202E \u2066\n"))
    }

    @Test fun `a long identity full of line breaks is bounded after it is cleaned`() {
        val identity = "Eve" + "\n".repeat(60) + "Mallory-" + "x".repeat(40) + "\n".repeat(60) + "<eve@example.test>"
        val shown = cardSignerIdentity(identity)!!
        assertTrue("a line break survived in $shown", shown.none { it == '\n' || Character.isISOControl(it) })
        assertTrue("was $shown", shown.startsWith("Eve Mallory-"))
        assertTrue("was $shown", shown.endsWith(" <eve@example.test>"))
        assertEquals("cleaned to ${"Eve Mallory-".length + 40 + 19} units, cut to the bound, was $shown", CARD_SIGNER_IDENTITY_MAX, shown.length)
    }
}
