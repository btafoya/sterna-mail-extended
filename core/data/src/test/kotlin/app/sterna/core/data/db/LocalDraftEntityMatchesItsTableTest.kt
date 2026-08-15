package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.sql.DriverManager

/**
 * [LocalDraftEntity] and [LOCAL_DRAFTS_CREATE_SQL], each read from itself and compared — the one
 * check that fails when the two drift apart.
 *
 * ⛔ The hole this closes. Renaming a field of the entity without touching the CREATE compiles, and
 * left every other test green: they all read `PRAGMA table_info` of a table built by the CONSTANT,
 * so they check the constant against a copy of itself. Nothing in this repo runs Room's own
 * `TableInfo`/`validateMigration` — there is no `androidTest`, no Robolectric, and `exportSchema` is
 * off — so the first execution of that comparison happens on a user's phone, at open time, as
 * `IllegalStateException: Migration didn't properly handle local_drafts` on EVERY launch. Only
 * installs coming up from v22 are hit; a fresh install builds its table from the entity and is
 * perfectly green, which is why no bench pass can catch it. The user's only way out is clearing the
 * app's data, which takes the outbox with it.
 *
 * ⛔ So this states no column list of its own. It EXECUTES both sides: it creates the real table and
 * reads back what SQLite made of it, it reads the entity's real constructor, and it discovers each
 * parameter's nullability by CALLING that constructor with a null and seeing whether Kotlin's own
 * `checkNotNullParameter` refuses it. A third hand-written copy of the schema would just be a third
 * thing to forget.
 *
 * What it compares, in both directions: name for name (a column with no field and a field with no
 * column both fail), affinity for affinity, nullable for nullable.
 *
 * ⚠ What it does NOT pin: the primary key and its column order (`androidx.room.Entity` has BINARY
 * retention, so the annotation is gone at runtime — `LocalDraftsMigrationSqlTest` pins that on the
 * SQL side), the column ORDER, and Kotlin default values (which are not column defaults; the table
 * deliberately declares none).
 */
class LocalDraftEntityMatchesItsTableTest {

    /** A column as both sides must agree on it — the two things Room's `TableInfo.Column` compares. */
    private data class Shape(val affinity: String, val notNull: Boolean)

    // --- what the shipped CREATE actually builds --------------------------------------------------

