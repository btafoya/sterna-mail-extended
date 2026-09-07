package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **A Cc that is nothing but a group must read as UNKNOWN, never as empty** — executed on a real
 * [ImapMailService] over a socket, from the ENVELOPE bytes to the set the destroy decision reads.
 *
 * The data loss this holds shut, in the order it happened:
 *
 * 1. a draft the server keeps in Drafts whose only addressing header is
 *    `Cc: undisclosed-recipients:;`. An IMAP ENVELOPE hands that over as RFC 5322 group
 *    delimiters — a start `(NIL NIL "undisclosed-recipients" NIL)` and an end `(NIL NIL NIL NIL)`,
 *    neither of which is an address;
 * 2. both were dropped, so [ccAndBccOf] read an EMPTY cc off a message that has one;
 * 3. `copiedAddressesOrNull(emptyList())` answers `emptySet()` — "read, and there is nothing in
 *    it" — and `draftAddressingIsCarried(emptySet(), …)` answers true: the replacement provably
 *    carries everything the original had. The server copy is EXPUNGED, taking the only remaining
 *    trace of the header with it. Silently.
 *
 * So the assertion below is `assertNull`, never `isNullOrEmpty()`: `null` (unknown, keep the
 * original) and `emptySet()` (empty, destroying is safe) are the two answers the defect confused,
 * and a test that accepts either proves nothing. [ccAndBccOfADraftWithNeitherCcNorBcc] is the
 * other side of that same coin and is not optional — without it, "always answer null" passes
 * every other test here and stops every draft in the app from ever being replaced in place.
 *
 * What runs above the socket is only this read. The save that consumes it
 * ([DraftSave.draftAddressingIsCarried]) needs Room and cannot be built in a JVM; its decision is
 * executed as a pure function in `DraftSaveTest`, including on the very entry the group produces.
 */
class GroupCcIsUnknownNotEmptyTest {

    /**
     * THE case: `Cc: undisclosed-recipients:;`, i.e. a group start and its end, and no bcc.
     * Unknown — the original must be kept.
     */
    @Test(timeout = 30_000)
    fun `a Cc holding only an empty group reads as unknown`() {
        ScriptedImapServer(folderWith(cc = GROUP_WITH_END, bcc = "NIL")).use { server ->
            val read = runBlocking { ccAndBccOf(server) }
            assertNull(
                "⛔ `Cc: undisclosed-recipients:;` is addressing that could not be resolved to an " +
                    "address, i.e. UNKNOWN. Answering an empty set here tells the save that the " +
                    "replacement carries everything the original had, and the copy still holding " +
                    "the header is expunged. Got $read",
                read,
            )
            assertEquals(
                "and it is read off the draft the caller named, in the folder it named",
                listOf(
                    // Every session opens by asking whether the server knows the RFC 2971 ID
                    // command (#173); this fixture answers without it, so nothing is named.
                    "CAPABILITY",
                    "SELECT \"$DRAFTS\"",
                    "UID FETCH $UID (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])",
                ),
                server.issued(),
            )
        }
    }

