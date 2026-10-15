package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v35 → v36**: `emails` gains `answered` and `forwarded` (#259), and every folder's
 * `highestModSeq` falls so its next read is a full walk that fills them in. A missing step would
 * let `fallbackToDestructiveMigration()` drop the outbox and the local drafts with the cache.
 */
class RepliedFlagsMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    private fun supportDb(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args?.size == 1 -> {
                    db.createStatement().use { it.executeUpdate(args[0] as String) }
                    null
                }
                method.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
                method.name == "hashCode" -> System.identityHashCode(this)
                method.name == "equals" -> false
                else -> error("Unexpected SupportSQLiteDatabase.${method.name} in a migration")
            }
        } as SupportSQLiteDatabase

    private fun registeredStep(from: Int): Migration {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == from }
        assertNotNull(
            "no migration starting at v$from is registered in SternaDatabase.ALL_MIGRATIONS: Room " +
                "finds no path and fallbackToDestructiveMigration() drops every table, the outbox included",
            step,
        )
        return step!!
    }

    /** `emails` and `outbox` of a v35 install, filled, as the steps before this one leave them. */
    private fun seedV35() {
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
            listOf(
                "recipientsJson TEXT", "replyToJson TEXT", "ccJson TEXT", "bccJson TEXT", "uidValidity INTEGER",
                "catchUp INTEGER NOT NULL DEFAULT 0",
            ).forEach {
                st.executeUpdate("ALTER TABLE `emails` ADD COLUMN $it")
            }
            st.executeUpdate(
                "CREATE INDEX `index_emails_accountId_mailboxId_sortKey` ON `emails` (`accountId`, `mailboxId`, `sortKey`)",
            )
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, `hasAttachment`, `sortKey`, `uidValidity`, `catchUp`) " +
                    "VALUES ('e1', 'accA', 'mb1', 'Hello', 0, 1, 0, 100, 7, 3), ('e1', 'accB', 'mb1', 'World', 1, 0, 0, 200, NULL, 0)",
            )
            st.executeUpdate(MAILBOX_UIDVALIDITY_CREATE_SQL)
            st.executeUpdate(MAILBOX_UIDVALIDITY_ADD_MODSEQ_SQL)
            st.executeUpdate(
                "INSERT INTO `mailbox_uidvalidity` (`accountId`, `mailboxId`, `uidValidity`, `highestModSeq`) " +
                    "VALUES ('accA', 'INBOX', 7, 500), ('accA', 'Sent', 8, NULL), ('accB', 'INBOX', 9, 42)",
            )
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, `attachmentsJson`, " +
                    "`createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 2)",
            )
        }
    }

    /** `type`, `notnull`, `dflt_value`, `pk` of each column, as `PRAGMA table_info` declares them. */
    private fun columnShapes(table: String): Map<String, List<Any?>> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildMap {
                while (rs.next()) {
                    put(rs.getString("name"), listOf(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk")))
                }
            }
        }
    }

    private fun dump(sql: String): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val width = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..width).map { rs.getString(it) }) }
        }
    }

    private fun indexes(): Set<String> = dump("PRAGMA index_list(`emails`)").map { it[1]!! }.filterNot { it.startsWith("sqlite_") }.toSet()

    @Test fun v35to36_addsTwoNonNullIntegersThatDefaultToZero() {
        seedV35()
        val before = columnShapes("emails")

        registeredStep(35).migrate(supportDb())

        assertEquals(
            before + ("answered" to listOf("INTEGER", 1, "0", 0)) + ("forwarded" to listOf("INTEGER", 1, "0", 0)),
            columnShapes("emails"),
        )
        assertEquals(36, registeredStep(35).endVersion)
    }

    @Test fun v35to36_everyCachedRowReadsAsNeitherAnsweredNorForwarded() {
        seedV35()
        val rows = dump("SELECT * FROM emails ORDER BY accountId")

        registeredStep(35).migrate(supportDb())

        assertEquals(rows.map { it + "0" + "0" }, dump("SELECT * FROM emails ORDER BY accountId"))
    }

    @Test fun v35to36_dropsEveryRecordedModSeqAndKeepsTheNumbering() {
        // A delta from the recorded mod-sequence brings back only what changed since: a message
        // answered elsewhere before the update would never be read again and keep no mark.
        seedV35()
        val shape = columnShapes("mailbox_uidvalidity")

        registeredStep(35).migrate(supportDb())

        assertEquals(
            listOf(
                listOf("accA", "INBOX", "7", null),
                listOf("accA", "Sent", "8", null),
                listOf("accB", "INBOX", "9", null),
            ),
            dump("SELECT accountId, mailboxId, uidValidity, highestModSeq FROM mailbox_uidvalidity ORDER BY accountId, mailboxId"),
        )
        assertEquals(shape, columnShapes("mailbox_uidvalidity"))
    }

    @Test fun v35to36_keepsTheKeyTheIndexesAndTheOutbox() {
        seedV35()
        val indexes = indexes()
        val outboxShape = columnShapes("outbox")
        val outbox = dump("SELECT * FROM outbox ORDER BY rowid")

        registeredStep(35).migrate(supportDb())

        assertEquals(setOf("index_emails_mailboxId", "index_emails_accountId_mailboxId_sortKey"), indexes)
        assertEquals(indexes, indexes())
        assertEquals(outboxShape, columnShapes("outbox"))
        assertEquals(outbox, dump("SELECT * FROM outbox ORDER BY rowid"))
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumns() {
        assertEquals(36, SCHEMA_VERSION)
        assertEquals(
            "the step is written and not handed to Room: every install opening v36 loses its cache, its outbox and its local drafts",
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        seedV35()
        registeredStep(35).migrate(supportDb())
        val fields = EmailEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertEquals(
            "EmailEntity must declare exactly the columns the migrated table holds, or Room refuses the database at open",
            columnShapes("emails").keys,
            fields.map { it.name }.toSet(),
        )
        assertEquals(Boolean::class.javaPrimitiveType, fields.single { it.name == "answered" }.type)
        assertEquals(Boolean::class.javaPrimitiveType, fields.single { it.name == "forwarded" }.type)
    }
}
