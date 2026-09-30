package app.sterna.contacts

import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [rankRecipients] and [usualRecipients] executed on hand-built pools: one test per invariant of the
 * order, so a change of formula that keeps them passes and one that breaks them does not.
 */
class RecipientRankingTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun contact(
        email: String,
        name: String? = null,
        sent: Int = 0,
        lastSent: Long = 0,
        received: Int = 0,
        lastReceived: Long = 0,
        known: Boolean = sent > 0,
    ) = PoolContact(email, name, null, sent, lastSent, received, lastReceived, known)

    private fun rank(query: String, vararg pool: PoolContact, onScreen: Set<String> = emptySet(), limit: Int = 6) =
        rankRecipients(pool.toList(), query, onScreen, now, limit).map { it.email }

    // --- correspondence ----------------------------------------------------------------------------

    @Test fun `a word start passes before a mere substring, whatever the usage`() {
        assertEquals(
            listOf("marie@x.org", "rosemarie@x.org"),
            rank(
                "mar",
                contact("rosemarie@x.org", "Rosemarie", sent = 50, lastSent = now),
                contact("marie@x.org", "Marie"),
            ),
        )
    }

    @Test fun `the words are the name's, the local part's split on dot underscore dash plus, and the domain's labels`() {
        val starts = mapOf(
            "dup" to contact("jean.dupont@x.org"),
            "b" to contact("a_b-c+d@x.org"),
            "c" to contact("a_b-c+d@x.org"),
            "d" to contact("a_b-c+d@x.org"),
            "acme" to contact("z@mail.acme.org"),
            "sophie" to contact("q@x.org", "Anne-Sophie Martin"),
            "mart" to contact("q@x.org", "Anne-Sophie Martin"),
            "dupont@x" to contact("jean.dupont@x.org"),
        )
        starts.forEach { (q, c) -> assertEquals("'$q' starts a word of ${c.email} / ${c.name}", 2, matchStrength(q, c)) }
        assertEquals(1, matchStrength("upon", contact("jean.dupont@x.org")))
        assertEquals(0, matchStrength("zz", contact("jean.dupont@x.org")))
    }

    @Test fun `a substring alone is enough to enter`() {
        assertEquals(listOf("rosemarie@x.org"), rank("emar", contact("rosemarie@x.org")))
    }

    @Test fun `nothing matching, nothing offered, and a blank query offers nothing`() {
        assertEquals(emptyList<String>(), rank("zz", contact("ann@x.org", "Ann", sent = 3, lastSent = now)))
        assertEquals(emptyList<String>(), rank("  ", contact("ann@x.org", "Ann", sent = 3, lastSent = now)))
    }

    @Test fun `an address is found by every name its sources give it, and shows the first of them`() {
        val pool = buildRecipientPool(
            sent = listOf(SentContactRow("p.martin@x.org", "Paul Martin", 1, now)),
            received = listOf(ReceivedContactRow("m.durand@x.org", "Marie Durand", 2, now)),
            book = listOf(ContactRow("p.martin@x.org", "Dr Martin")),
            phone = listOf(ContactSuggestion("m.durand@x.org", "Maman")),
            selves = emptySet(),
        ) + contact("annemarie@y.org", "Annemarie", sent = 40, lastSent = now)

        val marie = rankRecipients(pool, "marie", emptySet(), now)
        assertEquals(listOf("m.durand@x.org", "annemarie@y.org"), marie.map { it.email })
        assertEquals("Maman", marie.first().name)

        val paul = rankRecipients(pool, "paul", emptySet(), now)
        assertEquals(listOf("p.martin@x.org"), paul.map { it.email })
        assertEquals("Dr Martin", paul.single().name)
    }

    // --- normalisation -----------------------------------------------------------------------------

    @Test fun `accents and case are ignored, Unicode included`() {
        assertEquals(listOf("h@x.org"), rank("helene", contact("h@x.org", "Hélène")))
        assertEquals(listOf("e@x.org"), rank("ÉLO", contact("e@x.org", "élodie")))
        assertEquals(listOf("a@x.org"), rank("анна", contact("a@x.org", "Анна")))
        assertEquals(listOf("h@x.org"), rank("HÉLÈNE", contact("h@x.org", "helene")))
    }

    @Test fun `letters with no decomposition stay themselves, a known limit`() {
        assertEquals(emptyList<String>(), rank("lukasz", contact("l@x.org", "Łukasz")))
        assertEquals(listOf("l@x.org"), rank("łukasz", contact("l@x.org", "Łukasz")))
        assertEquals("søren", normalizeForMatch("Søren"))
        assertEquals("straße", normalizeForMatch("Straße"))
        assertEquals("đorđe", normalizeForMatch("Đorđe"))
    }

    @Test fun `the case is folded without the phone's locale`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertEquals("iris", normalizeForMatch("IRIS"))
        } finally {
            Locale.setDefault(before)
        }
    }

    // --- rank --------------------------------------------------------------------------------------

    @Test fun `a newsletter passes behind a contact of the server book it never wrote to`() {
        assertEquals(
            listOf("anna@x.org", "annonces@shop.org"),
            rank(
                "ann",
                contact("annonces@shop.org", "Annonces", received = 400, lastReceived = now),
                contact("anna@x.org", "Anna", known = true),
            ),
        )
    }

    // --- usage -------------------------------------------------------------------------------------

    @Test fun `at equal counts and dates a send weighs more than a reception`() {
        assertEquals(
            listOf("zoe@x.org", "adam@x.org"),
            rank(
                "x.org",
                contact("adam@x.org", "Adam", received = 3, lastReceived = now - 5 * day, known = true),
                contact("zoe@x.org", "Zoe", sent = 3, lastSent = now - 5 * day),
            ),
        )
    }

    @Test fun `one send outweighs three receptions of the same day`() {
        assertEquals(
            listOf("zoe@x.org", "adam@x.org"),
            rank(
                "x.org",
                contact("adam@x.org", "Adam", received = 3, lastReceived = now, known = true),
                contact("zoe@x.org", "Zoe", sent = 1, lastSent = now),
            ),
        )
    }

    @Test fun `at equal counts the more recent passes first`() {
        assertEquals(
            listOf("zoe@x.org", "adam@x.org"),
            rank(
                "x.org",
                contact("adam@x.org", "Adam", sent = 4, lastSent = now - 90 * day),
                contact("zoe@x.org", "Zoe", sent = 4, lastSent = now - 2 * day),
            ),
        )
    }

    @Test fun `at equal dates the more frequent passes first`() {
        assertEquals(
            listOf("zoe@x.org", "adam@x.org"),
            rank(
                "x.org",
                contact("adam@x.org", "Adam", sent = 2, lastSent = now - day, received = 1, lastReceived = now - day),
                contact("zoe@x.org", "Zoe", sent = 3, lastSent = now - day, received = 1, lastReceived = now - day),
            ),
        )
    }

    @Test fun `without any usage the name decides, accents ignored`() {
        assertEquals(
            listOf("b@x.org", "a@x.org", "c@x.org"),
            rank(
                "x.org",
                contact("a@x.org", "Émile", known = true),
                contact("b@x.org", "Ada", known = true),
                contact("c@x.org", "Zed", known = true),
            ),
        )
    }

    @Test fun `the cut to six comes after the ranking, never before`() {
        val strangers = (1..10).map { contact("stranger$it@x.org", received = 100, lastReceived = now) }
        val friend = contact("zz-friend@x.org", "Zz Friend", sent = 1, lastSent = now - 300 * day)
        val shown = rankRecipients(strangers + friend, "x.org", emptySet(), now, 6).map { it.email }
        assertEquals(6, shown.size)
        assertEquals("zz-friend@x.org", shown.first())
    }

    // --- already on screen -------------------------------------------------------------------------

    @Test fun `an address already in any field is not offered again`() {
        assertEquals(
            listOf("anna@x.org"),
            rank("ann", contact("Ann@X.org", "Ann", sent = 9, lastSent = now), contact("anna@x.org", "Anna"), onScreen = setOf("ann@x.org")),
        )
    }

    // --- the usual recipients, on an empty field ---------------------------------------------------

    @Test fun `the usual recipients are those written to, by usage, six at most, none already on screen`() {
        val written = (1..8).map { contact("w$it@x.org", sent = it, lastSent = now) }
        val pool = written + listOf(
            contact("book@x.org", "Book", known = true),
            contact("news@x.org", received = 900, lastReceived = now),
        )
        val usual = usualRecipients(pool, setOf("w8@x.org"), now).map { it.email }
        assertEquals(listOf("w7@x.org", "w6@x.org", "w5@x.org", "w4@x.org", "w3@x.org", "w2@x.org"), usual)
    }

    @Test fun `nobody written to, no usual recipient`() {
        assertTrue(usualRecipients(listOf(contact("book@x.org", known = true), contact("news@x.org", received = 3)), emptySet(), now).isEmpty())
    }

    @Test fun `the formula halves a send's weight at a month`() {
        val fresh = usage(contact("a@x.org", sent = 1, lastSent = now), now)
        val month = usage(contact("a@x.org", sent = 1, lastSent = now - 30 * day), now)
        assertEquals(fresh / 2, month, 1e-9)
    }
}
