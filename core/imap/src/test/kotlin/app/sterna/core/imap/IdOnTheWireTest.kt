package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 2971 `ID` command as it really leaves the client, driven against a scripted server on
 * loopback.
 *
 * What is at stake is a usable account. NetEase (`imap.163.com`, `126.com`, `188.com`,
 * `yeah.net`) lets an anonymous client authenticate and then refuses every SELECT with
 * `NO SELECT Unsafe Login`, so mail syncs nowhere (Codeberg #173). The reporter's transcript
 * shows a bare `ID NIL` accepted and the SELECT still refused: the server wants the client to
 * NAME itself.
 *
 * ⚠ Every assertion below is on the WHOLE command line, never `contains("ID (")`: a mutation that
 * adds a field, drops the quoting or renames the client all LENGTHEN the line, and `contains`
 * would witness none of them. The expected strings are pinned literals — this file must not
 * recompute what the client is supposed to say.
 */
class IdOnTheWireTest {

    /**
     * A server advertising [capabilities] on top of IMAP4rev1. It can announce them the two ways
     * a real server does — in the authentication completion's `[CAPABILITY …]` response code
     * (what Dovecot, Stalwart and Gmail do), or only in answer to a `CAPABILITY` command — and
     * [idAnswer] lets a test make it refuse the `ID` itself.
     */
    private fun server(
        vararg capabilities: String,
        advertiseAtLogin: Boolean = true,
        idAnswer: (String) -> String = { ok(it) },
    ) = FakeImapServer { tag, line ->
        val advertised = capabilities.joinToString(" ")
        when {
            advertiseAtLogin && (line.startsWith("LOGIN") || line.startsWith("AUTHENTICATE")) ->
                "$tag OK [CAPABILITY IMAP4rev1 $advertised] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 $advertised\r\n" + ok(tag)
            line.substringBefore(' ') == "ID" -> idAnswer(tag)
            line.startsWith("SELECT") -> selectResponse(tag, exists = 7)
            else -> ok(tag)
        }
    }

    /**
     * Every `ID` line received, whole. Matched on the VERB and not on a prefix of the arguments,
     * so an `ID NIL` — the anonymous form NetEase accepts and still refuses to serve — is caught
     * by the tests that require no ID at all.
     */
    private fun FakeImapServer.idLines(): List<String> =
        issued().filter { it.substringBefore(' ') == "ID" }

    /**
     * The verbs of every line received, LOGIN and AUTHENTICATE included — the ORDER witness.
     * Verbs only: those two lines carry credentials, which is why [FakeImapServer.issued] drops
     * them, and an assertion message must not print them either.
     */
    private fun FakeImapServer.verbs(): List<String> =
        commands.toList().map { it.substringBefore(' ') }

    /**
     * The fix itself: a server that says it knows ID is told the client's name and version, after
     * the login and before the first SELECT — the window NetEase judges.
     */
    @Test
    fun `a server advertising ID is told the name and version, after login and before SELECT`() {
        server("ID").use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("LOGIN", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * The guard. A server that PUBLISHED its capability list and put no ID in it gets none, and
     * the session is exactly the one it has always been.
     */
    @Test
    fun `a server that never advertised ID is sent none`() {
        server("UIDPLUS").use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            assertEquals(listOf("""SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * ⭐ The heart of the best-effort: a server that advertises ID and then answers NO to it must
     * not cost the user their mailbox. The refusal is swallowed, the SELECT that follows works,
     * and the socket is not leaked (openSession has no `try/finally` to close it).
     */
    @Test
    fun `a server refusing the ID keeps a usable session`() {
        server("ID", idAnswer = { "$it NO [SERVERBUG] ID not supported\r\n" }).use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("LOGIN", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * The OAuth half of the same door: an account authenticating with XOAUTH2 (Outlook/Gmail)
     * names itself too. Its capability list arrives in the AUTHENTICATE completion, which is a
     * different code path from LOGIN's — one that has already been forgotten once.
     */
    @Test
    fun `an XOAUTH2 session names itself too`() {
        server("ID").use { server ->
            val config = server.config.copy(accessToken = "tok-abc")
            val status = ImapClient("1.2.3").openSession(config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("AUTHENTICATE", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * ⛔ A version string carrying CR/LF sends NOTHING. `quote` refuses the newline, the refusal
     * lands in the best-effort wrapper, and the session goes on unnamed — instead of shipping a
     * second, AUTHENTICATED command of somebody else's choosing on the back of the first.
     */
    @Test
    fun `a version carrying a newline sends no ID and no injected command`() {
        server("ID").use { server ->
            val client = ImapClient("9.9\r\nA0001 CREATE \"evil\"")
            val status = client.openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            // Whole list, not just the absence of an ID: the injected CREATE would show up here.
            assertEquals(listOf("""SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * No version known (the default constructor, which is what every test and every non-`:app`
     * caller builds): the name goes out alone, with no empty or `NIL` version field.
     *
     * Also the second discovery route: this server volunteered nothing, so the question is PUT —
     * RFC 2971 §3 forbids sending ID to a server that has not advertised it, so it is asked first
     * and named second, in that order.
     */
    @Test
    fun `a mute server is asked, then named, in that order`() {
        server("ID", advertiseAtLogin = false).use { server ->
            val status = ImapClient().openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail")"""), server.idLines())
            assertEquals(
                listOf("CAPABILITY", """ID ("name" "Sterna Mail")""", """SELECT "INBOX"""", "LOGOUT"),
                server.issued(),
            )
            assertEquals(7, status.exists)
        }
    }

    /**
     * The other half of the question being PUT: a server that volunteered nothing and answers the
     * question without ID in it is not named. Presuming it would understand the command is exactly
     * what RFC 2971 §3 says a client must not do.
     */
    @Test
    fun `a mute server whose answer has no ID is asked and then left alone`() {
        server("UIDPLUS", advertiseAtLogin = false).use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            assertEquals(listOf("CAPABILITY", """SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * ⛔ **A guard against losing mail, not an optimisation.** The question above is put by hand —
     * `parseCapabilities(command("CAPABILITY").untagged)` — and NOT through `capabilities()` /
     * `hasCapability`, because those two CACHE what they learn. `ImapSession.move` reads "the
     * capability probe itself could not be answered" as "the socket is gone, do not copy"; with a
     * cache already filled at connect that question can no longer fail, and a `UID MOVE` cut
     * mid-command walks into the destructive copy fallback. Measured, before this shape existed:
     *
     * `the mail was copied: [CAPABILITY, SELECT "INBOX", UID MOVE 1:3 "Archive", UID COPY 1:3
     * "Archive", LOGOUT]`
     *
     * So the cache must still be COLD when the session is handed out, and the way to see that from
     * outside is that the next reader has to pay for its own round trip. Add a
     * `.also { advertisedCapabilities = it }` to the line in [ImapSession.identify] and this test
     * fails here, next to `ExpungeTest.a move cut mid-command does not enter the fallback on a
     * probe it cannot answer`.
     */
    @Test
    fun `asking for the ID capability leaves the session cache cold`() {
        server("ID", advertiseAtLogin = false).use { server ->
            ImapClient().openSession(server.config).use { session ->
                // The reader after the ID: it must ask again, because nothing was kept.
                assertTrue("the session answered from a cache it should not have", session.hasCapability("ID"))
            }

            assertEquals(
                listOf("CAPABILITY", """ID ("name" "Sterna Mail")""", "CAPABILITY", "LOGOUT"),
                server.issued(),
            )
        }
    }
}
