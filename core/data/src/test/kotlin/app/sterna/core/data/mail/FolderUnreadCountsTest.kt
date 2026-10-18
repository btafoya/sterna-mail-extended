package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.AccountMailboxId
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.db.AccountMailboxUnread
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.data.db.MailboxIdUnread
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.model.Mailbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unread count of each folder row, from an IMAP server's answer to the screens. Four levels:
 * `loadFolder` run against a scripted server, `MailboxDao.replaceAllKeepingUnread` run on a table
 * held in memory, the call of that write in `refreshImap`, pinned by its whole line, and
 * `toMailbox` run on a cache row, its call in `observeMailboxes` pinned the same way. The last
 * level holds for a JMAP account too; the lint that forbids a second writer of `unreadForList`
 * reads `MailRepository` alone, not the screens. Neither
 * refresh is executed here: `MailRepository` needs its message cache to walk a folder. The write of
 * `refreshAllInboxes` is pinned by [PrefetchStaysInTheInboxWiringTest], every folder-cache write by
 * [SubscribedFoldersWiringTest].
 */
class FolderUnreadCountsTest {

    // -- what the listing brings back ------------------------------------------------------

    @Test(timeout = 30_000)
    fun `a folder that is not opened carries the count the server stated, the opened one its own`() {
        server().use { server ->
            val load = load(server, requested = null)

            assertEquals(
                "INBOX is the folder opened: its count is the SEARCH's three, never a STATUS. " +
                    "Archives was refused its STATUS and carries a placeholder.",
                mapOf("INBOX" to 3, "Corbeille" to 4, "Archives" to 0),
                load.mailboxes.associate { it.id to it.unreadEmails },
            )
            assertEquals(
                "a refused count is unknown, and the load must say so: a zero written as stated " +
                    "erases the count the cache holds",
                setOf("INBOX", "Corbeille"),
                load.unreadStated,
            )
            assertEquals("the header count and the row of the opened folder are one number", 3, load.unread)
            assertEquals(
                "the folder about to be selected is not asked for a count it is about to give",
                listOf("""STATUS "Corbeille" (UNSEEN)""", """STATUS "Archives" (UNSEEN)"""),
                server.issued("STATUS"),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `the folder asked for is the one left out of the counts and given the exact one`() {
        server().use { server ->
            val load = load(server, requested = "Corbeille")

            assertEquals(listOf("""SELECT "Corbeille""""), server.issued("SELECT"))
            assertEquals(
                mapOf("INBOX" to 6, "Corbeille" to 3, "Archives" to 0),
                load.mailboxes.associate { it.id to it.unreadEmails },
            )
            assertEquals(setOf("INBOX", "Corbeille"), load.unreadStated)
            assertEquals(
                listOf("""STATUS "INBOX" (UNSEEN)""", """STATUS "Archives" (UNSEEN)"""),
                server.issued("STATUS"),
            )
        }
    }

    // -- what the cache keeps --------------------------------------------------------------

    @Test fun `a folder the server gave no count for keeps the count the cache holds`() {
        val dao = TableMailboxDao(row("INBOX", unread = 2), row("Archives", unread = 7))

        runBlocking {
            dao.replaceAllKeepingUnread(
                ACCOUNT,
                listOf(row("INBOX", unread = 5), row("Archives", unread = 0)),
                unreadStated = setOf("INBOX"),
            )
        }

        assertEquals(mapOf("INBOX" to 5, "Archives" to 7), dao.unread())
    }

    @Test fun `a new folder the server gave no count for starts at zero, whatever its row says`() {
        val dao = TableMailboxDao(row("INBOX", unread = 2))

        runBlocking {
            dao.replaceAllKeepingUnread(
                ACCOUNT,
                listOf(row("INBOX", unread = 2), row("Projets", unread = 9)),
                unreadStated = setOf("INBOX"),
            )
        }

        assertEquals(mapOf("INBOX" to 2, "Projets" to 0), dao.unread())
    }

    @Test fun `a zero the server stated is a zero`() {
        val dao = TableMailboxDao(row("Archives", unread = 7))

        runBlocking {
            dao.replaceAllKeepingUnread(ACCOUNT, listOf(row("Archives", unread = 0)), unreadStated = setOf("Archives"))
        }

        assertEquals(mapOf("Archives" to 0), dao.unread())
    }

    @Test fun `a folder the server no longer lists is still dropped`() {
        val dao = TableMailboxDao(row("INBOX", unread = 2), row("Ancien", unread = 3))

        runBlocking { dao.replaceAllKeepingUnread(ACCOUNT, listOf(row("INBOX", unread = 2)), unreadStated = emptySet()) }

        assertEquals(mapOf("INBOX" to 2), dao.unread())
    }

    @Test fun `the cached count is read in the transaction that writes over it`() {
        assertTrue(
            "outside a transaction, a count nudged between the read and the write is lost",
            DaoQuerySource.isTransactional("MailboxDao", "replaceAllKeepingUnread"),
        )
    }

    // -- where the refresh writes ----------------------------------------------------------

    @Test fun `the single-folder refresh writes its folders through that write, after its messages`() {
        assertEquals(
            "refreshImap must write the folder rows with the load's own account of which counts " +
                "were stated, and after imapWriteThrough. Lines found:",
            listOf(
                "{",
                "val load = imapWriteThrough(credentials, mailboxId, limit)",
                "mailboxDao.replaceAllKeepingUnread(credentials.id, load.mailboxes, load.unreadStated)",
            ),
            DaoQuerySource.mailFunctionBody("MailRepository", "refreshImap").lines()
                .map { it.trim() }
                .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .take(3),
        )
    }

    // -- what the screens read -------------------------------------------------------------

    @Test fun `a folder row shows the count its cache row holds, whatever messages are cached`() {
        val shown = listOf(row("Projets", unread = 12), row("Archives", unread = 0)).map { it.toMailbox() }

        assertEquals(
            "a folder none of whose messages is cached has its count in its cache row and nowhere else",
            mapOf("Projets" to 12, "Archives" to 0),
            shown.associate { it.id to it.unreadForList },
        )
    }

    @Test fun `a folder from the wire keeps its unread count apart from its total, down to its row`() {
        val stored = Mailbox(id = "inbox-1", name = "Inbox", totalEmails = 40, unreadEmails = 3).toEntity(ACCOUNT)

        assertEquals(
            "toEntity must carry each of the two server counts to its own column",
            40 to 3,
            stored.totalEmails to stored.unreadEmails,
        )
        assertEquals(
            "the drawer must show the three unread messages, not the forty the folder holds",
            3,
            stored.toMailbox().unreadForList,
        )
    }

    @Test fun `the mailbox list is the cache rows of the account, whatever its protocol`() {
        val code = codeLines("MailRepository")
        assertEquals(
            "observeMailboxes must hand the screens the cache rows crossed by toMailbox(), for a " +
                "JMAP account as for an IMAP one. Lines found:",
            listOf(
                "fun observeMailboxes(accountId: String): Flow<List<Mailbox>> =",
                "mailboxDao.observeAll(accountId).map { rows -> rows.map { it.toMailbox() } }",
            ),
            code.drop(code.indexOfFirst { it.startsWith("fun observeMailboxes(") }).take(2),
        )
    }

    @Test fun `nothing in the repository writes over the count a folder row crosses with`() {
        assertEquals(
            "MailRepository must not name unreadForList: the field is filled once, by toMailbox(), " +
                "from the cache row. A count of the cached messages put in its place is zero for " +
                "every folder that was never opened. Lines found:",
            emptyList<String>(),
            codeLines("MailRepository").filter { "unreadForList" in it },
        )
    }

    // -- the harness -----------------------------------------------------------------------

    private fun codeLines(fileName: String): List<String> = DaoQuerySource.mailSource(fileName).lines()
        .map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun load(server: ScriptedImapServer, requested: String?): ImapFolderLoad = runBlocking {
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher()).loadFolder(
            credentials(server),
            requestedMailboxId = requested,
            limit = 1,
            onPage = {},
            cachedPreviewsFor = null,
        )
    }

    /** No `LIST-STATUS`: one STATUS per folder. Archives is refused its own; SEARCH names three unseen. */
    private fun server() = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LIST") ->
                "* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Corbeille\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Archives\"\r\n$tag OK listed\r\n"
            line.startsWith("STATUS \"INBOX\"") -> "* STATUS \"INBOX\" (UNSEEN 6)\r\n$tag OK done\r\n"
            line.startsWith("STATUS \"Corbeille\"") -> "* STATUS \"Corbeille\" (UNSEEN 4)\r\n$tag OK done\r\n"
            line.startsWith("STATUS") -> "$tag NO no status for you\r\n"
            line.startsWith("SELECT") -> "* 0 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("SEARCH UNSEEN") -> "* SEARCH 3 5 9\r\n$tag OK done\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** A [MailboxDao] over a table in memory, whose two `replaceAll` writes are the shipped ones. */
    private class TableMailboxDao(vararg rows: MailboxEntity) : MailboxDao {
        private val table = rows.associateByTo(LinkedHashMap()) { it.accountId to it.id }

        fun unread(): Map<String, Int> = table.values.associate { it.id to it.unreadEmails }

        private fun of(accountId: String) = table.values.filter { it.accountId == accountId }

        override suspend fun upsertAll(mailboxes: List<MailboxEntity>) {
            mailboxes.forEach { table[it.accountId to it.id] = it }
        }

        override suspend fun idsForAccount(accountId: String): List<String> = of(accountId).map { it.id }

        override suspend fun unreadForAccount(accountId: String): List<MailboxIdUnread> =
            of(accountId).map { MailboxIdUnread(it.id, it.unreadEmails) }

        override suspend fun deleteByIds(accountId: String, ids: List<String>) {
            ids.forEach { table.remove(accountId to it) }
        }

        override fun observeAll(accountId: String): Flow<List<MailboxEntity>> = error("unused")
        override fun observeUnread(): Flow<List<AccountMailboxUnread>> = error("unused")
        override suspend fun deleteAll(): Unit = error("unused")
        override suspend fun deleteForAccount(accountId: String): Unit = error("unused")
        override suspend fun accountIds(): List<String> = error("unused")
        override suspend fun idForRole(accountId: String, role: String): String? = error("unused")
        override fun observeSentMailboxes(accountIds: List<String>): Flow<List<AccountMailboxId>> = error("unused")
        override fun observeRoles(accountIds: List<String>): Flow<List<AccountMailboxRole>> = error("unused")
        override suspend fun idsForAnyName(accountId: String, names: List<String>): List<String> = error("unused")
        override suspend fun rolesForAccount(accountId: String): List<MailboxIdRole> = error("unused")
        override suspend fun setRole(accountId: String, id: String, role: String?): Unit = error("unused")
        override suspend fun searchOrder(accountId: String): List<MailboxIdRole> = error("unused")
        override suspend fun roleForId(accountId: String, id: String): String? = error("unused")
        override suspend fun adjustCounts(accountId: String, id: String, totalDelta: Int, unreadDelta: Int): Unit =
            error("unused")
        override suspend fun cachedFolderIds(accountId: String): List<String> = emptyList()
        override suspend fun indexedFolderIds(accountId: String): List<String> = emptyList()
        override suspend fun deleteFolderMail(accountId: String, mailboxId: String) = Unit
        override suspend fun deleteFolderIndex(accountId: String, mailboxId: String) = Unit
        override suspend fun deleteFolderBodies(accountId: String, idPrefix: String) = Unit
    }

    private companion object {
        const val ACCOUNT = "acc-imap-1"

        fun row(id: String, unread: Int) = MailboxEntity(
            accountId = ACCOUNT,
            id = id,
            name = id,
            role = null,
            sortOrder = 0,
            totalEmails = 0,
            unreadEmails = unread,
        )

        /** [MoveNeedsTheNumberingStatedNowTest]'s: nothing here refreshes a token. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
