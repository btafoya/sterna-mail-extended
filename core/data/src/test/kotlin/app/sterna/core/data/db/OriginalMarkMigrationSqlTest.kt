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
 * **v36 → v37**: `outbox` and `scheduled_sends` each gain `originalAccountId`, `originalEmailId`
 * and `originalMark` (#259). A missing step would let `fallbackToDestructiveMigration()` drop the
 * outbox and every scheduled message with the cache.
 */
class OriginalMarkMigrationSqlTest {
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

    /** `outbox` and `scheduled_sends` of a v36 install, one waiting message in each. */
    private fun seedV36() {
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            listOf(
                "pgpMode TEXT", "pgpEntityPath TEXT", "draftEmailId TEXT",
                "requestReceipt INTEGER NOT NULL DEFAULT 0", "draftUidValidity INTEGER",
            ).forEach { st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN $it") }
            st.executeUpdate(
                "CREATE TABLE `scheduled_sends` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`accountId` TEXT NOT NULL, `recipients` TEXT NOT NULL, `cc` TEXT, `bcc` TEXT, " +
                    "`subject` TEXT NOT NULL, `textBody` TEXT NOT NULL, `htmlBody` TEXT, `fromName` TEXT, " +
                    "`fromEmail` TEXT, `inReplyTo` TEXT, `references` TEXT, `sendAtMillis` INTEGER NOT NULL, " +
                    "`draftEmailId` TEXT, `draftUidValidity` INTEGER, " +
                    "`requestReceipt` INTEGER NOT NULL DEFAULT 0, `failedAttempts` INTEGER NOT NULL DEFAULT 0, " +
                    "`attachmentsJson` TEXT NOT NULL DEFAULT '[]')",
            )
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, `attachmentsJson`, " +
                    "`createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Re: Lunch', 'yes', '[]', 1, 2, 'QUEUED', 2)",
            )
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, `sendAtMillis`) " +
                    "VALUES ('accA', 'bob@example.org', 'Fwd: Lunch', 'see below', 99)",
            )
        }
    }

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

    private val added = listOf("originalAccountId", "originalEmailId", "originalMark")

    @Test fun v36to37_addsThreeNullableTextColumnsToBothTables() {
        seedV36()
        val outbox = columnShapes("outbox")
        val scheduled = columnShapes("scheduled_sends")

        registeredStep(36).migrate(supportDb())

        val shape = listOf<Any?>("TEXT", 0, null, 0)
        assertEquals(outbox + added.associateWith { shape }, columnShapes("outbox"))
        assertEquals(scheduled + added.associateWith { shape }, columnShapes("scheduled_sends"))
        assertEquals(37, registeredStep(36).endVersion)
    }

    @Test fun v36to37_keepsEveryWaitingMessageAndMarksNoneOfThem() {
        seedV36()
        val outbox = dump("SELECT * FROM outbox ORDER BY rowid")
        val scheduled = dump("SELECT * FROM scheduled_sends ORDER BY rowid")

        registeredStep(36).migrate(supportDb())

        assertEquals(outbox.map { it + listOf(null, null, null) }, dump("SELECT * FROM outbox ORDER BY rowid"))
        assertEquals(scheduled.map { it + listOf(null, null, null) }, dump("SELECT * FROM scheduled_sends ORDER BY rowid"))
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntitiesNameTheColumns() {
        assertEquals(39, SCHEMA_VERSION)
        assertEquals(
            "the step is written and not handed to Room: every install opening v37 loses its outbox and its scheduled mail",
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        seedV36()
        registeredStep(36).migrate(supportDb())
        fun fieldsOf(type: Class<*>) = type.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        assertEquals(
            "OutboxEntity must declare exactly the columns the migrated table holds, or Room refuses the database at open",
            columnShapes("outbox").keys,
            fieldsOf(OutboxEntity::class.java),
        )
        assertEquals(
            "ScheduledSendEntity must declare exactly the columns the migrated table holds, or Room refuses the database at open",
            columnShapes("scheduled_sends").keys,
            fieldsOf(ScheduledSendEntity::class.java),
        )
    }
}
