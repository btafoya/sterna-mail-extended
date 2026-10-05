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
 * **v32 → v33**: the correspondents' S/MIME certificates, `smime_certificates`. One new table and nothing else: a
 * missing step would let `fallbackToDestructiveMigration()` drop the outbox and the local drafts with the cache.
 */
class SmimeCertificatesMigrationSqlTest {
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

    /** Two tables of a v32 install, filled, as written by the steps before this one. */
    private fun seedV32() {
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, `attachmentsJson`, " +
                    "`createdAtMillis`, `notBeforeMillis`, `state`, `attemptCount`) " +
                    "VALUES ('accA', 'bob@example.org', 'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 2)",
            )
            st.executeUpdate(
                "CREATE TABLE `recent_contacts` (`email` TEXT NOT NULL PRIMARY KEY, `name` TEXT, " +
                    "`lastSeen` INTEGER NOT NULL, `sendCount` INTEGER NOT NULL DEFAULT 1)",
            )
            st.executeUpdate("INSERT INTO `recent_contacts` VALUES ('bob@example.org', 'Bob', 7, 3)")
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

    private val shapes = mapOf(
        "address" to Triple("TEXT", 1, 1),
        "certificate" to Triple("BLOB", 1, 0),
        "messageDate" to Triple("INTEGER", 1, 0),
    )

    @Test fun v32to33_createsTheCertificatesKeyedByAddress() {
        seedV32()

        registeredStep(32).migrate(supportDb())

        assertEquals(shapes, columnShapes("smime_certificates"))
        db.createStatement().use { it.executeUpdate("INSERT INTO smime_certificates VALUES ('a@example.org', x'01', 1)") }
        val second = runCatching {
            db.createStatement().use { it.executeUpdate("INSERT INTO smime_certificates VALUES ('a@example.org', x'02', 2)") }
        }
        assertEquals("one certificate per address", true, second.isFailure)
        assertEquals(33, registeredStep(32).endVersion)
    }

    @Test fun v32to33_addsOneTableAndKeepsEveryRow() {
        seedV32()
        val before = tables()
        val outbox = dump("SELECT * FROM outbox ORDER BY rowid")
        val contacts = dump("SELECT * FROM recent_contacts ORDER BY rowid")
        val outboxShape = columnShapes("outbox")

        registeredStep(32).migrate(supportDb())

        assertEquals(before + "smime_certificates", tables())
        assertEquals(outboxShape, columnShapes("outbox"))
        assertEquals(outbox, dump("SELECT * FROM outbox ORDER BY rowid"))
        assertEquals(contacts, dump("SELECT * FROM recent_contacts ORDER BY rowid"))
    }

    @Test fun theChainReachesTheDeclaredVersionAndTheEntityNamesTheColumns() {
        assertEquals(34, SCHEMA_VERSION)
        assertEquals(
            SCHEMA_VERSION,
            SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion },
        )
        val fields = SmimeCertificateEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .associate { it.name to it.type }
        assertEquals(
            "SmimeCertificateEntity must declare exactly the columns the step creates, or Room opens a table it does not describe",
            mapOf("address" to String::class.java, "certificate" to ByteArray::class.java, "messageDate" to Long::class.javaPrimitiveType),
            fields,
        )
    }

    /**
     * Whether each field of [SmimeCertificateEntity] refuses a null, executed: a primitive cannot hold one, and a
     * reference parameter is handed a null to see whether Kotlin's parameter check throws. Room reads the same
     * nullability, and refuses at open a migrated column whose `NOT NULL` it does not match.
     */
    private fun entityRefusesNull(): Map<String, Boolean> {
        val fields = SmimeCertificateEntity::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val constructor = SmimeCertificateEntity::class.java.declaredConstructors
            .singleOrNull { it.parameterTypes.toList() == fields.map { f -> f.type } }
            ?: error("No constructor of SmimeCertificateEntity takes exactly its backing fields, in order")
        val good = arrayOf<Any?>("a@example.org", byteArrayOf(1), 1L)
        check(good.size == fields.size) { "SmimeCertificateEntity has ${fields.size} fields; teach this test the new one" }
        return fields.withIndex().associate { (i, field) ->
            if (field.type.isPrimitive) return@associate field.name to true
            val args = good.copyOf().also { it[i] = null }
            val refused = try {
                constructor.newInstance(*args)
                false
            } catch (e: java.lang.reflect.InvocationTargetException) {
                check(e.targetException is NullPointerException) { "a null for '${field.name}' threw ${e.targetException}" }
                true
            }
            field.name to refused
        }
    }

    @Test fun theEntityRefusesANullWhereTheStepCreatesNotNull() {
        seedV32()
        registeredStep(32).migrate(supportDb())

        val table = columnShapes("smime_certificates").mapValues { (_, shape) -> shape.second == 1 }
        assertEquals("every column the step creates is NOT NULL", mapOf("address" to true, "certificate" to true, "messageDate" to true), table)
        assertEquals(
            "SmimeCertificateEntity must refuse a null exactly where the table is NOT NULL, or Room refuses the database at open",
            table,
            entityRefusesNull(),
        )
    }
}
