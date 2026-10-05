package app.sterna.core.data.mail

import app.sterna.core.data.db.CATCH_UP_OFF_WALK
import app.sterna.core.data.db.CATCH_UP_SILENT
import app.sterna.core.data.db.CatchUpMark
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.upsertKeepingCatchUp
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * What each writer of `emails` does to `catchUp`, run over SQLite: `upsertKeepingCatchUp`, the body of
 * `EmailDao.upsertAll`, with the shipped statements of `catchUpOf`, `markWalked` and `markOffWalk`.
 *
 * What this does not cover:
 * - The statement Room generates for `@Upsert`. `writeWhole` stands in for it with an `INSERT OR
 *   REPLACE` of every column, the property that matters here: the entity's mark lands on the row.
 * - That `EmailDao.upsertAll` runs `upsertKeepingCatchUp` and that no caller reaches the bare
 *   `upsertRows`: Room is not on this classpath, so both are read as text, in the last two tests.
 * - That the read and the write of one `upsertAll` share a transaction: `@Transaction` is read as text.
 */
class CatchUpMarkSqlTest {
    private lateinit var db: Connection
    private val readSizes = mutableListOf<Int>()

    private companion object {
        const val BOTH = CATCH_UP_OFF_WALK or CATCH_UP_SILENT
    }

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE `emails` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
                    "`subject` TEXT, `catchUp` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`accountId`, `id`))",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, mailboxId: String = "inbox", accountId: String = "A", subject: String = "from the server", catchUp: Int = 0) =
        Email(id = id, subject = subject).toEntity(accountId, mailboxId).copy(catchUp = catchUp)

    private fun writeWhole(rows: List<EmailEntity>) {
        db.prepareStatement("INSERT OR REPLACE INTO emails (accountId, id, mailboxId, subject, catchUp) VALUES (?, ?, ?, ?, ?)").use { st ->
            rows.forEach {
                st.setString(1, it.accountId); st.setString(2, it.id); st.setString(3, it.mailboxId)
                st.setString(4, it.subject); st.setInt(5, it.catchUp)
                st.executeUpdate()
            }
        }
    }

    private fun seed(vararg rows: EmailEntity) = writeWhole(rows.map { it.copy(subject = "as cached") })

    private fun catchUpOf(accountId: String, ids: List<String>): List<CatchUpMark> {
        readSizes += ids.size
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("catchUpOf"), mapOf("ids" to ids.size))
        val pending = ids.iterator()
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name -> st.setString(i + 1, if (name == "accountId") accountId else pending.next()) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(CatchUpMark(rs.getString("id"), rs.getInt("catchUp"))) } }
        }
    }

    private fun markWalked(accountId: String, mailboxId: String, ids: List<String>): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("markWalked"), mapOf("ids" to ids.size))
        val pending = ids.iterator()
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name ->
                st.setString(
                    i + 1,
                    when (name) {
                        "accountId" -> accountId
                        "mailboxId" -> mailboxId
                        else -> pending.next()
                    },
                )
            }
            st.executeUpdate()
        }
    }

    private fun markOffWalk(accountId: String, ids: List<String>): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("markOffWalk"), mapOf("ids" to ids.size))
        val pending = ids.iterator()
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name -> st.setString(i + 1, if (name == "accountId") accountId else pending.next()) }
            st.executeUpdate()
        }
    }

    private fun upsertAll(rows: List<EmailEntity>) =
        runBlocking { upsertKeepingCatchUp(rows, { accountId, ids -> catchUpOf(accountId, ids) }, { writeWhole(it) }) }

    private fun listPage(mailboxId: String, rows: List<EmailEntity>): Int {
        upsertAll(rows)
        return markWalked("A", mailboxId, rows.map { it.id })
    }

    /** `account/id` to `folder|subject|mark` of every row. */
    private fun table(): Map<String, String> = db.createStatement().use { st ->
        st.executeQuery("SELECT accountId, id, mailboxId, subject, catchUp FROM emails ORDER BY accountId, id").use { rs ->
            buildMap {
                while (rs.next()) put("${rs.getString(1)}/${rs.getString(2)}", "${rs.getString(3)}|${rs.getString(4)}|${rs.getInt(5)}")
            }
        }
    }

    @Test fun `written bare, an entity mapped from the server erases the mark of the row it overwrites`() {
        seed(row("m", catchUp = BOTH))

        writeWhole(listOf(row("m")))

        assertEquals("the stand-in for @Upsert must carry the trap, or nothing below measures it", mapOf("A/m" to "inbox|from the server|0"), table())
    }

    @Test fun `a delta re-reading a marked row rewrites it and keeps each of its bits`() {
        seed(row("both", catchUp = BOTH), row("off-walk", catchUp = CATCH_UP_OFF_WALK), row("silent", catchUp = CATCH_UP_SILENT), row("plain"))

        upsertAll(listOf(row("both"), row("off-walk"), row("silent"), row("plain")))

        assertEquals(
            mapOf(
                "A/both" to "inbox|from the server|$BOTH",
                "A/off-walk" to "inbox|from the server|$CATCH_UP_OFF_WALK",
                "A/plain" to "inbox|from the server|0",
                "A/silent" to "inbox|from the server|$CATCH_UP_SILENT",
            ),
            table(),
        )
    }

    @Test fun `a row the cache does not hold is written with the mark it comes with`() {
        upsertAll(listOf(row("new"), row("put-back", catchUp = BOTH)))

        assertEquals(mapOf("A/new" to "inbox|from the server|0", "A/put-back" to "inbox|from the server|$BOTH"), table())
    }

    @Test fun `an upsert never sets a mark on a row cached without one`() {
        seed(row("m"))

        upsertAll(listOf(row("m", catchUp = BOTH)))

        assertEquals(mapOf("A/m" to "inbox|from the server|0"), table())
    }

    @Test fun `the mark kept is the one of the same account's row`() {
        seed(row("m", catchUp = BOTH), row("m", accountId = "B"))

        upsertAll(listOf(row("m", accountId = "B"), row("n", accountId = "B")))

        assertEquals(
            mapOf("A/m" to "inbox|as cached|$BOTH", "B/m" to "inbox|from the server|0", "B/n" to "inbox|from the server|0"),
            table(),
        )
    }

    @Test fun `a page too long for one read keeps the mark of its last row`() {
        val ids = (1..1200).map { "m$it" }
        seed(row(ids.last(), catchUp = BOTH))

        upsertAll(ids.map { row(it) })

        assertEquals("inbox|from the server|$BOTH", table()["A/${ids.last()}"])
        assertEquals(1200, table().size)
        assertTrue("one read bound ${readSizes.max()} ids, over SQLite's 999 variables with the account", readSizes.max() < 999)
    }

    @Test fun `a list page takes its rows off the catch-up and leaves them silent`() {
        seed(row("both", catchUp = BOTH), row("off-walk", catchUp = CATCH_UP_OFF_WALK), row("silent", catchUp = CATCH_UP_SILENT), row("plain"))

        val changed = listPage("inbox", listOf(row("both"), row("off-walk"), row("silent"), row("plain"), row("new")))

        assertEquals(2, changed)
        assertEquals(
            mapOf(
                "A/both" to "inbox|from the server|$CATCH_UP_SILENT",
                "A/new" to "inbox|from the server|0",
                "A/off-walk" to "inbox|from the server|0",
                "A/plain" to "inbox|from the server|0",
                "A/silent" to "inbox|from the server|$CATCH_UP_SILENT",
            ),
            table(),
        )
    }

    @Test fun `the retention prune's mark sets the off-walk bit alone, on the rows it named in its account`() {
        seed(
            row("plain"), row("silent", catchUp = CATCH_UP_SILENT), row("both", catchUp = BOTH),
            row("not-named"), row("plain", accountId = "B"),
        )

        val changed = markOffWalk("A", listOf("plain", "silent", "both"))

        assertEquals(3, changed)
        assertEquals(
            mapOf(
                "A/both" to "inbox|as cached|$BOTH",
                "A/not-named" to "inbox|as cached|0",
                "A/plain" to "inbox|as cached|$CATCH_UP_OFF_WALK",
                "A/silent" to "inbox|as cached|$BOTH",
                "B/plain" to "inbox|as cached|0",
            ),
            table(),
        )
    }

    @Test fun `a list page clears nothing outside the rows it named, its folder and its account`() {
        seed(
            row("named-elsewhere", mailboxId = "work", catchUp = BOTH),
            row("not-named", catchUp = BOTH),
            row("named", accountId = "B", catchUp = BOTH),
            row("named", catchUp = BOTH),
        )

        val changed = markWalked("A", "inbox", listOf("named", "named-elsewhere"))

        assertEquals(1, changed)
        assertEquals(
            mapOf(
                "A/named" to "inbox|as cached|$CATCH_UP_SILENT",
                "A/named-elsewhere" to "work|as cached|$BOTH",
                "A/not-named" to "inbox|as cached|$BOTH",
                "B/named" to "inbox|as cached|$BOTH",
            ),
            table(),
        )
    }

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "core/data/src/main/kotlin").isDirectory) dir = dir.parentFile
        dir ?: error("Cannot find the repository root from ${System.getProperty("user.dir")}")
    }

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") }

    /** Text, as a last resort: Room is not on this classpath, so the DAO's own function cannot be run. */
    @Test fun `upsertAll is the mark-keeping write, in one transaction, and nothing else reaches the bare upsert`() {
        val dao = codeLines(File(root, "core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt"))
        val bare = dao.indexOf("suspend fun upsertRows(emails: List<EmailEntity>)")
        assertTrue("EmailDao.upsertRows is no longer declared as this test reads it", bare > 0)
        assertEquals("@Upsert", dao[bare - 1])
        assertEquals(
            listOf(
                "@Transaction",
                "suspend fun upsertAll(emails: List<EmailEntity>) =",
                "upsertKeepingCatchUp(emails, { accountId, ids -> catchUpOf(accountId, ids) }, { upsertRows(it) })",
            ),
            dao.subList(bare + 1, bare + 4),
        )
        assertEquals("@Upsert writes every column: only upsertRows may carry it", 1, dao.count { it == "@Upsert" })

        val callers = listOf("core", "app").flatMap { module ->
            File(root, module).walkTopDown()
                .onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> codeLines(file).filter { "upsertRows(" in it }.map { file.name } }
                .toList()
        }
        assertEquals("the declaration and the one call inside upsertAll", listOf("EmailDao.kt", "EmailDao.kt"), callers)
    }

    /** Text, as a last resort: no test here can run `syncMailbox` or the list's mediator. */
    @Test fun `only the three list pages take rows off the catch-up, each right after its write, and the delta does not`() {
        val repository = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
        val pageWrites = mapOf(
            "markWalked(localAccountId, mailboxId, fresh)" to
                "emailDao.upsertAll(sparingInFlight(fresh.map { it.toEntity(localAccountId, mailboxId) }))",
            "markWalked(credentials.id, mailboxId, page.emails)" to
                "emailDao.upsertAll(sparingInFlight(page.emails.map { it.toEntity(credentials.id, mailboxId) }))",
        )
        val named = repository.withIndex().filter { "markWalked(" in it.value }
        assertEquals(
            listOf(
                "markWalked(localAccountId, mailboxId, fresh)",
                "markWalked(credentials.id, mailboxId, page.emails)",
                "markWalked(credentials.id, mailboxId, page.emails)",
                "private suspend fun markWalked(accountId: String, mailboxId: String, page: List<Email>) =",
                "page.map { it.id }.chunked(MAX_CHANGES).forEach { emailDao.markWalked(accountId, mailboxId, it) }",
            ),
            named.map { it.value },
        )
        named.take(3).forEach { (at, call) -> assertEquals("the write $call follows", pageWrites.getValue(call), repository[at - 1]) }

        val delta = repository.indexOf("emailDao.upsertAll(sparingInFlight(fetched.map { it.toEntity(localAccountId, mailboxId) }))")
        assertTrue("the delta's write is no longer the line this test reads", delta > 0)
        assertEquals("the delta's write closes its block: nothing clears a mark after it", "}", repository[delta + 1])

        val elsewhere = File(root, "core/data/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MailRepository.kt" }
            .filter { file -> codeLines(file).any { ".markWalked(" in it } }
            .map { it.name }.toList()
        assertEquals("no other file clears the mark", emptyList<String>(), elsewhere)
    }
}
