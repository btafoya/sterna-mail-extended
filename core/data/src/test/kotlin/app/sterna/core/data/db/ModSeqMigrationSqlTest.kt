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
 * **v34 → v35**: `mailbox_uidvalidity` gains `highestModSeq`. One nullable column and nothing else:
 * a folder recorded before it keeps its numbering and has no mod-sequence, which reads "walk it in
 * full".
 */
class ModSeqMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(MAILBOX_UIDVALIDITY_CREATE_SQL)
            st.executeUpdate("INSERT INTO `mailbox_uidvalidity` VALUES ('accA', 'INBOX', 77), ('accB', 'INBOX', 3)")
        }
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

    /** `type`, `notnull`, `dflt_value`, `pk` of each column, as `PRAGMA table_info` declares them. */
    private fun columnShapes(): Map<String, List<Any?>> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`mailbox_uidvalidity`)").use { rs ->
            buildMap {
                while (rs.next()) {
                    put(rs.getString("name"), listOf(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk")))
                }
            }
        }
    }

    private fun dump(): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery("SELECT * FROM mailbox_uidvalidity ORDER BY accountId").use { rs ->
            val width = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..width).map { rs.getString(it) }) }
        }
    }

    @Test fun v34to35_addsANullableIntegerWithNoDefault() {
        val before = columnShapes()

        registeredStep(34).migrate(supportDb())

        assertEquals(before + ("highestModSeq" to listOf("INTEGER", 0, null, 0)), columnShapes())
        assertEquals(35, registeredStep(34).endVersion)
    }

    @Test fun v34to35_everyRecordedFolderKeepsItsNumberingAndHasNoModSeq() {
        val rows = dump()

        registeredStep(34).migrate(supportDb())

        assertEquals(listOf(listOf("accA", "INBOX", "77"), listOf("accB", "INBOX", "3")), rows)
        assertEquals(rows.map { it + listOf<String?>(null) }, dump())
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumns() {
        assertEquals(38, SCHEMA_VERSION)
        assertEquals(
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        registeredStep(34).migrate(supportDb())
        val fields = MailboxUidValidityEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertEquals(
            "MailboxUidValidityEntity must declare exactly the columns the migrated table holds, or Room refuses the database at open",
            columnShapes().keys,
            fields.map { it.name }.toSet(),
        )
        assertEquals(Long::class.javaObjectType, fields.single { it.name == "highestModSeq" }.type)
    }
}
