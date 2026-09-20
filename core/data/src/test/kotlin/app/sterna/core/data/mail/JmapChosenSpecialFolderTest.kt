package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.SPECIAL_FOLDER_ROLES
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The JMAP half of the per-account choice of a folder for a special role, executed on both places a
 * JMAP role becomes an effective one: the listing that becomes cache rows (the drawer, the search,
 * the notification banner, and everything read offline) and the role table a gesture routes by (the
 * send's copy, the draft, the archive, the delete).
 *
 * A JMAP id is an opaque server string, not a path, so every assertion here pins the id and the
 * data is built so that matching anything else fails: `mb-07` is NAMED `mb-05`, and the mailbox
 * whose ID spells the path IMAP reserves for the inbox is an ordinary folder.
 */
class JmapChosenSpecialFolderTest {

    /**
     * Every role already carried by the folder the server named, so a choice can only ever take a
     * role away from someone. RFC 8621 §2 asks for one holder per role and says nothing about a
     * server that answers two, so two pairs state one twice: `sent`, which a search walks, and
     * `trash`, which it skips. Only the second pair can show the mark of a lost claim, and without
     * it a loser that keeps no mark is a personal bin beside a shared one coming back into every
     * search result and out of the drawer's keep-list.
     */
    private val listing = listOf(
        Mailbox(id = "mb-01", name = "INBOX", role = "inbox"),
        Mailbox(id = "mb-02", name = "Corbeille", role = "trash"),
        Mailbox(id = "mb-03", name = "Spam", role = "junk"),
        Mailbox(id = "mb-04", name = "Archives", role = "archive"),
        Mailbox(id = "mb-05", name = "Vieux courrier"),
        Mailbox(id = "INBOX", name = "Courrier ancien"),
        Mailbox(id = "mb-07", name = "mb-05"),
        Mailbox(id = "mb-08", name = "Envoyés", role = "sent"),
        Mailbox(id = "mb-09", name = "Sent Items", role = "sent"),
        Mailbox(id = "mb-10", name = "Corbeille partagée", role = "trash"),
    )

    private fun credentials(chosen: Map<String, String>, id: String = "acc-1") = AccountCredentials(
        server = "jmap.example.test",
        username = "p@example.test",
        password = "x",
        id = id,
        protocol = MailProtocol.JMAP,
        specialFolders = chosen,
    )

    /** Every row's role as the cache would hold it, keyed by the mailbox id, through the one call
     *  every JMAP folder-cache write goes through. */
    private fun rows(chosen: Map<String, String>): Map<String, String?> =
        listing.toEntities(credentials(chosen)).associate { it.id to it.role }

    /** The table the send, the draft, the archive and the delete resolve a role through. */
    private fun table(chosen: Map<String, String>): Map<String, String> =
        jmapRolesToMailboxId(listing, chosen)

    @Test fun `with no choice stored the server's own roles stand`() {
        assertEquals(
            mapOf(
                "mb-01" to "inbox",
                "mb-02" to "trash",
                "mb-03" to "junk",
                "mb-04" to "archive",
                "mb-05" to null,
                "INBOX" to null,
                "mb-07" to null,
                "mb-08" to "sent",
                "mb-09" to null,
                "mb-10" to "~trash",
            ),
            rows(emptyMap()),
        )
        assertEquals(
            mapOf(
                "inbox" to "mb-01",
                "trash" to "mb-02",
                "junk" to "mb-03",
                "archive" to "mb-04",
                "sent" to "mb-08",
            ),
            table(emptyMap()),
        )
    }

    @Test fun `the chosen mailbox carries the role on the row and in the table`() {
        SPECIAL_FOLDER_ROLES.forEach { role ->
            assertEquals("pinning $role on mb-05", role, rows(mapOf(role to "mb-05"))["mb-05"])
            assertEquals("pinning $role on mb-05", "mb-05", table(mapOf(role to "mb-05"))[role])
        }
    }

    /**
     * The choice names a mailbox by its JMAP id, which is opaque: nothing here may fall back on the
     * display name. `mb-07` is named `mb-05`, so a rule reading the name would give it every role.
     */
    @Test fun `the choice matches the id and never the display name`() {
        val rows = rows(mapOf("trash" to "mb-05"))
        assertEquals("trash", rows["mb-05"])
        assertEquals(null, rows["mb-07"])
        assertEquals("mb-05", table(mapOf("trash" to "mb-05"))["trash"])
        assertEquals(
            "a name is not an id: nothing may be pinned by it",
            "mb-02",
            table(mapOf("trash" to "Vieux courrier"))["trash"],
        )
    }

    /** A dispossessed junk keeps the mark of a lost claim, which is what holds it out of search. */
    @Test fun `pinning a role dispossesses its automatic holder, and marks it when a search skips it`() {
        val junk = rows(mapOf("junk" to "mb-05"))
        assertEquals("junk", junk["mb-05"])
        assertEquals("~junk", junk["mb-03"])
        assertEquals("mb-05", table(mapOf("junk" to "mb-05"))["junk"])
        val archive = rows(mapOf("archive" to "mb-05"))
        assertEquals("a dispossessed archive is an ordinary folder and stays searchable", null, archive["mb-04"])
    }

    /**
     * The guard nothing on this side already held: with two mailboxes stating one role, a map write
     * keeps whichever came last and a pinned folder can be overwritten by either of them.
     */
    @Test fun `a role stated twice by the server is carried once, and a pin beats both`() {
        val untouched = rows(emptyMap())
        assertEquals("sent", untouched["mb-08"])
        assertEquals("the second holder lost the election and carries no role", null, untouched["mb-09"])
        assertEquals(
            "and a loser on a role a search skips keeps the mark of the claim, with nothing pinned: " +
                "unmarked it is a shared bin back in every search result and out of the drawer",
            "~trash",
            untouched["mb-10"],
        )
        val elsewhere = rows(mapOf("trash" to "mb-05"))
        assertEquals("the mark survives the role being pinned somewhere else", "~trash", elsewhere["mb-10"])
        assertEquals("~trash", elsewhere["mb-02"])
        assertEquals("trash", elsewhere["mb-05"])
        val pinned = rows(mapOf("sent" to "mb-05"))
        assertEquals("mb-05", table(mapOf("sent" to "mb-05"))["sent"])
        assertEquals("sent", pinned["mb-05"])
        assertEquals(null, pinned["mb-08"])
        assertEquals(null, pinned["mb-09"])
        assertEquals(1, pinned.values.count { it == "sent" })
    }

    /**
     * The guard that protects mail: a choice naming a mailbox the server no longer lists falls back
     * on the automatic role. With the role held by nobody, `MailRepository.trashMailboxId` and
     * `MailRepository.archiveMailboxId` take the first folder whose name they recognise, and the
     * message lands in one nobody designated.
     */
    @Test fun `a choice naming a mailbox the server does not list leaves the automatic role alone`() {
        assertEquals("trash", rows(mapOf("trash" to "mb-404"))["mb-02"])
        assertEquals("mb-02", table(mapOf("trash" to "mb-404"))["trash"])
    }

    /** A row carries one role, so the inbox cannot be a target: overwritten, it stops answering
     *  `MailRepository.cachedInboxMailboxId` and under `trash` drops out of every search. */
    @Test fun `the inbox is never honoured as a target`() {
        SPECIAL_FOLDER_ROLES.forEach { role ->
            val rows = rows(mapOf(role to "mb-01"))
            assertEquals("pinning $role on the inbox must change nothing", "inbox", rows["mb-01"])
            assertEquals("and must leave the automatic holder its role", "trash", rows["mb-02"])
            assertEquals("mb-01", table(mapOf(role to "mb-01"))["inbox"])
        }
    }

    /** The reserved path is an IMAP rule about paths. A JMAP id is opaque, so a mailbox whose id
     *  happens to spell it is an ordinary folder and stays pinnable. */
    @Test fun `an id that spells the reserved path is still an ordinary mailbox`() {
        val rows = rows(mapOf("trash" to "INBOX"))
        assertEquals("trash", rows["INBOX"])
        assertEquals("~trash", rows["mb-02"])
        assertEquals("inbox", rows["mb-01"])
        assertEquals("INBOX", table(mapOf("trash" to "INBOX"))["trash"])
    }

    /** `inbox` and `all` are not among the five ([SPECIAL_FOLDER_ROLES]): a stored key naming one
     *  takes nothing, on the row or in the table. */
    @Test fun `a key that is not one of the five roles takes nothing`() {
        val chosen = mapOf("inbox" to "mb-05", "all" to "mb-05")
        assertEquals(null, rows(chosen)["mb-05"])
        assertEquals("mb-01", table(chosen)["inbox"])
        assertEquals(null, table(chosen)["all"])
    }

    /** The marking is the one thing that keeps a dispossessed spam out of every search result. */
    @Test fun `the dispossessed spam stays out of search and the chosen one too`() {
        val rows = rows(mapOf("junk" to "mb-05"))
        val drawer = listOf(
            MailboxIdRole("mb-01", rows["mb-01"]),
            MailboxIdRole("mb-03", rows["mb-03"]),
            MailboxIdRole("mb-05", rows["mb-05"]),
            MailboxIdRole("mb-10", rows["mb-10"]),
        )
        assertEquals(listOf("mb-01"), searchableFolderIds(drawer))
        assertEquals(listOf("mb-03", "mb-05", "mb-10"), excludedSearchFolderIds(drawer))
    }

    /** The row is a role change, not a rename, a reorder or a recount. */
    @Test fun `choosing a mailbox changes its role and nothing else about the row`() {
        val chosen = listing.toEntities(credentials(mapOf("trash" to "mb-05")))
        val automatic = listing.toEntities(credentials(emptyMap()))
        assertEquals(automatic[4].copy(role = "trash"), chosen[4])
        assertEquals(automatic[1].copy(role = "~trash"), chosen[1])
        assertEquals(automatic.drop(5), chosen.drop(5))
    }

    /**
     * The rows are filed under the account whose choice was honoured. Id and choice are read off one
     * object on purpose: a second account's choice cannot reach this listing without its id coming
     * along, and a drawer of one account's folders under another's id is #121's ghost.
     */
    @Test fun `the rows carry the account the choice was read from`() {
        val rows = listing.toEntities(credentials(mapOf("trash" to "mb-05"), id = "acc-2"))
        assertEquals(setOf("acc-2"), rows.map { it.accountId }.toSet())
        assertEquals("trash", rows.single { it.id == "mb-05" }.role)
        assertEquals(
            "the other account pinned nothing and its rows must say so",
            null,
            listing.toEntities(credentials(emptyMap(), id = "acc-3")).single { it.id == "mb-05" }.role,
        )
    }

    /**
     * The eviction of the in-memory JMAP session, which is a single slot for the whole account list
     * and is reused for credentials that compare equal. The choice rides on [AccountCredentials], so
     * changing it makes that comparison false and the next gesture opens a session that reads the
     * new choice. Nothing else drops the session: were the choice to stop counting in this equality,
     * the setting would take effect only at the next start of the app, and until then a send, a
     * draft and a delete would go on filing into the folders the server named.
     *
     * The comparison itself is pinned line for line by `SessionSeamIsNamedTest`, which is all a JVM
     * test can do with it: `MailRepository` cannot be driven through a warm cache here.
     */
    @Test fun `credentials differing only by the pinned folders are not equal`() {
        assertNotEquals(credentials(emptyMap()), credentials(mapOf("sent" to "mb-05")))
        assertNotEquals(credentials(mapOf("sent" to "mb-05")), credentials(mapOf("sent" to "mb-08")))
        assertNotEquals(credentials(mapOf("sent" to "mb-05")), credentials(mapOf("trash" to "mb-05")))
        assertEquals(credentials(mapOf("sent" to "mb-05")), credentials(mapOf("sent" to "mb-05")))
    }
}
