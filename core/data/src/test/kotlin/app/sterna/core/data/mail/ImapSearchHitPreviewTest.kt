package app.sterna.core.data.mail

import app.sterna.core.data.db.CachedPreview
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * An IMAP search hit is built with no opening line; the one the cache holds for the same message
 * is what the result row shows, and what the searched words are highlighted in. The cache is read
 * through the shipped `cachedPreviews` statement, executed against SQLite.
 */
class ImapSearchHitPreviewTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE emails(id TEXT, accountId TEXT, preview TEXT, uidValidity INTEGER, " +
                    "PRIMARY KEY(accountId, id))",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun cache(id: String, preview: String, uidValidity: Long) {
        db.prepareStatement("INSERT INTO emails VALUES(?, 'acc-1', ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, preview)
            ps.setLong(3, uidValidity)
            ps.executeUpdate()
        }
    }

    private fun shippedCachedPreviews(ids: List<String>, uidValidity: Long?): List<CachedPreview> {
        val (sql, _) = DaoQuerySource.bindOrder(
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
            listParams = mapOf("ids" to ids.size),
        )
        return db.prepareStatement(sql).use { ps ->
            val values = listOf<Any?>("acc-1") + ids + listOf(uidValidity)
            values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, Types.BIGINT)
                    is Long -> ps.setLong(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(CachedPreview(rs.getString("id"), rs.getString("preview"))) }
            }
        }
    }

    private fun hit(mailbox: String, uid: Long, uidValidity: Long) = ImapMessage(
        uid = uid,
        subject = "Subject $uid",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        to = emptyList(),
        dateMillis = 1_700_000_000_000L + uid,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = true,
        messageId = "<$uid@example.org>",
        inReplyTo = null,
    ).toEntity("acc-1", mailbox, uidValidity, preview = null)

    private fun filled(vararg hits: EmailEntity) = runBlocking {
        withCachedSearchPreviews(hits.toList()) { ids, numbering -> shippedCachedPreviews(ids, numbering) }
    }

    @Test fun `a search hit shows the opening line the cache holds for it`() {
        cache("imap:acc-1:INBOX:7", "Il reste des poires pour samedi.", uidValidity = 42L)
        cache("imap:acc-1:Archive:3", "Facture de mars", uidValidity = 9L)

        val rows = filled(hit("INBOX", 7L, 42L), hit("INBOX", 8L, 42L), hit("Archive", 3L, 9L))

        assertEquals(
            "each hit must carry its cached opening line, in the order the server ranked them; " +
                "one the cache never held stays without one",
            listOf("Il reste des poires pour samedi.", null, "Facture de mars"),
            rows.map { it.preview },
        )
    }

    @Test fun `a line cached under another numbering is not copied onto the hit`() {
        cache("imap:acc-1:INBOX:7", "the PREVIOUS message's opening", uidValidity = 7L)

        val rows = filled(hit("INBOX", 7L, 42L))

        assertNull(
            "UID 7 under numbering 42 is not the message cached as UID 7 under numbering 7: " +
                "its first lines would sit under another message's subject",
            rows.single().preview,
        )
    }

    @Test fun `the IMAP search hands its hits through the cache of the searching account`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "search")
        val compact = body.replace(Regex("\\s+"), "")
        assertTrue(
            "the IMAP leg must fill its hits' opening lines from this account's cache, " +
                "under the numbering each hit was read with",
            "withCachedSearchPreviews(hits.messages){ids,numbering->emailDao.cachedPreviews(credentials.id,ids,numbering)}"
                in compact,
        )
    }
}
