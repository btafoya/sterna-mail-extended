package app.sterna.core.data.mail

import app.sterna.core.data.db.AccountMailboxId
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.db.AccountMailboxUnread
import app.sterna.core.data.db.BODY_CACHE_ID_PREFIX_SQL
import app.sterna.core.data.db.DOWNLOAD_AHEAD_SKIPS_CREATE_SQL
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import app.sterna.core.data.db.EMAIL_FTS_CREATE_SQL
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.data.db.MailboxIdUnread
import app.sterna.core.data.db.MailboxUidValidityDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * #255, the folder side: a folder the IMAP listing no longer carries takes its cached rows, index
 * rows and bodies with it, and a renumbering takes the folder's index rows. The shipped default
 * `MailboxDao.replaceAllKeepingUnread` and `MailboxUidValidityStore.invalidate` are executed, over
 * the `@Query` texts of the shipped DAOs on in-memory SQLite.
 *
 * Not covered: the `@Upsert` of the folder rows, written by hand here, and the Room transaction.
 */
class VanishedFolderLeavesNoTraceSqlTest {
    private lateinit var db: Connection

    /** One account per test: [LegacyIndexSweep] reads an account's index once per process. */
    private val account = "acc-${java.util.UUID.randomUUID()}"

    private var indexReads = 0

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAIL_FTS_CREATE_SQL)
            st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
            st.executeUpdate(DOWNLOAD_AHEAD_SKIPS_CREATE_SQL)
            st.executeUpdate(
                "CREATE TABLE mailboxes(accountId TEXT NOT NULL, id TEXT NOT NULL, unreadEmails INTEGER NOT NULL, " +
                    "PRIMARY KEY(accountId, id))",
            )
        }
    }

    @After fun tearDown() = db.close()

    // ---- the fixture ----------------------------------------------------------------------------

    private fun id(account: String, folder: String, uid: Long) = ImapMailService.emailId(account, folder, uid)

    private fun cache(account: String, folder: String, uid: Long) {
        db.prepareStatement(
            "INSERT INTO emails (id, accountId, mailboxId, subject, seen, flagged, hasAttachment, sortKey) " +
                "VALUES (?, ?, ?, 's', 1, 1, 0, 1)",
        ).use {
            it.setString(1, id(account, folder, uid)); it.setString(2, account); it.setString(3, folder)
            it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO email_bodies VALUES (?, ?, '{}', '{}', 1)").use {
            it.setString(1, id(account, folder, uid)); it.setString(2, account); it.executeUpdate()
        }
    }

    private fun index(account: String, folder: String, uid: Long) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 's', 'f', '', '', '', NULL, NULL, 1, 1, 0, 1)",
        ).use {
            it.setString(1, id(account, folder, uid)); it.setString(2, account); it.setString(3, folder)
            it.executeUpdate()
        }
    }

    /** A message cached and indexed, the way the reseed leaves it. */
    private fun held(account: String, folder: String, uid: Long) {
        cache(account, folder, uid)
        index(account, folder, uid)
    }

    private fun ids(table: String): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT ${if (table == "email_fts") "emailId" else "id"} FROM $table ORDER BY 1").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    private fun listing(vararg folders: String) = folders.map {
        MailboxEntity(accountId = account, id = it, name = it, role = null, sortOrder = 0, totalEmails = 0, unreadEmails = 0)
    }

    private fun update(sql: String, vararg args: Pair<String, Any>, lists: Map<String, List<String>> = emptyMap()) {
        val (text, order) = DaoQuerySource.bindOrder(sql, lists.mapValues { it.value.size })
        val cursors = mutableMapOf<String, Int>()
        val values = args.toMap()
        db.prepareStatement(text).use { ps ->
            order.forEachIndexed { i, name ->
                val list = lists[name]
                if (list != null) {
                    val at = cursors.getOrDefault(name, 0)
                    ps.setString(i + 1, list[at]); cursors[name] = at + 1
                } else {
                    ps.setObject(i + 1, values[name] ?: error("no value for :$name"))
                }
            }
            ps.executeUpdate()
        }
    }

    private fun strings(sql: String, vararg args: Pair<String, Any>): List<String> {
        val (text, order) = DaoQuerySource.bindOrder(sql)
        val values = args.toMap()
        return db.prepareStatement(text).use { ps ->
            order.forEachIndexed { i, name -> ps.setObject(i + 1, values[name] ?: error("no value for :$name")) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun mailboxQuery(fn: String) = DaoQuerySource.daoQuery("MailboxDao", fn)

    /** The shipped default methods over the shipped statements; only the `@Upsert` is hand-written. */
    private inner class SqlMailboxDao : MailboxDao {
        override suspend fun upsertAll(mailboxes: List<MailboxEntity>) {
            mailboxes.forEach { row ->
                db.prepareStatement("INSERT OR REPLACE INTO mailboxes VALUES (?, ?, ?)").use {
                    it.setString(1, row.accountId); it.setString(2, row.id); it.setInt(3, row.unreadEmails)
                    it.executeUpdate()
                }
            }
        }

        override suspend fun idsForAccount(accountId: String): List<String> =
            strings(mailboxQuery("idsForAccount"), "accountId" to accountId)

        override suspend fun unreadForAccount(accountId: String): List<MailboxIdUnread> =
            idsForAccount(accountId).map { MailboxIdUnread(it, 0) }

        override suspend fun deleteByIds(accountId: String, ids: List<String>) =
            update(mailboxQuery("deleteByIds"), "accountId" to accountId, lists = mapOf("ids" to ids))

        override suspend fun cachedFolderIds(accountId: String): List<String> =
            strings(mailboxQuery("cachedFolderIds"), "accountId" to accountId)

        override suspend fun indexedFolderIds(accountId: String): List<String> {
            indexReads++
            return strings(mailboxQuery("indexedFolderIds"), "accountId" to accountId)
        }

        override suspend fun deleteFolderMail(accountId: String, mailboxId: String) =
            update(mailboxQuery("deleteFolderMail"), "accountId" to accountId, "mailboxId" to mailboxId)

        override suspend fun deleteFolderIndex(accountId: String, mailboxId: String) =
            update(mailboxQuery("deleteFolderIndex"), "accountId" to accountId, "mailboxId" to mailboxId)

        override suspend fun deleteFolderBodies(accountId: String, idPrefix: String) =
            update(BODY_CACHE_ID_PREFIX_SQL, "accountId" to accountId, "idPrefix" to idPrefix)

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
    }

    private fun write(vararg folders: String) = runBlocking {
        SqlMailboxDao().replaceAllKeepingUnread(account, listing(*folders), unreadStated = emptySet())
    }

    // ---- a listing the server answered ----------------------------------------------------------

    @Test fun `a folder the listing no longer carries loses its cached rows, index rows and bodies`() {
        held(account, "INBOX", 1)
        held(account, "Old", 2)
        index(account, "Gone", 3)

        write("INBOX")

        assertEquals(listOf(id(account, "INBOX", 1)), ids("emails"))
        assertEquals(listOf(id(account, "INBOX", 1)), ids("email_fts"))
        assertEquals(listOf(id(account, "INBOX", 1)), ids("email_bodies"))
    }

    @Test fun `another account's folder of the same name keeps its cache and its index`() {
        held(account, "INBOX", 1)
        held(account, "Old", 2)
        held(OTHER, "Old", 2)

        write("INBOX")

        assertEquals(listOf(id(account, "INBOX", 1), id(OTHER, "Old", 2)), ids("emails"))
        assertEquals(listOf(id(account, "INBOX", 1), id(OTHER, "Old", 2)), ids("email_fts"))
        assertEquals(listOf(id(account, "INBOX", 1), id(OTHER, "Old", 2)), ids("email_bodies"))
    }

    @Test fun `a listing without an INBOX takes nothing away`() {
        held(account, "INBOX", 1)
        held(account, "Old", 2)
        index(account, "Gone", 3)
        val before = listOf("emails", "email_fts", "email_bodies").associateWith { ids(it) }

        write()
        write("Archive")

        assertEquals(before, listOf("emails", "email_fts", "email_bodies").associateWith { ids(it) })
    }

    @Test fun `a listed folder keeps its rows, cached yet or not, and out of the drawer for the undo window of its deletion or not`() {
        held(account, "INBOX", 1)
        index(account, "Archive", 2)
        held(account, "Hidden", 3)
        write("INBOX", "Archive", "Hidden")
        runBlocking { SqlMailboxDao().deleteByIds(account, listOf("Hidden")) }

        write("INBOX", "Archive", "Hidden")

        assertEquals(listOf(id(account, "Archive", 2), id(account, "Hidden", 3), id(account, "INBOX", 1)), ids("email_fts"))
        assertEquals(listOf(id(account, "Hidden", 3), id(account, "INBOX", 1)), ids("emails"))
    }

    @Test fun `a vanished folder cached but never indexed and no longer in the drawer loses its cache`() {
        held(account, "INBOX", 1)
        cache(account, "Trash", 2)

        write("INBOX")

        assertEquals(listOf(id(account, "INBOX", 1)), ids("emails"))
        assertEquals(listOf(id(account, "INBOX", 1)), ids("email_bodies"))
    }

    @Test fun `a vanished folder known to the drawer loses its rows though the index is not read again`() {
        held(account, "INBOX", 1)
        write("INBOX", "Projects")
        index(account, "Projects", 2)

        write("INBOX")

        assertEquals(1, indexReads)
        assertEquals(listOf(id(account, "INBOX", 1)), ids("email_fts"))
    }

    @Test fun `a listing naming its inbox in another case is a whole listing`() {
        held(account, "Inbox", 1)
        held(account, "Old", 2)

        write("Inbox")

        assertEquals(listOf(id(account, "Inbox", 1)), ids("emails"))
        assertEquals(listOf(id(account, "Inbox", 1)), ids("email_fts"))
    }

    @Test fun `a ghost left before any folder row is purged at the first listing, and the index is read once`() {
        held(account, "INBOX", 1)
        index(account, "Gone", 2)

        write("INBOX")
        index(account, "Later", 3)
        write("INBOX")

        assertEquals(1, indexReads)
        assertEquals(listOf(id(account, "INBOX", 1), id(account, "Later", 3)), ids("email_fts"))
    }

    // ---- a renumbering --------------------------------------------------------------------------

    private fun ftsDao(fails: Boolean = false): EmailFtsDao =
        Proxy.newProxyInstance(EmailFtsDao::class.java.classLoader, arrayOf(EmailFtsDao::class.java)) { _, method, args ->
            when (method.name) {
                "deleteForMailbox" -> {
                    if (fails) throw IllegalStateException("database is locked")
                    update(
                        DaoQuerySource.daoQuery("EmailFtsDao", "deleteForMailbox"),
                        "accountId" to args!![0] as String, "mailboxId" to args[1] as String,
                    )
                }
                else -> error("EmailFtsDao.${method.name} was not expected here.")
            }
        } as EmailFtsDao

    private inline fun <reified T : Any> permissive(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> Unit } as T

    @Test fun `a renumbering drops the folder's index rows, this account's only, and leaves the cache`() {
        held(account, "INBOX", 1)
        index(account, "INBOX", 2)
        held(account, "Archive", 3)
        held(OTHER, "INBOX", 1)

        runBlocking {
            MailboxUidValidityStore(permissive<MailboxUidValidityDao>(), permissive(), permissive(), ftsDao(), PendingExpungeStore.None)
                .invalidate(account, "INBOX", 7L)
        }

        assertEquals(listOf(id(account, "Archive", 3), id(OTHER, "INBOX", 1)), ids("email_fts"))
        assertEquals(listOf(id(account, "Archive", 3), id(account, "INBOX", 1), id(OTHER, "INBOX", 1)), ids("emails"))
    }

    private fun bodyDao(): EmailBodyDao =
        Proxy.newProxyInstance(EmailBodyDao::class.java.classLoader, arrayOf(EmailBodyDao::class.java)) { _, method, args ->
            when (method.name) {
                "deleteForIdPrefix", "deleteDownloadAheadSkipsForIdPrefix" -> update(
                    DaoQuerySource.daoQuery("EmailBodyDao", method.name),
                    "accountId" to args!![0] as String, "idPrefix" to args[1] as String,
                )
                else -> error("EmailBodyDao.${method.name} was not expected here.")
            }
        } as EmailBodyDao

    private fun skip(account: String, folder: String, uid: Long) {
        db.prepareStatement("INSERT INTO download_ahead_skips VALUES (?, ?)").use {
            it.setString(1, account); it.setString(2, id(account, folder, uid)); it.executeUpdate()
        }
    }

    @Test fun `a renumbering drops the folder's download-ahead skips with its bodies, this account's only`() {
        cache(account, "INBOX", 1)
        skip(account, "INBOX", 2)
        skip(account, "Archive", 3)
        skip(OTHER, "INBOX", 2)

        runBlocking {
            MailboxUidValidityStore(permissive<MailboxUidValidityDao>(), bodyDao(), permissive(), ftsDao(), PendingExpungeStore.None)
                .invalidate(account, "INBOX", 7L)
        }

        assertEquals(
            "a skip kept under the new numbering would hide another message from the download ahead for good",
            listOf(id(OTHER, "INBOX", 2), id(account, "Archive", 3)).sorted(),
            ids("download_ahead_skips"),
        )
        assertEquals(emptyList<String>(), ids("email_bodies"))
    }

    @Test fun `a failed index purge does not fail the renumbering`() {
        val renumbered = mutableListOf<String>()
        val store = MailboxUidValidityStore(permissive<MailboxUidValidityDao>(), permissive(), permissive(), ftsDao(fails = true), PendingExpungeStore.None)
        store.onRenumbered = { _, folder -> renumbered += folder }

        runBlocking { store.invalidate(account, "INBOX", 7L) }

        assertEquals(listOf("INBOX"), renumbered)
    }

    private companion object {
        const val OTHER = "other"
    }
}
