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
 * **v38 → v39**: the messages the download ahead will not read again, `download_ahead_skips`. One
 * new table and nothing else: a missing step would let `fallbackToDestructiveMigration()` drop the
 * outbox and the local drafts with the cache.
 */
class DownloadAheadSkipsMigrationSqlTest {
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

    /** Two tables of a v38 install, filled. */
    private fun seedV38() {
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, `attachmentsJson`, " +
                    "`createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 2)",
            )
            st.executeUpdate(
                "CREATE TABLE `email_bodies` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
                    "`inlineImagesJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accA', '{}', '{}', 42)")
        }
    }

    private fun tables(): Set<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
    }

    /** `type`, `notnull`, `pk` of each column, as `PRAGMA table_info` declares them. */
    private fun columnShapes(table: String): Map<String, Triple<String, Int, Int>> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull"), rs.getInt("pk"))) }
        }
    }

    private fun dump(sql: String): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val width = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..width).map { rs.getString(it) }) }
        }
    }

    @Test fun v38to39_createsTheSkipsKeyedByAccountThenId() {
        seedV38()

        registeredStep(38).migrate(supportDb())

        assertEquals(
            mapOf("accountId" to Triple("TEXT", 1, 1), "id" to Triple("TEXT", 1, 2)),
            columnShapes("download_ahead_skips"),
        )
        db.createStatement().use {
            it.executeUpdate("INSERT INTO download_ahead_skips VALUES ('accA', 'm1')")
            it.executeUpdate("INSERT INTO download_ahead_skips VALUES ('accB', 'm1')")
        }
        val second = runCatching { db.createStatement().use { it.executeUpdate("INSERT INTO download_ahead_skips VALUES ('accA', 'm1')") } }
        assertEquals("one mark per message of an account", true, second.isFailure)
        assertEquals(39, registeredStep(38).endVersion)
    }

    @Test fun v38to39_addsOneTableAndKeepsEveryRow() {
        seedV38()
        val before = tables()
        val outbox = dump("SELECT * FROM outbox ORDER BY rowid")
        val bodies = dump("SELECT * FROM email_bodies ORDER BY rowid")

        registeredStep(38).migrate(supportDb())

        assertEquals(before + "download_ahead_skips", tables())
        assertEquals(outbox, dump("SELECT * FROM outbox ORDER BY rowid"))
        assertEquals(bodies, dump("SELECT * FROM email_bodies ORDER BY rowid"))
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumns() {
        assertEquals(40, SCHEMA_VERSION)
        assertEquals(
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        val fields = DownloadAheadSkipEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name to it.type }
        assertEquals(
            "DownloadAheadSkipEntity must declare exactly the columns the step creates, both non-null " +
                "Strings, in key order, or Room refuses the migrated table at open",
            listOf("accountId" to String::class.java, "id" to String::class.java),
            fields,
        )
        val refused = runCatching {
            DownloadAheadSkipEntity::class.java.declaredConstructors
                .single { it.parameterTypes.toList() == listOf(String::class.java, String::class.java) }
                .newInstance(null, "m1")
        }
        assertEquals("a null accountId is refused, as `NOT NULL` is", true, refused.isFailure)
    }
}
