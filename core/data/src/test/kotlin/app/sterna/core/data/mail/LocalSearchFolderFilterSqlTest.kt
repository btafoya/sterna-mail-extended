package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, that the local index half of a search
 * ([app.sterna.core.data.db.EmailFtsDao.search]) hides the folders a search must not surface —
 * Trash/Junk/Spam, the roles of [NOT_SEARCHED_ROLES], which the server half already excludes.
 *
 * What this filter covers, and all it covers: index rows that are still there but LABELLED with an
 * excluded folder. Rows get that label when they were WRITTEN with the excluded mailbox already on
 * them — the folder cache carried no role yet when the crawl indexed them, the role arrived from
 * the server afterwards, or the row came from `MIGRATION_14_15`, which repopulated the index
 * straight `FROM emails` with no exclusion at all. NOTHING in the app rewrites
 * `email_fts.mailboxId`, so the cases below fabricate that label with an `UPDATE` the app never
 * runs ([labelIndexRow]); a message deleted out of the Inbox keeps the INBOX on its index row and
 * is invisible to this filter. That other half — the message is gone, the row must go with it — is
 * `EmailDao.deleteById`/`deleteByIds`, pinned by [DeletedMailLeavesTheIndexSqlTest].
 *
 * Every case therefore carries the same witness: **the row is still in `email_fts`**. That is what
 * proves the FILTER did the work; a test that merely deleted the index row would also pass against
 * the old, broken query and would guard nothing.
 *
 * The statement executed here is read out of the shipped DAO by [DaoQuerySource], not retyped, so
 * changing the DAO's SQL changes this test's SQL with it.
 */
class LocalSearchFolderFilterSqlTest {
    private lateinit var db: Connection

