package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.filter.SourceText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * The two sources of one schema, held to saying the same thing.
 *
 * A FRESH install builds `emails` from the `@Entity` annotation: Room reads `indices = [...]`, names
 * each index `index_<table>_<column>_…` and creates it. An UPDATED install builds the same table
 * from the `CREATE INDEX` of the 26→27 migration. Room then compares the two at every open
 * (`validateMigration`), and a disagreement is not cosmetic: the updated install fails to open on
 * the schema it just migrated to, and the user's way out is a reinstall — which takes the outbox
 * with it.
 *
 * Half of this test reads SOURCE TEXT, which is a last resort, and the reason is the same one
 * [SCHEMA_VERSION] is a named constant: `@Entity` is not retained at runtime (`AnnotationRetention`
 * is CLASS for Room's annotations), so what the entity declares cannot be read reflectively in a
 * JVM test, and `EmailEntity` cannot be handed to a real Room without a device. The other half
 * EXECUTES the shipped migration and reads the index back out of SQLite, so the agreement is
 * measured on one real artefact and one pinned line rather than on two pinned lines.
 *
 * And it reads that index back through `PRAGMA index_xinfo` / `PRAGMA index_list`, the two
 * pragmas Room itself uses — never `PRAGMA index_info`, which gives the column names and hides the
 * direction, the uniqueness and the collation. Those three are the ways the two sources can drift
 * apart while every name-level assertion stays green.
 *
 * The source half pins the WHOLE `indices = …` line by equality, never `contains`: a `contains`
 * is satisfied by every mutation that makes the line LONGER — adding a third index, widening the
 * composite one — and that exact blind spot has been found three times in this repository.
 *
 * What losing this test costs: the entity and the migration drift apart silently. Nothing fails at
 * build time, the JVM suite stays green, and the breakage appears on the phones of the users who
 * UPDATE (never on a fresh install, so never in a quick bench check).
 */
class EmailIndexEntityAndMigrationAgreeTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    private val entitySource: String
        get() = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailEntity.kt")

    /** The single `indices = …` line of [EmailEntity], comments dropped, whitespace squeezed. */
    private fun indicesLine(): String {
        val lines = SourceText.codeLines(entitySource).filter { it.startsWith("indices") }
        assertEquals(
            "EmailEntity must declare its indices on ONE line starting with `indices` — this test " +
                "pins that line whole, and a line split across several is a change to review, not " +
                "something to tolerate silently. Found: $lines",
            1,
            lines.size,
        )
        return lines.single().replace(Regex("\\s+"), " ").trim()
    }

    // --- (a) the entity's declaration, pinned whole -------------------------------------------------

    /**
     * Equality on the whole line. It fails if the composite index disappears (the defect this
     * branch fixes comes straight back), and it fails just as loudly if something is ADDED — a
     * fourth column, a third index, a `unique = true` — because every one of those is a schema
     * change that needs its own migration step, and Room will refuse to open an updated install
     * that never ran one.
     */
    @Test fun theEntityDeclaresTheMailboxIndexAndTheCompositeOne() {
        assertEquals(
            "the `indices` line of EmailEntity is the schema a FRESH install gets. Changing it " +
                "without a matching migration step breaks every UPDATED install at open time, and " +
                "removing the composite index brings back the blank list on a folder switch with " +
                "the conversation view on.",
            "indices = [Index(\"mailboxId\"), Index(value = [\"accountId\", \"mailboxId\", \"sortKey\"])],",
            indicesLine(),
        )
    }

    // --- (b) what the shipped migration really creates ----------------------------------------------

    /**
     * The executed half: the registered 26→27 step runs against a real SQLite, and the index it
     * leaves behind is read back — name and column order — then compared with what the entity line
     * above declares. Nothing here re-states the decision; both sides are read from what ships.
     */
    @Test fun theMigrationCreatesExactlyWhatTheEntityDeclares() {
        val declared = compositeColumnsDeclaredByTheEntity()

        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
        }
        registeredStep26().migrate(supportDb())

        val name = "index_emails_" + declared.joinToString("_")
        assertEquals(
            "Room names an index `index_<table>_<col>_<col>…`, so the entity line above makes the " +
                "fresh install's index `$name`; the migration must create that same name, or an " +
                "updated install carries an index Room does not recognise and validateMigration " +
                "fails at open. Indexes found on `emails`: ${indexesOnEmails()}",
            listOf(name),
            indexesOnEmails().filter { it != "index_emails_mailboxId" && !it.startsWith("sqlite_autoindex") },
        )
        assertEquals(
            "and in the same column ORDER: (accountId, mailboxId) is the scope folderScopeSql " +
                "binds, sortKey last is the list's ORDER BY and the representative join",
            declared,
            indexColumns(name),
        )
        assertEquals(
            "and in the same SHAPE. `Index(value = [...])` on the entity declares no direction and " +
                "no uniqueness, so Room builds a plain ascending non-unique index on a FRESH " +
                "install; Room then reads `PRAGMA index_xinfo` and `PRAGMA index_list` back at " +
                "every open and compares `orders` and `unique` (TableInfo.Index.equals). A `DESC` " +
                "or a `UNIQUE` in the migration alone therefore diverges from the entity in a way " +
                "the column names cannot show, and validateMigration throws on every launch of an " +
                "UPDATED install — never on a fresh one, so never in a quick bench check. The way " +
                "out for the user is a reinstall, which takes the outbox with it.",
            "unique=0 partial=0 " + declared.joinToString(", ") { "$it ASC BINARY" },
            indexShape(name),
        )
    }

    /** The composite index's columns, read out of the entity's pinned `indices` line. */
    private fun compositeColumnsDeclaredByTheEntity(): List<String> {
        val match = Regex("Index\\(value = \\[([^\\]]*)]\\)").find(indicesLine())
        assertNotNull(
            "EmailEntity declares no `Index(value = [...])` on `emails`: the composite index a " +
                "fresh install would build is gone, so a fresh install and an updated one no " +
                "longer hold the same schema. Line was: ${indicesLine()}",
            match,
        )
        return match!!.groupValues[1].split(",").map { it.trim().trim('"') }
    }

    private fun registeredStep26(): androidx.room.migration.Migration {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == 26 }
        assertNotNull(
            "no migration starting at v26 is registered in SternaDatabase.ALL_MIGRATIONS " +
                "(registered: " +
                SternaDatabase.ALL_MIGRATIONS.joinToString { "${it.startVersion}->${it.endVersion}" } +
                "): the entity would declare an index no updated install ever creates",
            step,
        )
        return step!!
    }

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

    private fun indexesOnEmails(): List<String> {
        val names = mutableListOf<String>()
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT `name` FROM `sqlite_master` WHERE `type` = 'index' AND `tbl_name` = 'emails' " +
                    "ORDER BY `name`",
            ).use { rs ->
                while (rs.next()) names += rs.getString("name")
            }
        }
        return names
    }

    /**
     * The full SHAPE of [index]: `unique=<0|1> partial=<0|1>` then its key columns in index order,
     * each with direction and collation.
     *
     * `PRAGMA index_xinfo` and `PRAGMA index_list`, never `PRAGMA index_info` — the latter
     * returns column names and nothing else, and direction / uniqueness / collation are precisely
     * the three ways the migration can build something the entity does not declare while every
     * name-level assertion stays green. These are the two pragmas Room itself reads.
     * (`index_xinfo` also lists the auxiliary rowid columns, marked `key = 0`.)
     */
    private fun indexShape(index: String): String {
        var unique = "?"
        var partial = "?"
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`emails`)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == index) {
                        unique = rs.getInt("unique").toString()
                        partial = rs.getInt("partial").toString()
                    }
                }
            }
        }
        val bySeq = sortedMapOf<Int, String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_xinfo(`$index`)").use { rs ->
                while (rs.next()) {
                    if (rs.getInt("key") != 1) continue
                    val dir = if (rs.getInt("desc") == 1) "DESC" else "ASC"
                    bySeq[rs.getInt("seqno")] = "${rs.getString("name")} $dir ${rs.getString("coll")}"
                }
            }
        }
        return "unique=$unique partial=$partial " + bySeq.values.joinToString(", ")
    }

    /** The indexed columns of [index] alone, in index order — [indexShape] without the rest. */
    private fun indexColumns(index: String): List<String> =
        indexShape(index).substringAfter(" partial=").substringAfter(" ")
            .split(", ").filter { it.isNotBlank() }.map { it.substringBefore(" ") }
}
