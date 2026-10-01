package app.sterna.core.data.mail

import app.sterna.core.jmap.model.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * The local leg of a search with criteria, executed: [ftsMatch] builds the `MATCH`, and the DAO's
 * own two statements (`search`, `searchWithoutWords`) run against in-memory SQLite with the flags
 * and dates of the query, bound the way `MailRepository.searchIndex` hands them over.
 */
class LocalSearchCriteriaSqlTest {
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
            st.executeUpdate(
                "CREATE TABLE mailboxes(accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT, " +
                    "sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER, PRIMARY KEY(accountId, id))",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun index(
        emailId: String,
        subject: String = "quarterly report",
        sender: String = "Alex Rivera alex@example.org",
        seen: Boolean = false,
        flagged: Boolean = false,
        attachment: Boolean = false,
        sortKey: Long = 100,
    ) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, 'acc', 'mb-inbox', NULL, ?, ?, '', 'preview', '', NULL, NULL, ?, ?, ?, ?)",
        ).use {
            it.setString(1, emailId); it.setString(2, subject); it.setString(3, sender)
            it.setInt(4, if (seen) 1 else 0); it.setInt(5, if (flagged) 1 else 0)
            it.setInt(6, if (attachment) 1 else 0); it.setLong(7, sortKey)
            it.executeUpdate()
        }
    }

    /** The ids the shipped statement returns for [query], chosen as `searchIndex` chooses it. */
    private fun search(query: SearchQuery): List<String> {
        val match = ftsMatch(query)
        val function = if (match != null) "search" else "searchWithoutWords"
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("EmailFtsDao", function),
            listParams = mapOf("excludedRoles" to NOT_SEARCHED_ROLES.size, "accountIds" to 0),
        )
        val roles = NOT_SEARCHED_ROLES.toList()
        var role = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                val at = i + 1
                when (name) {
                    "match" -> ps.setString(at, match)
                    "unreadOnly" -> ps.setBoolean(at, query.unread)
                    "flaggedOnly" -> ps.setBoolean(at, query.flagged)
                    "attachmentOnly" -> ps.setBoolean(at, query.hasAttachment)
                    "sinceMillis" -> query.afterMillis?.let { ps.setLong(at, it) } ?: ps.setNull(at, Types.BIGINT)
                    "untilMillis" -> query.beforeMillis?.let { ps.setLong(at, it) } ?: ps.setNull(at, Types.BIGINT)
                    "excludedRoles" -> ps.setString(at, roles[role++])
                    "accountScopeCount" -> ps.setInt(at, 0)
                    "limit" -> ps.setInt(at, 50)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.$function")
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }
    }

    @Test fun `the match holds the words, the sender and the subject, each a prefix term`() {
        assertEquals(
            "invoice* sender:alex* sender:example* subject:Q3*",
            ftsMatch(SearchQuery(text = "invoice", from = "alex@example", subject = "Q3")),
        )
        assertNull("no words: the statement without MATCH runs", ftsMatch(SearchQuery(unread = true)))
        assertNull("the recipient is not in the index", ftsMatch(SearchQuery(recipient = "sam@example.org")))
    }

    @Test fun `From is looked for in the sender only, Subject in the subject only`() {
        index("byAlex", subject = "lunch", sender = "Alex Rivera alex@example.org")
        index("aboutAlex", subject = "alex lunch", sender = "Sam Doe sam@example.org")
        assertEquals(listOf("byAlex"), search(SearchQuery(from = "alex")))
        assertEquals(listOf("aboutAlex"), search(SearchQuery(subject = "alex")))
    }

    @Test fun `Unread keeps only the rows the index holds unread`() {
        index("read", seen = true)
        index("unread", seen = false)
        assertEquals(listOf("unread"), search(SearchQuery(text = "report", unread = true)))
        assertEquals(listOf("read", "unread"), search(SearchQuery(text = "report")).sorted())
    }

    @Test fun `Starred and Has attachment filter, and they add up`() {
        index("plain")
        index("starred", flagged = true)
        index("both", flagged = true, attachment = true)
        assertEquals(listOf("both", "starred"), search(SearchQuery(text = "report", flagged = true)).sorted())
        assertEquals(listOf("both"), search(SearchQuery(text = "report", flagged = true, hasAttachment = true)))
    }

    @Test fun `Since and Until are inclusive bounds on the received time`() {
        index("before", sortKey = 99)
        index("first", sortKey = 100)
        index("last", sortKey = 200)
        index("after", sortKey = 201)
        assertEquals(
            listOf("last", "first"),
            search(SearchQuery(text = "report", afterMillis = 100, beforeMillis = 200)),
        )
    }

    @Test fun `a search of flags alone runs without words, with the same filters`() {
        index("read", seen = true, sortKey = 1)
        index("unread", seen = false, sortKey = 2)
        index("unreadStarred", seen = false, flagged = true, sortKey = 3)
        assertEquals(listOf("unreadStarred", "unread"), search(SearchQuery(unread = true)))
        assertEquals(listOf("unreadStarred"), search(SearchQuery(unread = true, flagged = true)))
        assertEquals(listOf("unread"), search(SearchQuery(unread = true, beforeMillis = 2)))
    }
}