    /**
     * The DAO's own statement, with `:match` / `:excludedRoles` / `:accountIds` /
     * `:accountScopeCount` / `:limit` turned into `?`.
     *
     * Built per call rather than once: Room expands `IN (:accountIds)` into as many `?` as the
     * scope holds, so the statement depends on [accountScope]'s size.
     */
    private fun searchSql(accountScope: List<String>) = DaoQuerySource.bindOrder(
        DaoQuerySource.daoQuery("EmailFtsDao", "search"),
        listParams = mapOf(
            "excludedRoles" to NOT_SEARCHED_ROLES.size,
            "accountIds" to accountScope.size,
        ),
    )

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
                """
                CREATE TABLE mailboxes(
                    accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT,
                    sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun folder(id: String, role: String?, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO mailboxes VALUES(?, ?, 'Folder', ?, NULL, 0, 0, 0)").use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    private fun index(
        emailId: String, mailboxId: String, subject: String = "quarterly token report",
        accountId: String = "acc", sortKey: Long = 100,
    ) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, " +
                "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, " +
                "sortKey) VALUES(?, ?, ?, NULL, ?, 'Alex Rivera alex@example.org', '', " +
                "'preview', '', 'Alex Rivera', 'alex@example.org', 0, 0, 0, ?)",
        ).use {
            it.setString(1, emailId); it.setString(2, accountId); it.setString(3, mailboxId)
            it.setString(4, subject); it.setLong(5, sortKey)
            it.executeUpdate()
        }
    }

    /**
     * Put an excluded folder's id on an index row — the state this filter exists for.
     *
     * This UPDATE is a fixture, NOT something the app runs: no code path rewrites
     * `email_fts.mailboxId`. It stands in for a row that was WRITTEN carrying that mailbox (a
     * folder whose role was still unknown to the cache when the crawl indexed it, a role the server
     * sent later, a row from `MIGRATION_14_15`), which is the only way such a row exists.
     */
    private fun labelIndexRow(emailId: String, mailboxId: String) {
        db.prepareStatement("UPDATE email_fts SET mailboxId = ? WHERE emailId = ?").use {
            it.setString(1, mailboxId); it.setString(2, emailId); it.executeUpdate()
        }
    }

    /**
     * The ids the shipped search query returns for [match], scoped to [accountScope].
     *
     * [accountScope] defaults to the empty list — no account filter at all, which is what the
     * repository passes when the stored account list cannot be read, and what keeps every case
     * written before the scope existed answering the same rows.
     */
    private fun search(match: String, limit: Int = 50, accountScope: List<String> = emptyList()): List<String> {
        val (sql, order) = searchSql(accountScope)
        val roles = NOT_SEARCHED_ROLES.toList()
        var role = 0
        var account = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "match" -> ps.setString(i + 1, match)
                    "excludedRoles" -> ps.setString(i + 1, roles[role++])
                    "accountIds" -> ps.setString(i + 1, accountScope[account++])
                    "accountScopeCount" -> ps.setInt(i + 1, accountScope.size)
                    "limit" -> ps.setInt(i + 1, limit)
                    else -> error("Unexpected parameter ':$name' in EmailFtsDao.search")
                }
            }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("emailId")) }
            }
        }
    }

    /** The witness: what the INDEX holds, filter or no filter. */
    private fun indexedIds(match: String): List<String> =
        db.prepareStatement("SELECT emailId FROM email_fts WHERE email_fts MATCH ? ORDER BY emailId").use { ps ->
            ps.setString(1, match)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("emailId")) } }
        }

    @Test fun aMessageInASearchableFolderIsReturned() {
        folder("mb-inbox", role = "inbox")
        index("kept", "mb-inbox")

        assertEquals(listOf("kept"), search("token*"))
    }

    @Test fun aRowLabelledTrashIsHiddenFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        index("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search("token*"))

        labelIndexRow("thrown", "mb-trash")

        assertEquals(emptyList<String>(), search("token*"))
        // The witness: nothing cleaned the index — the QUERY is what hides the message.
        assertEquals(listOf("thrown"), indexedIds("token*"))
    }

    @Test fun aRowLabelledJunkIsHiddenFromResultsWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox")
        folder("mb-junk", role = "junk")
        index("junked", "mb-inbox")
        assertEquals(listOf("junked"), search("token*"))

        labelIndexRow("junked", "mb-junk")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("junked"), indexedIds("token*"))
    }

    @Test fun aSpamFolderIsHiddenUnderWhateverSpellingTheServerSentItsRole() {
        // Servers spell roles inconsistently (`Trash`, ` Spam `): the same folding the index build
        // applies (LOWER(TRIM(...))) must apply here, or the filter misses the folder it targets.
        folder("mb-inbox", role = "inbox")
        folder("mb-spam", role = " Spam ")
        folder("mb-trash", role = "TRASH")
        index("kept", "mb-inbox")
        index("spam", "mb-spam")
        index("thrown", "mb-trash")

        assertEquals(listOf("kept"), search("token*"))
        assertEquals(listOf("kept", "spam", "thrown"), indexedIds("token*"))
    }

    /**
     * The SECOND trash. An account with a personal `Trash` and a shared `Shared/…/Trash` gives the
     * role to one of them (`ImapClient.assignRoles`); the loser is stored with the role MARKED
     * (`~trash`, [imapMailboxEntity]) and must be hidden exactly like the elected one. Left
     * role-less, as it was, its mail came back in every search result.
     */
    @Test fun aRowLabelledWithAnUnelectedTrashIsHiddenToo() {
        folder("mb-inbox", role = "inbox")
        folder("mb-trash", role = "trash")
        folder("mb-shared-trash", role = "~trash")
        index("thrown", "mb-inbox")
        assertEquals(listOf("thrown"), search("token*"))

        labelIndexRow("thrown", "mb-shared-trash")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("thrown"), indexedIds("token*"))
    }

    @Test fun aRowLabelledWithAnUnelectedJunkIsHiddenToo() {
        folder("mb-inbox", role = "inbox")
        folder("mb-shared-junk", role = "~junk")
        index("junked", "mb-inbox")
        assertEquals(listOf("junked"), search("token*"))

        labelIndexRow("junked", "mb-shared-junk")

        assertEquals(emptyList<String>(), search("token*"))
        assertEquals(listOf("junked"), indexedIds("token*"))
    }

    /**
     * The witness for the two above: the mark is not a wildcard. A folder whose role merely STARTS
     * with the mark, or that lost a claim on something else, is an ordinary folder and its mail is
     * still found — otherwise the cases above would pass under a filter that hides too much.
     */
    @Test fun theMarkHidesTrashAndJunkAndNothingElse() {
        folder("mb-sent", role = "~sent")
        folder("mb-odd", role = "~")
        index("sent", "mb-sent")
        index("odd", "mb-odd")

        assertEquals(listOf("odd", "sent"), search("token*").sorted())
    }

    @Test fun aHitWhoseFolderIsNotCachedYetIsStillReturned() {
        // Index and folder list fill in independently. An unknown folder must not turn "not synced
        // yet" into "no results" while the user is typing: local search stays monotonic.
        index("early", "mb-not-synced-yet")

        assertEquals(listOf("early"), search("token*"))
    }

    @Test fun aSiblingAccountsTrashDoesNotHideThisAccountsMessage() {
        // Two accounts of one login (issue #31) can mint the SAME mailbox id: account A's inbox
        // and account B's trash both being "mb-7" must not make A's message vanish.
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "trash", accountId = "accB")
        index("a1", "mb-7", accountId = "accA")
        index("b1", "mb-7", accountId = "accB")

        assertEquals(listOf("a1"), search("token*"))
        assertEquals(listOf("a1", "b1"), indexedIds("token*"))
    }

    /**
     * A row indexed under an account that is GONE stays cherchable otherwise: the index carries no
     * foreign key to anything, and removing an account does not sweep it. The scope is what hides
     * it, and the witness says the row is still there — so it is the QUERY doing the work, not a
     * cleanup that happened to run.
     */
    @Test fun aRowOfAnAccountThatIsGoneIsHiddenWhileStayingInTheIndex() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("live"), search("token*", accountScope = listOf("accA")))
        assertEquals(listOf("ghost", "live"), indexedIds("token*"))
    }

    /**
     * The inverse witness for the case above: name BOTH accounts and both hits come back. Without
     * it, a scope clause that matched nothing at all — or one bound to the wrong column — would
     * pass by returning an empty answer.
     */
    @Test fun everyAccountNamedInTheScopeIsSearched() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("ghost", "live"), search("token*", accountScope = listOf("accA", "accGhost")).sorted())
    }

    /**
     * An EMPTY scope filters nothing, deliberately. `AccountStore.accounts()` answers with an empty
     * list both for "no account" and for "the stored blob could not be decoded"; filtering on the
     * second would switch local search off entirely on a healthy install, the same failure the
     * unknown-folder rule above refuses.
     */
    @Test fun anEmptyScopeFiltersNoAccountAtAll() {
        folder("mb-inbox", role = "inbox", accountId = "accA")
        index("live", "mb-inbox", accountId = "accA")
        index("ghost", "mb-inbox", accountId = "accGhost")

        assertEquals(listOf("ghost", "live"), search("token*", accountScope = emptyList()).sorted())
    }

    /**
     * The scope does not make the role join's own account match redundant. Two accounts of one
     * login (issue #31) can mint the SAME mailbox id, and here BOTH are configured, so the scope
     * keeps both rows: only `mailboxes.accountId = email_fts.accountId` stops account B's trash
     * from hiding account A's inbox message. Drop that condition and this case is what turns red.
     */
    @Test fun accountScopeDoesNotReplaceTheRoleJoinsAccountMatch() {
        folder("mb-7", role = "inbox", accountId = "accA")
        folder("mb-7", role = "trash", accountId = "accB")
        index("a1", "mb-7", accountId = "accA")
        index("b1", "mb-7", accountId = "accB")

        assertEquals(listOf("a1"), search("token*", accountScope = listOf("accA", "accB")))
        assertEquals(listOf("a1", "b1"), indexedIds("token*"))
    }

    @Test fun anEmptyIndexSimplyAnswersNothing() {
        folder("mb-inbox", role = "inbox")

        assertEquals(emptyList<String>(), search("token*"))
    }
}
