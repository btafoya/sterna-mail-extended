package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterisation, NOT a fix. `primaryAccounts` is mandatory (RFC 8620 §2), but the model defaults
 * it to an empty map, so a server omitting it is not refused: [JmapSession.mailAccountId] falls back
 * to `accounts.keys.firstOrNull()` — i.e. to the **position of the key in the JSON document**,
 * because kotlinx decodes a `Map` into a `LinkedHashMap`. [JmapSession.mailAccountIds] then puts
 * that same account first, so one arbitrary thing (document order) governs both the primary head
 * and the order of the account list.
 *
 * That fallback is deliberately left as it is: no server has been observed shipping a session
 * without `primaryAccounts`, and any "better" rule (alphabetical, last, hashed) would be just as
 * arbitrary while silently moving which mailbox a user lands in. This test exists so that the
 * behaviour cannot be changed by accident during a cleanup — if the fallback is ever changed on
 * purpose, this test is the file to update, with a reason.
 *
 * Form matters here:
 * - the session is **decoded from JSON**, never built by constructor: a Kotlin `mapOf(…)` is
 *   already a `LinkedHashMap`, so a constructor-built test would keep passing even if kotlinx
 *   started decoding into a `HashMap` — which is precisely what is being pinned;
 * - it decodes with the shipping [JmapClient.DefaultJson], not a local `Json {}`;
 * - both accounts advertise the mail capability, otherwise `mailAccountIds()` degenerates to a
 *   single element and the ordering assertion says nothing;
 * - the ids are chosen so JSON order is the **reverse** of alphabetical order, and the same two
 *   accounts are decoded in both orders. A regression that sorts, hashes, reverses or takes the
 *   last account can satisfy one case, never both.
 */
class JmapSessionPrimaryFallbackTest {
    private val json = JmapClient.DefaultJson

    /** Two mail-capable accounts, listed in the given order, with NO `primaryAccounts` at all. */
    private fun sessionJson(first: String, second: String) =
        """
        {
          "apiUrl": "https://mail.example.com/jmap/api/",
          "accounts": {
            "$first": { "name": "$first@example.com",
                        "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } },
            "$second": { "name": "$second@example.com",
                         "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } }
          }
        }
        """.trimIndent()

    @Test fun withoutPrimaryAccountsTheFirstAccountInTheJsonLeads() {
        val session = json.decodeFromString(JmapSession.serializer(), sessionJson("zulu", "alpha"))

        // "zulu" is alphabetically LAST, yet it leads: only its position in the document explains
        // this head, which rules out an ascending sort. The opposite ordering below, with the same
        // two accounts, rules out a descending one.
        assertEquals("zulu", session.mailAccountId())
        assertEquals(listOf("zulu", "alpha"), session.mailAccountIds())
    }

    @Test fun withoutPrimaryAccountsTheSameAccountsInTheOppositeJsonOrderFlipTheHead() {
        val session = json.decodeFromString(JmapSession.serializer(), sessionJson("alpha", "zulu"))

        // Same two accounts, same capabilities, only the document order changed — and the head
        // changed with it. Any order-independent rule fails one of these two cases.
        assertEquals("alpha", session.mailAccountId())
        assertEquals(listOf("alpha", "zulu"), session.mailAccountIds())
    }
}