    private fun tableShapes(): Map<String, Shape> {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { it.executeUpdate(LOCAL_DRAFTS_CREATE_SQL) }
            db.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`local_drafts`)").use { rs ->
                    val out = linkedMapOf<String, Shape>()
                    while (rs.next()) {
                        out[rs.getString("name")] = Shape(rs.getString("type"), rs.getInt("notnull") == 1)
                    }
                    check(out.isNotEmpty()) { "LOCAL_DRAFTS_CREATE_SQL built a table with no column" }
                    return out
                }
            }
        }
    }

    // --- what the shipped entity actually declares ------------------------------------------------

    /**
     * The entity's own constructor, found by matching its parameter types against the backing
     * fields — which is also what lets a parameter be named: Kotlin emits no parameter names in the
     * bytecode (that needs `-java-parameters`), so the names come from the fields, and this match
     * is what says the two lists line up. A data class also carries a synthetic constructor with
     * the defaults mask, which does not match and is therefore never picked.
     */
    private fun primaryConstructor(fieldTypes: List<Class<*>>): Constructor<*> =
        LocalDraftEntity::class.java.declaredConstructors.singleOrNull {
            it.parameterTypes.toList() == fieldTypes
        } ?: error(
            "No constructor of LocalDraftEntity takes exactly its backing fields, in order " +
                "($fieldTypes). This test names parameters through the fields, and cannot line " +
                "the two up any more — teach it the new shape rather than deleting it.",
        )

    /**
     * A usable value for [type], so the constructor can be called for real. Anything unknown fails
     * loudly: a field of a type nobody thought about must stop this test, not slip past it.
     */
    private fun sample(type: Class<*>): Any = when {
        type.isEnum -> type.enumConstants.first()
        else -> when (type.name) {
            "java.lang.String" -> "x"
            "long", "java.lang.Long" -> 1L
            "int", "java.lang.Integer" -> 1
            "boolean", "java.lang.Boolean" -> false
            else -> error(
                "LocalDraftEntity now has a field of type ${type.name}, which this test cannot " +
                    "build a value for — and cannot therefore say what column shape it needs.",
            )
        }
    }

    /** The SQLite affinity a column of this Kotlin type must be declared with. */
    private fun affinityOf(type: Class<*>): String = when {
        type.isEnum -> "TEXT" // Room stores an enum by its name.
        type.name == "java.lang.String" -> "TEXT"
        else -> "INTEGER"
    }

    /**
     * The entity's columns as the entity itself defines them: names from the backing fields,
     * affinity from the type, and nullability EXECUTED — a primitive parameter cannot hold null at
     * all, and for a reference one we hand the constructor a null and watch what it does.
     */
    private fun entityShapes(): Map<String, Shape> {
        val fields = LocalDraftEntity::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val constructor = primaryConstructor(fields.map { it.type })
        val types = constructor.parameterTypes
        val good = Array<Any?>(types.size) { sample(types[it]) }

        val out = linkedMapOf<String, Shape>()
        var refused = 0
        types.forEachIndexed { i, type ->
            val name = fields[i].name
            val notNull = if (type.isPrimitive) true else refusesNull(constructor, good, i, name).also { if (it) refused++ }
            out[name] = Shape(affinityOf(type), notNull)
        }
        // ⛔ If nothing ever refused a null, the probe is inoperative (parameter assertions compiled
        // out) and every reference column would be reported nullable — a green that means nothing.
        assertTrue(
            "Not one reference parameter of LocalDraftEntity refused a null, so Kotlin's parameter " +
                "assertions are not in this build and nullability cannot be read by executing the " +
                "constructor. This test would report every String column as nullable.",
            refused > 0,
        )
        return out
    }

    /**
     * Whether the constructor rejects a null in position [i] — i.e. whether that parameter is
     * non-null, which is what makes the column `NOT NULL`.
     *
     * The rejection comes from Kotlin's own `Intrinsics.checkNotNullParameter`, and its message
     * names the parameter. That name is checked against the field we lined up with this position:
     * a mismatch means the fields and the parameters are not in the same order, and every verdict
     * below it would be about the wrong column.
     */
    private fun refusesNull(constructor: Constructor<*>, good: Array<Any?>, i: Int, name: String): Boolean {
        val args = good.copyOf()
        args[i] = null
        return try {
            constructor.newInstance(*args)
            false
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            check(cause is NullPointerException) {
                "Constructing LocalDraftEntity with a null in position $i ('$name') threw $cause, " +
                    "which is not the null check this reads nullability from."
            }
            val blamed = cause.message.orEmpty().substringAfterLast("parameter ").trim()
            assertEquals(
                "Kotlin blamed parameter '$blamed' for the null put in position $i, where the " +
                    "backing fields say '$name' lives. The fields and the constructor parameters " +
                    "are not in the same order, so this test would compare the wrong columns.",
                name,
                blamed,
            )
            true
        }
    }

    // --- the comparison ---------------------------------------------------------------------------

    @Test fun `every field of the entity has its column, and every column has its field`() {
        val entity = entityShapes()
        val table = tableShapes()

        assertEquals(
            "Fields of LocalDraftEntity that LOCAL_DRAFTS_CREATE_SQL creates no column for. Room " +
                "would fail validateMigration on every launch, for every install coming from v22 " +
                "— a fresh install is green, so no bench pass can find this.",
            emptySet<String>(),
            entity.keys - table.keys,
        )
        assertEquals(
            "Columns LOCAL_DRAFTS_CREATE_SQL creates that no field of LocalDraftEntity claims. " +
                "Room's TableInfo compares the whole table, so a leftover column fails the open " +
                "just as an absent one does.",
            emptySet<String>(),
            table.keys - entity.keys,
        )
    }

    @Test fun `every column is declared with the affinity and the nullability its field needs`() {
        val entity = entityShapes()
        val table = tableShapes()

        entity.forEach { (name, shape) ->
            val declared = table[name] ?: return@forEach // the other test names the missing ones
            assertEquals("`$name` is declared with the wrong SQLite affinity", shape.affinity, declared.affinity)
            assertEquals(
                "`$name` is ${if (shape.notNull) "non-null in the entity" else "nullable in the entity"} " +
                    "and the column says otherwise — Room compares nullability at open time",
                shape.notNull,
                declared.notNull,
            )
        }
    }
}
