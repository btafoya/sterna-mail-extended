package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * The one read that decides both halves of the IMAP list preview — which rows are NOT asked of the
 * server, and which rows keep the opening line they already have — EXECUTED against SQLite, with
 * the statement taken out of the shipped DAO ([DaoQuerySource.emailDaoQuery]).
 *
 * ⛔ Why it has to be executed and not read. `EmailEntity.id` is `imap:$account:$mailbox:$uid`
 * (`ImapMailService.emailId`): it does NOT carry the folder's numbering. A renumbered folder
 * (UIDVALIDITY changed: recreated on the server, migrated, rebuilt) restarts its UIDs low, and
 * `MailboxUidValidityStore.invalidate` deliberately leaves the `emails` rows behind — it deletes
 * bodies and purge snapshots only, because "the list cache is replaced from the server on the next
 * refresh". The preview column broke that sentence: since #187 it is no longer replaced from the
 * server, it is CARRIED OVER from the cached row. So without the numbering in this statement, the
 * new message that lands on UID 7 is handed the opening line of the old message that used to be
 * UID 7 — the right sender, the right subject, and the first line of somebody else's mail, for
 * good, because the row then counts as filled and nothing ever asks for its body again (#99).
 *
 * ⛔ And `IS`, never `=`. A server that states no UIDVALIDITY stores NULL ([UidValidity.stated]),
 * and `NULL = NULL` is NULL in SQL: with `=` every row of such an account is invisible to this
 * read, which is not a corruption but a permanent one — every refresh re-downloads the whole page
 * for ever, and the "paid for once" bound this feature is built on is gone. Reading the statement
 * as text cannot tell the two operators apart; running it can.
 */
class CachedPreviewNumberingSqlTest {
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

    private fun insert(id: String, preview: String?, uidValidity: Long?, accountId: String = "acc-1") {
        db.prepareStatement("INSERT INTO emails VALUES(?, ?, ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, accountId)
            if (preview == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, preview)
            if (uidValidity == null) ps.setNull(4, Types.BIGINT) else ps.setLong(4, uidValidity)
            ps.executeUpdate()
        }
    }

    /** The shipped `cachedPreviews` statement, run for [ids] under [uidValidity]. */
    private fun cachedPreviews(
        ids: List<String>,
        uidValidity: Long?,
        accountId: String = "acc-1",
    ): Map<String, String> {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
            listParams = mapOf("ids" to ids.size),
        )
        assertEquals(
            "the statement must still bind the account, the page, and the NUMBERING — in that order",
            listOf("accountId") + List(ids.size) { "ids" } + listOf("uidValidity"),
            order,
        )
        val values = listOf<Any?>(accountId) + ids + listOf(uidValidity)
        return db.prepareStatement(sql).use { ps ->
            values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, Types.BIGINT)
                    is Long -> ps.setLong(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString("id"), rs.getString("preview")) }
            }
        }
    }

    /**
     * ⭐ THE DEFECT THIS STATEMENT EXISTS TO STOP. Two rows, the SAME cache id, read under two
     * numberings: the folder was recreated on the server and UID 7 now means a different message.
     * The walk asks under the numbering it has just SELECTed, and must be told nothing.
     */
    @Test fun `a row cached under another numbering is not an already-filled row`() {
        insert("imap:acc-1:INBOX:7", preview = "the OLD message's opening", uidValidity = 7L)

        assertEquals(
            "a preview cached under numbering 7 must not answer a page read under numbering 42: " +
                "carried over, it would put the previous message's first line under the new " +
                "message's sender, and permanently — the row would then count as filled",
            emptyMap<String, String>(),
            cachedPreviews(listOf("imap:acc-1:INBOX:7"), uidValidity = 42L),
        )
    }

    /** And under its own numbering the very same row answers, or nothing is ever paid once. */
    @Test fun `a row cached under this numbering is returned with its opening line`() {
        insert("imap:acc-1:INBOX:7", preview = "this message's opening", uidValidity = 42L)

        assertEquals(
            mapOf("imap:acc-1:INBOX:7" to "this message's opening"),
            cachedPreviews(listOf("imap:acc-1:INBOX:7"), uidValidity = 42L),
        )
    }

    /**
     * ⛔ `IS`, not `=`. A server that states no UIDVALIDITY stores NULL, and `=` would hide every
     * one of its rows from this read: no corruption, but the page re-downloaded on every single
     * refresh, for ever.
     */
    @Test fun `a server that states no numbering still has its cached openings found`() {
        insert("imap:acc-1:INBOX:7", preview = "no numbering here", uidValidity = null)

        assertEquals(
            "NULL = NULL is NULL in SQL: with '=' this account's openings are invisible to the " +
                "read that bounds the fetch, and every refresh re-downloads the whole page",
            mapOf("imap:acc-1:INBOX:7" to "no numbering here"),
            cachedPreviews(listOf("imap:acc-1:INBOX:7"), uidValidity = null),
        )
    }

    /** The mirror: a numbered row is not what an un-numbered read is asking about. */
    @Test fun `a numbered row does not answer a read that states no numbering`() {
        insert("imap:acc-1:INBOX:7", preview = "numbered", uidValidity = 42L)

        assertEquals(
            emptyMap<String, String>(),
            cachedPreviews(listOf("imap:acc-1:INBOX:7"), uidValidity = null),
        )
    }

    /**
     * ⛔ The account bound is still there, and it is the older half of the same rule (#31): with the
     * composite (accountId, id) key an id is not unique across accounts, so a same-server sibling's
     * filled row would report THIS account's row as filled and leave it blank for good.
     */
    @Test fun `a sibling account's row under the same id and numbering is not this account's`() {
        insert("imap:acc-1:INBOX:7", preview = "the sibling's opening", uidValidity = 42L, accountId = "acc-2")

        assertEquals(
            emptyMap<String, String>(),
            cachedPreviews(listOf("imap:acc-1:INBOX:7"), uidValidity = 42L),
        )
    }

    /** A blank row is the gap itself: it must come back missing, so its body gets fetched. */
    @Test fun `a row with no opening line is not returned at all`() {
        insert("imap:acc-1:INBOX:7", preview = null, uidValidity = 42L)
        insert("imap:acc-1:INBOX:8", preview = "filled", uidValidity = 42L)

        assertEquals(
            "only the filled row answers — the blank one is what the walk goes and reads",
            mapOf("imap:acc-1:INBOX:8" to "filled"),
            cachedPreviews(listOf("imap:acc-1:INBOX:7", "imap:acc-1:INBOX:8"), uidValidity = 42L),
        )
    }
}
