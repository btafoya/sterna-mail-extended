package app.sterna.core.data.mail

import app.sterna.core.imap.ImapFolderHits
import app.sterna.core.imap.ImapIndexVerdict
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * #255, the index side: which rows an IMAP search may contradict, and what its verdict writes,
 * each through the shipped `EmailFtsDao` statements on in-memory SQLite.
 */
class ServerSearchContradictsTheIndexSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE VIRTUAL TABLE email_fts USING fts4(" +
                    "emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                    "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey, " +
                    "notindexed=emailId, notindexed=accountId, notindexed=mailboxId, " +
                    "notindexed=threadId, notindexed=preview, notindexed=receivedAt, " +
                    "notindexed=fromName, notindexed=fromEmail, notindexed=seen, " +
                    "notindexed=flagged, notindexed=hasAttachment, notindexed=sortKey, " +
                    "tokenize=unicode61 `remove_diacritics=1`)",
            )
            st.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, id))")
        }
    }

    @After fun tearDown() = db.close()

    private fun index(account: String, emailId: String, mailbox: String = "INBOX", seen: Boolean = true, flagged: Boolean = true) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 'subject', 'sender', '', '', '', NULL, NULL, ?, ?, 0, 1)",
        ).use {
            it.setString(1, emailId); it.setString(2, account); it.setString(3, mailbox)
            it.setInt(4, if (seen) 1 else 0); it.setInt(5, if (flagged) 1 else 0)
            it.executeUpdate()
        }
    }

    private fun cache(account: String, emailId: String) {
        db.prepareStatement("INSERT INTO emails(id, accountId, mailboxId) VALUES(?, ?, 'INBOX')").use {
            it.setString(1, emailId); it.setString(2, account); it.executeUpdate()
        }
    }

    private fun rows(): List<Triple<String, String, Pair<Int, Int>>> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, emailId, seen, flagged FROM email_fts ORDER BY accountId, emailId").use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getInt(3) to rs.getInt(4))) }
            }
        }

    private fun uncachedMatching(account: String, folders: List<String>, query: SearchQuery): List<String> {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("EmailFtsDao", "uncachedMatching"),
            listParams = mapOf("mailboxIds" to folders.size),
        )
        var folder = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, account)
                    "mailboxIds" -> ps.setString(i + 1, folders[folder++])
                    "unreadOnly" -> ps.setBoolean(i + 1, query.unread)
                    "flaggedOnly" -> ps.setBoolean(i + 1, query.flagged)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.uncachedMatching")
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) }.sorted() }
        }
    }

    private suspend fun deleteByIds(accountId: String, ids: List<String>) {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("EmailFtsDao", "deleteByIds"),
            listParams = mapOf("ids" to ids.size),
        )
        var id = 0
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, accountId)
                    "ids" -> ps.setString(i + 1, ids[id++])
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.deleteByIds")
                }
            }
            ps.executeUpdate()
        }
    }

    private suspend fun setFlags(accountId: String, emailId: String, seen: Boolean, flagged: Boolean) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("EmailFtsDao", "setFlags"))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, accountId)
                    "emailId" -> ps.setString(i + 1, emailId)
                    "seen" -> ps.setBoolean(i + 1, seen)
                    "flagged" -> ps.setBoolean(i + 1, flagged)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.setFlags")
                }
            }
            ps.executeUpdate()
        }
    }

    @Test fun `the candidates are this account's uncached rows, in the searched folders, meeting the flags`() {
        index("acc", "ghost")
        index("acc", "cached"); cache("acc", "cached")
        index("acc", "unstarred", flagged = false)
        index("acc", "elsewhere", mailbox = "Trash")
        index("other", "theirs")
        index("acc", "unreadGhost", seen = false)

        assertEquals(listOf("ghost", "unreadGhost"), uncachedMatching("acc", listOf("INBOX", "Archive"), SearchQuery(flagged = true)))
        assertEquals(listOf("unreadGhost"), uncachedMatching("acc", listOf("INBOX"), SearchQuery(flagged = true, unread = true)))
    }

    @Test fun `a cached row of another account does not shield this account's ghost`() {
        index("acc", "same")
        cache("other", "same")
        assertEquals(listOf("same"), uncachedMatching("acc", listOf("INBOX"), SearchQuery(flagged = true)))
    }

    @Test fun `the verdict removes this account's row only, rewrites the flags read, and names what it removed`() = runTest {
        index("acc", "gone")
        index("other", "gone")
        index("acc", "unstarred", seen = false, flagged = true)
        val hits = ImapSearchHits(
            messages = emptyList(),
            complete = true,
            indexGone = listOf("gone"),
            indexFlags = listOf(IndexFlags("unstarred", seen = true, flagged = false)),
        )

        val purged = applyIndexVerdict("acc", hits, ::deleteByIds, ::setFlags)

        assertEquals(setOf(EmailKey("acc", "gone")), purged)
        assertEquals(
            listOf(
                Triple("acc", "unstarred", 1 to 0),
                Triple("other", "gone", 1 to 1),
            ),
            rows(),
        )
    }

    @Test fun `a probe's verdict reaches the index, the absent row goes, the present one keeps its row and takes its flags`() = runTest {
        index("acc", "imap:acc:INBOX:5")
        index("acc", "imap:acc:INBOX:6", seen = false, flagged = true)
        index("other", "imap:other:INBOX:5")
        index("other", "imap:acc:INBOX:5")
        val folders = listOf(
            ImapFolderHits(
                "INBOX",
                emptyList(),
                uidValidity = 42L,
                verdict = ImapIndexVerdict(gone = setOf(5L), flags = mapOf(6L to listOf("\\Seen"))),
            ),
        )
        val hits = ImapSearchHits(emptyList(), complete = true, indexGone = indexGoneOf("acc", folders), indexFlags = indexFlagsOf("acc", folders))

        assertEquals(setOf(EmailKey("acc", "imap:acc:INBOX:5")), applyIndexVerdict("acc", hits, ::deleteByIds, ::setFlags))
        assertEquals(
            listOf(
                Triple("acc", "imap:acc:INBOX:6", 1 to 0),
                Triple("other", "imap:acc:INBOX:5", 1 to 1),
                Triple("other", "imap:other:INBOX:5", 1 to 1),
            ),
            rows(),
        )
    }

    @Test fun `only a search of flags alone may contradict the index`() {
        assertTrue(SearchQuery(flagged = true).indexMayBeContradicted())
        assertTrue(SearchQuery(unread = true).indexMayBeContradicted())
        assertTrue(SearchQuery(flagged = true, unread = true).indexMayBeContradicted())
        listOf(
            SearchQuery(),
            SearchQuery(flagged = true, text = "invoice"),
            SearchQuery(flagged = true, from = "alex"),
            SearchQuery(flagged = true, recipient = "sam"),
            SearchQuery(flagged = true, subject = "Q3"),
            SearchQuery(flagged = true, afterMillis = 1L),
            SearchQuery(flagged = true, beforeMillis = 1L),
            SearchQuery(flagged = true, hasAttachment = true),
        ).forEach { assertFalse("$it", it.indexMayBeContradicted()) }
    }

    @Test fun `claims are grouped by the folder the id names, searched folders only`() {
        assertEquals(
            mapOf("INBOX" to setOf(5L, 7L), "a:b" to setOf(3L)),
            indexClaimsOf(
                listOf("imap:acc:INBOX:5", "imap:acc:INBOX:7", "imap:acc:a:b:3", "imap:acc:Trash:9", "jmap-id"),
                listOf("INBOX", "a:b"),
            ),
        )
    }

    @Test fun `purged keys survive the unified merge and carry their account`() {
        val one = MailSearchResult(listOf(Email(id = "x", accountId = "a")), purged = setOf(EmailKey("a", "g1")))
        val two = MailSearchResult(emptyList(), purged = setOf(EmailKey(null, "g2"))).ownedBy("b")
        assertEquals(setOf(EmailKey("a", "g1"), EmailKey("b", "g2")), mergeAccountSearches(listOf(one, two), limit = 10).purged)
    }
}
