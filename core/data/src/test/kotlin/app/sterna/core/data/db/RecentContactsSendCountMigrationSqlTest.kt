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
 * **v31 → v32**: `recent_contacts` gains `sendCount`. Every row already there is an address written
 * to at least once, so it must read back as 1, and `lastSeen` must stay the date of that send.
 */
class RecentContactsSendCountMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    /** The table as published up to v31, written by hand. */
    private val v31ContactsCreate =
        "CREATE TABLE `recent_contacts` (" +
            "`email` TEXT NOT NULL PRIMARY KEY, `name` TEXT, `lastSeen` INTEGER NOT NULL)"

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

    private fun seedV31() {
        db.createStatement().use { st ->
            st.executeUpdate(v31ContactsCreate)
            st.executeUpdate("INSERT INTO `recent_contacts` VALUES ('bob@example.org', 'Bob', 7)")
            st.executeUpdate("INSERT INTO `recent_contacts` VALUES ('ann@example.org', NULL, 9)")
        }
    }

    private fun columnShapes(): Map<String, Triple<String, Int, String?>> {
        val shapes = linkedMapOf<String, Triple<String, Int, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`recent_contacts`)").use { rs ->
                while (rs.next()) {
                    shapes[rs.getString("name")] =
                        Triple(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))
                }
            }
        }
        return shapes
    }

    private fun rows(): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery("SELECT email, name, lastSeen, sendCount FROM `recent_contacts` ORDER BY email").use { rs ->
            buildList { while (rs.next()) add((1..4).map { rs.getString(it) }) }
        }
    }

    @Test fun v31to32_addsTheCountAsNotNullIntegerDefaultingToOne() {
        seedV31()
        val before = columnShapes()

        registeredStep(31).migrate(supportDb())

        assertEquals(
            "exactly one column is added, and nothing else in the table moves",
            before + ("sendCount" to Triple("INTEGER", 1, "1")),
            columnShapes(),
        )
        assertEquals(32, registeredStep(31).endVersion)
    }

    @Test fun v31to32_keepsEveryRowAndCountsEachOneSendAtItsDate() {
        seedV31()

        registeredStep(31).migrate(supportDb())

        assertEquals(
            listOf(listOf("ann@example.org", null, "9", "1"), listOf("bob@example.org", "Bob", "7", "1")),
            rows(),
        )
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumn() {
        assertTrue("the schema is past the step", SCHEMA_VERSION >= 32)
        assertEquals(
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        assertTrue(
            "RecentContactEntity must declare sendCount, or Room opens a table it does not describe",
            RecentContactEntity::class.java.declaredFields.any { it.name == "sendCount" && it.type == Int::class.javaPrimitiveType },
        )
    }
}
