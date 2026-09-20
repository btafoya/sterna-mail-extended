package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: what the table DECIDES is executed next door
 * ([JmapChosenSpecialFolderTest]), and what nothing in this module can run is the three gestures
 * that build one. `MailRepository` and `MailSender` both need a live `JmapClient`, a concrete class
 * with no seam to stand in for it.
 *
 * `MailSender` is swept by name because it is the one that got left behind: it builds the table for
 * a send on behalf of a delegated address, from the login's own folders, and no other lint reads
 * that file. A table built by hand there is a server listing two folders with one role and a sent
 * copy filed in the one the election dispossessed, invisible in the drawer.
 */
class JmapRoleTableIsBuiltOnceTest {

    private val swept = listOf("MailRepository", "MailSender", "MailFolders", "MailDrafts", "MailAccounts")

    private fun lines(): List<String> = swept.flatMap { file ->
        DaoQuerySource.mailSource(file).lines().map { it.trim() }.map { "$file: $it" }
    }

    @Test fun `no gesture builds a role table by hand`() {
        assertEquals(
            "a line pairs a mailbox's own role with its id again. That is a table without an " +
                "election: a role stated twice by the server keeps whichever the map wrote last, " +
                "and the folder the drawer and the search call the holder is the other one. " +
                "jmapRolesToMailboxId is where that is settled, for the three of them.",
            emptyList<String>(),
            lines().filter { Regex("""\.role\?\.let""").containsMatchIn(it) },
        )
    }

    @Test fun `the three tables are built by the one function that elects`() {
        assertEquals(
            "a call of jmapRolesToMailboxId appeared, vanished or changed its arguments. The two " +
                "sessions read the account's own pinned folders; the on-behalf send passes none, " +
                "because the folders it lists belong to the login and a pinned folder is stored " +
                "on the account record it was pinned from.",
            listOf(
                "MailRepository: rolesToMailboxId = jmapRolesToMailboxId(mailboxes, credentials.specialFolders),",
                "MailRepository: val roles = jmapRolesToMailboxId(mailboxes, credentials.specialFolders)",
                "MailSender: jmapRolesToMailboxId(client.getMailboxes(ctx.session, submissionAccountId, ctx.auth), emptyMap())",
            ),
            lines().filter { "jmapRolesToMailboxId(" in it },
        )
    }
}