    /**
     * A group start with NO end. HYPOTHETICAL, and kept on purpose: no server here has been
     * seen to send it. The bench (`emu`/Stalwart, 2026-08-26) showed the opposite — a bare atom
     * `Cc: undisclosed-recipients` comes back as `cc = NIL`, i.e. not at all, which is why the
     * APPEND now writes `undisclosed-recipients:;` (`localDraftOutgoing`). This pins the reading
     * for a server that would hand a half-spelled group over: still unknown, so a second save is
     * not the one that authorises the expunge.
     */
    @Test(timeout = 30_000)
    fun `a Cc holding a group start with no end reads as unknown too`() {
        ScriptedImapServer(folderWith(cc = GROUP_START_ONLY, bcc = "NIL")).use { server ->
            assertNull(
                "the second open of the same draft must not answer differently from the first",
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * THE witness that gives the two above their meaning: a real address in the Cc comes back
     * as that address, not as `null`. Without it, "answer unknown whenever a Cc is present" passes
     * everything above, and no IMAP draft with a Cc is ever replaced in place again — it would be
     * duplicated on the server at every save.
     */
    @Test(timeout = 30_000)
    fun `a Cc holding a real address reads as that address`() {
        ScriptedImapServer(folderWith(cc = COPY_CAT, bcc = "NIL")).use { server ->
            assertEquals(
                "a readable Cc is read, and the destroy decision gets a set it can compare",
                setOf("copy@masto.top"),
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * THE OTHER witness, and the one the defect's whole shape rests on: a draft with neither Cc
     * nor Bcc answers an EMPTY SET — empty and KNOWN. This is the answer that keeps authorising
     * the replace-in-place of the overwhelmingly common draft, and it is what the first test has
     * to be different from.
     */
    @Test(timeout = 30_000)
    fun ccAndBccOfADraftWithNeitherCcNorBcc() {
        ScriptedImapServer(folderWith(cc = "NIL", bcc = "NIL")).use { server ->
            assertEquals(
                "no copied parties at all is a KNOWN empty addressing — nothing to lose, the " +
                    "server copy may be replaced in place",
                emptySet<String>(),
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    /**
     * Both halves are read together (`message.cc + message.bcc`), so a group naming nobody in the
     * BCC makes the whole read unknown even though the Cc beside it is perfectly readable. A draft
     * saved with `Bcc: undisclosed-recipients:;` loses its blind copies exactly the same way.
     */
    @Test(timeout = 30_000)
    fun `an empty group in the Bcc makes the read unknown though the Cc is readable`() {
        ScriptedImapServer(folderWith(cc = COPY_CAT, bcc = GROUP_WITH_END)).use { server ->
            assertNull(
                "one unreadable entry on either side makes the read unknown; a set of just " +
                    "copy@masto.top would say the bcc was empty, and it is not",
                runBlocking { ccAndBccOf(server) },
            )
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * The shipped call, POSITIONALLY: folder then uid, the order the save and the send both pass
     * them in. Swapped at the declaration it would still compile, and the SELECT would name a
     * number — the command list in the first test is what catches that.
     */
    private suspend fun ccAndBccOf(server: ScriptedImapServer): Set<String>? =
        service().ccAndBccOf(credentials(server), DRAFTS, UID, ImapBudget.NO_BUDGET)

    /** A Drafts folder holding one message, UID [UID], whose envelope carries [cc] and [bcc]. */
    private fun folderWith(cc: String, bcc: String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") -> envelope(cc, bcc) + "$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    /** One `UID FETCH` row. Ten ENVELOPE slots: cc is the 7th and bcc the 8th, and getting those
     *  two the wrong way round is its own data loss — see `envelopeAddresses`. */
    private fun envelope(cc: String, bcc: String) =
        "* 1 FETCH (UID $UID FLAGS (\\Draft) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Half written\" " +
            "((\"Alex\" NIL \"alex\" \"masto.top\")) NIL NIL NIL $cc $bcc NIL \"<d1@masto.top>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@masto.top",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val DRAFTS = "Drafts"
        const val UID = 42L

        /** `Cc: undisclosed-recipients:;` on the wire: a mailbox with no host (the group start),
         *  then an entry with nothing at all in it (the group end). Neither is an address. */
        const val GROUP_WITH_END = "((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL))"

        /** A group start alone, with no end marker — a shape no server here has been seen to
         * send (hypothetical), not the result of our own round trip: what this app appends is
         *  `undisclosed-recipients:;`, which comes back start AND end. */
        const val GROUP_START_ONLY = "((NIL NIL \"undisclosed-recipients\" NIL))"

        /** …and a recipient that really is one. */
        const val COPY_CAT = "((\"Copy Cat\" NIL \"copy\" \"masto.top\"))"

        /**
         * An [OAuthTokenRefresher] that is never used and cannot be built — the same trick, and for
         * the same reason, as `ImapDraftAppendGoesOutOnceTest`'s: the REAL service is required, it
         * needs a refresher, and a refresher needs an Android `Context`. `freshAccessToken` returns
         * at `credentials.oauth ?: return null` for a password account, so no field is ever read.
         */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
