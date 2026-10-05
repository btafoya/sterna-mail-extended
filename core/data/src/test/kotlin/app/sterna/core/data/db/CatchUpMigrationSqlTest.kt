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
 * **v33 → v34**: `emails` gains `catchUp`. One column and nothing else: a missing step would let
 * `fallbackToDestructiveMigration()` drop the outbox and the local drafts with the cache.
 */
class CatchUpMigrationSqlTest {
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

    /** `emails` and `outbox` of a v33 install, filled, as the steps before this one leave them. */
    private fun seedV33() {
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
            listOf("recipientsJson TEXT", "replyToJson TEXT", "ccJson TEXT", "bccJson TEXT", "uidValidity INTEGER").forEach {
                st.executeUpdate("ALTER TABLE `emails` ADD COLUMN $it")
            }
            st.executeUpdate(
                "CREATE INDEX `index_emails_accountId_mailboxId_sortKey` ON `emails` (`accountId`, `mailboxId`, `sortKey`)",
            )
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, `hasAttachment`, `sortKey`, `uidValidity`) " +
                    "VALUES ('e1', 'accA', 'mb1', 'Hello', 0, 1, 0, 100, 7), ('e1', 'accB', 'mb1', 'World', 1, 0, 0, 200, NULL)",
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

    @Test fun v33to34_addsTheMarkAsANonNullIntegerThatDefaultsToZero() {
        seedV33()
        val before = columnShapes("emails")

        registeredStep(33).migrate(supportDb())

        assertEquals(before + ("catchUp" to listOf("INTEGER", 1, "0", 0)), columnShapes("emails"))
        assertEquals(34, registeredStep(33).endVersion)
    }

    @Test fun v33to34_everyCachedRowReadsAsNeitherCaughtUpNorSilent() {
        seedV33()
        val rows = dump("SELECT * FROM emails ORDER BY accountId")

        registeredStep(33).migrate(supportDb())

        assertEquals(rows.map { it + "0" }, dump("SELECT * FROM emails ORDER BY accountId"))
    }

    @Test fun v33to34_keepsTheKeyTheIndexesAndTheOutbox() {
        seedV33()
        val indexes = indexes()
        val outboxShape = columnShapes("outbox")
        val outbox = dump("SELECT * FROM outbox ORDER BY rowid")

        registeredStep(33).migrate(supportDb())

        assertEquals(setOf("index_emails_mailboxId", "index_emails_accountId_mailboxId_sortKey"), indexes)
        assertEquals(indexes, indexes())
        assertEquals(outboxShape, columnShapes("outbox"))
        assertEquals(outbox, dump("SELECT * FROM outbox ORDER BY rowid"))
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumn() {
        assertEquals(34, SCHEMA_VERSION)
        assertEquals(
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        seedV33()
        registeredStep(33).migrate(supportDb())
        val fields = EmailEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertEquals(
            "EmailEntity must declare exactly the columns the migrated table holds, or Room refuses the database at open",
            columnShapes("emails").keys,
            fields.map { it.name }.toSet(),
        )
        assertEquals(Int::class.javaPrimitiveType, fields.single { it.name == "catchUp" }.type)
    }
}
