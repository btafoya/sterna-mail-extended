package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder Sterna creates is a folder Sterna subscribes to (#174).
 *
 * Direct consequence of the panel that hides unsubscribed folders: an IMAP `CREATE` does NOT
 * subscribe (RFC 3501 §6.3.3 says nothing of the subscription list), so with the setting on the
 * folder the user has just created vanishes from the drawer the instant it appears. Measured
 * against a real server on 2026-08-25.
 *
 * The scope of this, and it is deliberate: **Sterna does not drive subscriptions.** No
 * `UNSUBSCRIBE` anywhere, no `SUBSCRIBE` on any other path, no subscription control on screen.
 * The only mailbox it ever subscribes to is the one it created itself, at the moment it creates
 * it.
 *
 * Read on the WIRE, never from the client's own encoder: what the tests below oppose is
 * "the second command names the same mailbox as the first", and a test that re-derived the
 * encoded name through [encodeModifiedUtf7] would be copying the code it is meant to watch.
 */
class CreatedFolderIsSubscribedTest {

    /** Wire form of "Привет", as a server really announces it (same literal as [MailboxNameTest]). */
    private val helloWire = "&BB8EQAQ4BDIENQRC-"

    /** Everything succeeds unless [refuse] claims the line — which is how a NO gets scripted. */
    private fun server(refuse: (String) -> Boolean = { false }) = FakeImapServer { tag, line ->
        if (refuse(line)) "$tag NO refused\r\n" else ok(tag)
    }

    private fun FakeImapServer.folderCommands(): List<String> =
        issued().filterNot { it.startsWith("LOGOUT") }

    /** The argument [command] carried, as it left — `CREATE "x"` → `"x"`, quotes included. */
    private fun List<String>.argOf(command: String): String =
        single { it.startsWith("$command ") }.removePrefix("$command ")

    /**
     * T1 — the two commands, in that order. Order is asserted because it is the useful one: a
     * SUBSCRIBE sent BEFORE the CREATE is at the mercy of a server that validates the name
     * (RFC 3501 §6.3.6 lets it), and would then be refused on exactly the folder it is for.
     */
    @Test
    fun `creating a folder subscribes to it, in that order`() {
        server().use { server ->
            server.session().use { it.createFolder("Projets") }

            assertEquals(
                // Every session opens by asking whether the server knows the RFC 2971 ID
                // command (#173); this fixture answers without it, so nothing is named.
                listOf("CAPABILITY", """CREATE "Projets"""", """SUBSCRIBE "Projets""""),
                server.folderCommands(),
            )
        }
    }

    /**
     * T2 — the server refuses the SUBSCRIBE. The folder EXISTS: the call must not report a
     * failure the user would read as "the folder was not created". The subscription is a
     * best-effort follow-up, and losing it costs a folder hidden until the user subscribes
     * server-side, never a lost creation.
     */
    @Test
    fun `a refused SUBSCRIBE does not fail the creation`() {
        server { it.startsWith("SUBSCRIBE") }.use { server ->
            val outcome = server.session().use { runCatching { it.createFolder("Projets") } }

            assertTrue(
                "the SUBSCRIBE was never sent, so this proves nothing — ${server.issued()}",
                server.folderCommands().any { it.startsWith("SUBSCRIBE") },
            )
            assertNull("a created folder must not surface as an error", outcome.exceptionOrNull())
        }
    }

    /**
     * T4 — the CREATE is REFUSED and the SUBSCRIBE goes out anyway. This is the case the two
     * separate `runCatching` exist for, and it is the one that matters most on this branch: the
     * failure `createFolder` is documented to swallow is "the mailbox already exists", which is
     * exactly a folder that is there and unsubscribed.
     *
     * The gesture it protects: with "subscribed folders only" on, `Archives 2019` exists on the
     * server, is unsubscribed, and is therefore invisible. The user creates it again from the app
     * to get it back. The CREATE is refused. If the SUBSCRIBE were skipped on that refusal, the app
     * would report success and the folder would stay unreachable, with nothing left to try.
     */
    @Test
    fun `a refused CREATE still subscribes, which is how a hidden folder is recovered`() {
        server { it.startsWith("CREATE") }.use { server ->
            val outcome = server.session().use { runCatching { it.createFolder("Archives 2019") } }

            val issued = server.folderCommands()
            assertTrue(
                "the SUBSCRIBE was skipped because the CREATE failed — the folder stays hidden: $issued",
                issued.any { it.startsWith("SUBSCRIBE") },
            )
            assertEquals("the SUBSCRIBE names another mailbox — $issued", issued.argOf("CREATE"), issued.argOf("SUBSCRIBE"))
            assertNull("an existing folder must not surface as an error", outcome.exceptionOrNull())
        }
    }

    /**
     * T3 — a Cyrillic folder is subscribed under EXACTLY the name it was created under. Both
     * arguments are read off the wire and compared to each other; subscribing to the decoded
     * form would subscribe to another mailbox, or to none.
     */
    @Test
    fun `a Cyrillic folder is subscribed under the very name it was created under`() {
        server().use { server ->
            server.session().use { it.createFolder("Привет") }

            val issued = server.folderCommands()
            assertEquals("""the CREATE did not carry the wire form — $issued""", "\"$helloWire\"", issued.argOf("CREATE"))
            assertEquals("""the SUBSCRIBE names another mailbox — $issued""", issued.argOf("CREATE"), issued.argOf("SUBSCRIBE"))
        }
    }
}
