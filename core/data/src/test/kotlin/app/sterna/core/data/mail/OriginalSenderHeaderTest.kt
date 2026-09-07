package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * [originalSenderHeaderOf], EXECUTED — which header names a forwarded message's real author, and
 * what is done to the value before anything shows it (#160).
 *
 * Nothing here recomputes the rule to decide what to assert. Every input is a literal list of
 * header fields and every expectation is written out by hand: a test that derives its expectation
 * from the code under test is a copy of the decision, and inverting the delivered condition would
 * leave it green.
 *
 * Every fixture carries DECOY `X-` headers around the interesting one, because a real relayed
 * message is thick with them. They pin the half of the rule a name-prefix match alone would miss:
 * `X-Mailer` and `X-Spam-Score` start with `x-` and name nobody.
 */
class OriginalSenderHeaderTest {

    /** [text] as one RFC 2047 base64 encoded-word, the way a relay wraps a non-ASCII name. */
    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    /** The decoys, before and after: present in every fixture, chosen for the trap. */
    private fun around(vararg fields: Pair<String, String>): List<Pair<String, String>> =
        listOf("Received" to "from relay.example.test", "X-Spam-Score" to "0.1") +
            fields.toList() +
            listOf("X-Mailer" to "Relay 4.2", "To" to "alias@alias.example")

    @Test fun `the anonaddy spelling names the author`() {
        assertEquals(
            "Ann Lee <ann@example.org>",
            originalSenderHeaderOf(
                around("X-AnonAddy-Original-From-Header" to "Ann Lee <ann@example.org>"),
            ),
        )
    }

    /**
     * The four spellings in the wild, and one casing nobody would write on purpose. The rule is
     * the SHAPE of the name — `x-` plus `original-from` — and never a list of vendors: a list is
     * a promise to keep chasing whichever alias service ships next.
     */
    @Test fun `every spelling of the same header is recognised, whatever its casing`() {
        assertEquals(
            "a@example.org",
            originalSenderHeaderOf(around("X-Google-Original-From" to "a@example.org")),
        )
        assertEquals(
            "b@example.org",
            originalSenderHeaderOf(around("X-SimpleLogin-Original-From" to "b@example.org")),
        )
        assertEquals(
            "c@example.org",
            originalSenderHeaderOf(around("X-Original-From" to "c@example.org")),
        )
        assertEquals(
            "d@example.org",
            originalSenderHeaderOf(around("x-anonaddy-ORIGINAL-from-header" to "d@example.org")),
        )
    }

    /** The ordinary message: nothing of the shape, nothing read — decoys included. */
    @Test fun `a message that was never relayed reads as null`() {
        assertNull(originalSenderHeaderOf(around("From" to "ann@example.org")))
    }

    /**
     * BOTH halves are required. `Original-From` with no `X-` is not an extension field, and a
     * match on `original-from` alone would let any future standard header of that name be shown
     * as an origin this app never agreed to trust.
     */
    @Test fun `original-from without the x- prefix is not this header`() {
        assertNull(originalSenderHeaderOf(around("Original-From" to "ann@example.org")))
    }

    /**
     * Two fields of the shape in one message: the FIRST occurrence is the one kept.
     *
     * This pins a DETERMINISTIC CHOICE, not a rule about relays. Which of the two names the
     * actual author is NOT established: fields are prepended as a message travels, which would
     * make the first one the OUTERMOST relay's, and no message carrying two of them has ever been
     * seen in this project. The first is taken because the same message must always read the same
     * way; the alternative is guessing from a case nobody has observed.
     *
     * Accepted debt: a chain is reported by one of its hops, possibly the wrong one, and nothing
     * on screen says there was a chain. The label reads "(unverified)" partly for this.
     */
    @Test fun `on a chain of two relays the first occurrence is the one kept`() {
        assertEquals(
            "inner@example.org",
            originalSenderHeaderOf(
                around(
                    "X-SimpleLogin-Original-From" to "inner@example.org",
                    "X-AnonAddy-Original-From-Header" to "outer@alias.example",
                ),
            ),
        )
    }

    /**
     * The sanitising door, crossed. The value is sender-controlled text on its way to a screen, so
     * it goes through the SAME chain every other shown header goes through
     * ([app.sterna.core.imap.MimeParser.decodedHeaderValue]) — encoded-words decoded here.
     */
    @Test fun `an encoded-word value comes back decoded`() {
        assertEquals(
            "Émile Zola <emile@example.org>",
            originalSenderHeaderOf(
                around("X-Original-From" to word("Émile Zola <emile@example.org>")),
            ),
        )
    }

    /**
     * The other half of the same door, and the reason this header cannot be shown raw: it is
     * ENTIRELY under the sender's control, and a right-to-left override in it would paint a
     * reversed address that reads like somebody else's.
     */
    @Test fun `a bidi override is gone from the value`() {
        val answer = originalSenderHeaderOf(
            around("X-Original-From" to word("Ann ‮gro.elpmaxe@nna")),
        )

        assertEquals("Ann gro.elpmaxe@nna", answer)
        assertFalse("a bidi override reached the screen", answer!!.contains('‮'))
    }

    /** A header that is there and says nothing is not an origin: no line rather than a blank one. */
    @Test fun `a blank value reads as null`() {
        assertNull(originalSenderHeaderOf(around("X-Original-From" to "   ")))
        assertNull(originalSenderHeaderOf(around("X-Original-From" to "")))
    }
}
