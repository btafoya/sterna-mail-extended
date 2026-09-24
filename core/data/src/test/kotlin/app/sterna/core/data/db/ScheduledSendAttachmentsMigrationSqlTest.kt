package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * **v30 → v31**: `scheduled_sends` gains `attachmentsJson` ([ScheduledSendEntity.attachmentsJson]),
 * in the outbox's own format. A row scheduled before the step carried no attachment, so it must
 * read back as the empty list, never NULL.
 *
 * The whole chain from a published install is run by the older migration tests, which now arrive
 * at this step too; this one measures the step alone, on the v30 shape of the table.
 */
class ScheduledSendAttachmentsMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    private val v30ScheduledCreate =
        "CREATE TABLE `scheduled_sends` (" +
            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `accountId` TEXT NOT NULL, " +
            "`recipients` TEXT NOT NULL, `cc` TEXT, `bcc` TEXT, `subject` TEXT NOT NULL, " +
            "`textBody` TEXT NOT NULL, `htmlBody` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
            "`inReplyTo` TEXT, `references` TEXT, `sendAtMillis` INTEGER NOT NULL, " +
            "`draftEmailId` TEXT, `draftUidValidity` INTEGER, " +
            "`requestReceipt` INTEGER NOT NULL DEFAULT 0, `failedAttempts` INTEGER NOT NULL DEFAULT 0)"

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
                "finds no path and fallbackToDestructiveMigration() drops every table, scheduled sends included",
            step,
        )
        return step!!
    }

    private fun seedV30() {
        db.createStatement().use { st ->
            st.executeUpdate(v30ScheduledCreate)
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`sendAtMillis`, `draftEmailId`, `requestReceipt`, `failedAttempts`) " +
                    "VALUES ('accA', 'bob@example.org', 'Later', 'the only copy', 500, 'draft-7', 1, 2)",
            )
        }
    }

    private fun columnShapes(): Map<String, Triple<String, Int, String?>> {
        val shapes = linkedMapOf<String, Triple<String, Int, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`scheduled_sends`)").use { rs ->
                while (rs.next()) {
                    shapes[rs.getString("name")] =
                        Triple(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))
                }
            }
        }
        return shapes
    }

    private fun rows(columns: List<String>): List<List<String?>> {
        val out = mutableListOf<List<String?>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT ${columns.joinToString { "`$it`" }} FROM `scheduled_sends` ORDER BY rowid").use { rs ->
                while (rs.next()) out += columns.indices.map { rs.getString(it + 1) }
            }
        }
        return out
    }

    @Test fun v30to31_addsTheAttachmentsColumnAsNotNullTextDefaultingToTheEmptyList() {
        seedV30()
        val before = columnShapes()

        registeredStep(30).migrate(supportDb())

        assertEquals(
            "exactly one column is added, and nothing else in the table moves",
            before + ("attachmentsJson" to Triple("TEXT", 1, "'[]'")),
            columnShapes(),
        )
        assertEquals(31, registeredStep(30).endVersion)
    }

    @Test fun v30to31_keepsTheRowWordForWordAndReadsItAsCarryingNoAttachment() {
        seedV30()
        val columns = columnShapes().keys.toList()
        val before = rows(columns)

        registeredStep(30).migrate(supportDb())

        assertEquals(before, rows(columns))
        assertEquals(listOf(listOf("Later", "[]")), rows(listOf("subject", "attachmentsJson")))
        assertEquals(emptyList<OutboxAttachment>(), OutboxAttachments.decode(rows(listOf("attachmentsJson")).single().single()))
    }

    @Test fun theSchemaDeclaresTheStepAndTheEntityNamesItsColumn() {
        assertEquals(31, SCHEMA_VERSION)
        assertTrue(
            "ScheduledSendEntity must declare attachmentsJson, or Room opens a table it does not describe",
            ScheduledSendEntity::class.java.declaredFields.any { it.name == "attachmentsJson" && it.type == String::class.java },
        )
    }
}
