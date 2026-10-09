package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A message moved out of a folder while that folder's delta was between its two change requests
 * came back into the folder's list until the next sync (#252). [stillFiledIn] is executed here; the
 * last three tests read the wiring of `syncMailbox` as text, and the interleaving itself is not
 * replayed here.
 */
class DeltaWritesOnlyItsOwnFolderTest {

    private fun email(id: String, vararg folders: Pair<String, Boolean>) = Email(id = id, mailboxIds = mapOf(*folders))

    private fun kept(mailboxId: String, vararg fetched: Email): List<String> =
        stillFiledIn(mailboxId, fetched.toList()).map { it.id }

    // ---- the decision, executed ------------------------------------------------------------

    @Test fun `a message the server files in this folder is written`() {
        assertEquals(listOf("M1"), kept("inbox", email("M1", "inbox" to true)))
    }

    @Test fun `a message the server files in this folder and another is written`() {
        assertEquals(listOf("M1"), kept("inbox", email("M1", "label" to true, "inbox" to true)))
    }

    @Test fun `a message the server now files elsewhere is not written`() {
        assertEquals(emptyList<String>(), kept("inbox", email("M1", "trash" to true)))
    }

    @Test fun `a message whose folders the server did not report is written`() {
        assertEquals(listOf("M1"), kept("inbox", email("M1")))
    }

    @Test fun `a folder named with false is not a folder the message is in`() {
        assertEquals(emptyList<String>(), kept("inbox", email("M1", "inbox" to false, "trash" to true)))
        assertEquals(emptyList<String>(), kept("inbox", email("M1", "inbox" to false)))
    }

    @Test fun `only the moved message is left out, the rest in the order the server gave`() {
        assertEquals(
            listOf("M1", "M3", "M4"),
            kept(
                "inbox",
                email("M1", "inbox" to true),
                email("M2", "trash" to true),
                email("M3"),
                email("M4", "inbox" to true, "trash" to true),
            ),
        )
    }

    // ---- the wiring, read from the shipped source ------------------------------------------

    private fun codeLinesNaming(needle: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox")
            .lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun `the delta reads what it writes together with the folders the server files it in`() {
        assertEquals(
            "the delta must read through getEmailsWithMailboxIds: getEmailsByIds does not ask for " +
                "`mailboxIds`, every map comes back empty, and an empty map keeps the row",
            listOf("val fetched = client.getEmailsWithMailboxIds(session, accountId, toFetch, auth)"),
            codeLinesNaming("client.getEmails"),
        )
    }

    @Test fun `the delta writes only what the decision kept, under the folder it asked the decision about`() {
        assertEquals(
            "the delta must ask the decision about the mailbox it is syncing",
            listOf("val filed = stillFiledIn(mailboxId, fetched)"),
            codeLinesNaming("stillFiledIn("),
        )
        assertEquals(
            "the delta's row write must take stillFiledIn's answer; the second line is the full " +
                "query's page, which lists the folder and needs no such gate",
            listOf(
                "emailDao.upsertAll(sparingInFlight(filed.map { it.toEntity(localAccountId, mailboxId) }))",
                "emailDao.upsertAll(sparingInFlight(fresh.map { it.toEntity(localAccountId, mailboxId) }))",
            ),
            codeLinesNaming(".toEntity("),
        )
    }

    @Test fun `the delta's log line counts what it read and did not write`() {
        assertEquals(
            "the count of rows left unwritten is the only trace that the gate worked: it is what " +
                "was read minus what was kept, and it closes the `incremental` line",
            listOf(
                "var elsewhere = 0",
                "elsewhere = fetched.size - filed.size",
                "\"moved=\${toRemove.size - toDelete.size} spared=\${spared.size} sweep=\${claim.reason} elsewhere=\$elsewhere\",",
            ),
            codeLinesNaming("elsewhere"),
        )
    }
}
