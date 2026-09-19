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
 * **v29 → v30** ([MIGRATION_29_30]): the copy of each JMAP account's server address book
 * (`server_contacts`) and the `ContactCard` state it was read at (`server_contacts_state`). Two new
 * tables and nothing else: a missing step would let `fallbackToDestructiveMigration()` drop the
 * outbox and the local drafts along with the cache.
 */
class ServerContactsMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    // --- the published v15 schema (what an install carried before the key widening) --------------

    private val publishedEmailsCreate =
        "CREATE TABLE `emails` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
            "`threadId` TEXT, `subject` TEXT, `preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, " +
            "`fromEmail` TEXT, `seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, " +
            "`hasAttachment` INTEGER NOT NULL, `sortKey` INTEGER NOT NULL)"
    private val publishedBodiesCreate =
        "CREATE TABLE `email_bodies` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
            "`inlineImagesJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)"
    private val publishedSnoozedCreate =
        "CREATE TABLE `snoozed` (" +
            "`emailId` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `until` INTEGER NOT NULL)"
    private val publishedMailboxesCreate =
        "CREATE TABLE `mailboxes` (" +
            "`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT, " +
            "`parentId` TEXT, `sortOrder` INTEGER NOT NULL, `totalEmails` INTEGER NOT NULL, " +
            "`unreadEmails` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))"
    private val publishedScheduledCreate =
        "CREATE TABLE `scheduled_sends` (" +
            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `accountId` TEXT NOT NULL, " +
            "`recipients` TEXT NOT NULL, `cc` TEXT, `bcc` TEXT, `subject` TEXT NOT NULL, " +
            "`textBody` TEXT NOT NULL, `htmlBody` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
            "`inReplyTo` TEXT, `references` TEXT, `sendAtMillis` INTEGER NOT NULL, " +
            "`draftEmailId` TEXT)"
    private val publishedContactsCreate =
        "CREATE TABLE `recent_contacts` (" +
            "`email` TEXT NOT NULL PRIMARY KEY, `name` TEXT, `lastSeen` INTEGER NOT NULL)"

    /** Every table a v27 database holds rows in, so "nothing was lost" can be measured, not hoped. */
    private val allTables = listOf(
        "emails", "email_bodies", "snoozed", "mailboxes", "outbox", "scheduled_sends",
        "recent_contacts", "email_fts", "purge_snapshot", "mailbox_uidvalidity", "local_drafts",
    )

    /** The tables whose declared columns are compared before/after (`email_fts` is virtual). */
    private val shapedTables = allTables - "email_fts"

    // --- running the real, REGISTERED Migration objects ------------------------------------------

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

    /** The step Room really has for `from` → …, read out of [SternaDatabase.ALL_MIGRATIONS]. */
    private fun registeredStep(from: Int): Migration {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == from }
        assertNotNull(
            "no migration starting at v$from is registered in SternaDatabase.ALL_MIGRATIONS " +
                "(registered: " +
                SternaDatabase.ALL_MIGRATIONS.joinToString { "${it.startVersion}->${it.endVersion}" } +
                "). A step that is written but not registered never runs: Room finds no path and " +
                "fallbackToDestructiveMigration() DROPS every table, scheduled sends included.",
            step,
        )
        return step!!
    }

    /** The published v15 schema, populated, the state a real install carries before any of this. */
    private fun seedPublishedV15() {
        db.createStatement().use { st ->
            st.executeUpdate(publishedEmailsCreate)
            st.executeUpdate("CREATE INDEX `index_emails_mailboxId` ON `emails` (`mailboxId`)")
            st.executeUpdate(publishedBodiesCreate)
            st.executeUpdate("CREATE INDEX `index_email_bodies_accountId` ON `email_bodies` (`accountId`)")
            st.executeUpdate(publishedSnoozedCreate)
            st.executeUpdate(publishedMailboxesCreate)
            st.executeUpdate(OUTBOX_CREATE_SQL)
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
            st.executeUpdate(publishedScheduledCreate)
            st.executeUpdate(publishedContactsCreate)
            st.executeUpdate(EMAIL_FTS_CREATE_SQL)

            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accA', 'mb1', 'Hello', 0, 0, 0, 100)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accA', '{}', '{}', 42)")
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e1', 'accA', 99999)")
            st.executeUpdate(
                "INSERT INTO `outbox` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`attachmentsJson`, `createdAtMillis`, `notBeforeMillis`, `state`, " +
                    "`attemptCount`) VALUES ('accA', 'bob@example.org', " +
                    "'Never sent', 'see you at six', '[]', 1, 2, 'QUEUED', 2)",
            )
            // Two scheduled sends: every nullable column filled on one, left NULL on the other, so a
            // rebuilt table that drops or rewrites a column cannot pass on NULL = NULL.
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `cc`, `bcc`, `subject`, " +
                    "`textBody`, `htmlBody`, `fromName`, `fromEmail`, `inReplyTo`, `references`, " +
                    "`sendAtMillis`, `draftEmailId`) VALUES ('accA', 'bob@example.org, eve@example.org', " +
                    "'carol@example.org', 'dan@example.org', 'Later', 'the only copy of this text', " +
                    "'<p>the only copy</p>', 'Alex', 'alex@example.org', '<a@example.org>', " +
                    "'<r1@example.org> <a@example.org>', 500, 'draft-7')",
            )
            st.executeUpdate(
                "INSERT INTO `scheduled_sends` (`accountId`, `recipients`, `subject`, `textBody`, " +
                    "`sendAtMillis`) VALUES ('accB', 'zoe@example.org', 'Bare', 'body', 900)",
            )
            st.executeUpdate("INSERT INTO `mailboxes` VALUES ('accA', 'mb1', 'Inbox', 'inbox', NULL, 0, 2, 1)")
            st.executeUpdate("INSERT INTO `recent_contacts` VALUES ('bob@example.org', 'Bob', 7)")
            st.executeUpdate(
                "INSERT INTO `email_fts`(emailId, accountId, mailboxId, threadId, subject, sender, " +
                    "body, preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                    "VALUES ('e1', 'accA', 'mb1', NULL, 'Hello', 'Alex alex@example.org', '', NULL, " +
                    "NULL, 'Alex', 'alex@example.org', 0, 0, 0, 100)",
            )
        }
    }

    private fun fillTheV18AndV19Tables() {
        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO `purge_snapshot` VALUES ('p1', 'accA', 'trash', 'm1', 1, 77)")
            st.executeUpdate("INSERT INTO `mailbox_uidvalidity` VALUES ('accA', 'mb1', 77)")
        }
    }

    /** The columns the scheduled row gained after v15, filled so they are watched too. */
    private fun fillTheLaterScheduledColumns() {
        db.createStatement().use { st ->
            st.executeUpdate(
                "UPDATE `scheduled_sends` SET `requestReceipt` = 1, `draftUidValidity` = 4242 " +
                    "WHERE `subject` = 'Later'",
            )
        }
    }

    private fun fillTheV24Table() {
        db.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO `local_drafts` (`accountId`, `id`, `messageId`, `toAddresses`, " +
                    "`subject`, `textBody`, `attachmentsJson`, `bodyIsLossy`, `requestReceipt`, " +
                    "`createdAtMillis`, `updatedAtMillis`, `notBeforeMillis`, `attemptCount`, `state`) " +
                    "VALUES ('accA', 'local-draft:1', 'mid@example.org', 'bob@example.org', " +
                    "'Only here', 'never sent anywhere', '[]', 0, 0, 1, 1, 0, 0, 'PENDING')",
            )
        }
    }

    /** Runs the registered steps from v15 up to (but not including) [upTo]; returns the version reached. */
    private fun chainRegisteredFrom15(upTo: Int): Int {
        var current = 15
        SternaDatabase.ALL_MIGRATIONS
            .filter { it.startVersion >= 15 && it.startVersion < upTo }
            .sortedBy { it.startVersion }
            .forEach { step ->
                assertEquals(
                    "the registered migrations must form an unbroken chain from v15; a gap is not a " +
                        "degraded upgrade, it is a destructive fallback",
                    current,
                    step.startVersion,
                )
                step.migrate(supportDb())
                current = step.endVersion
                if (current == 19) fillTheV18AndV19Tables()
                if (current == 23) fillTheLaterScheduledColumns()
                if (current == 24) fillTheV24Table()
            }
        return current
    }

    private fun seedV29() {
        seedPublishedV15()
        assertEquals(
            "the registered chain must reach v29 before this branch's step can be measured",
            29,
            chainRegisteredFrom15(upTo = 29),
        )
    }

    private fun migrate29to30() = registeredStep(29).migrate(supportDb())

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    private fun columnsOf(table: String): Set<String> = columnShapes(table).keys

    /** `type`, `notnull`, `dflt_value` of each column, as `PRAGMA table_info` declares them. */
    private fun columnShapes(table: String): Map<String, Triple<String, Int, String?>> {
        val shapes = linkedMapOf<String, Triple<String, Int, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    shapes[rs.getString("name")] =
                        Triple(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))
                }
            }
        }
        return shapes
    }

    private fun allShapes(): Map<String, Map<String, Triple<String, Int, String?>>> =
        shapedTables.associateWith { columnShapes(it) }

    private fun namesIn(type: String): Set<String> {
        val names = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT `name` FROM `sqlite_master` WHERE `type` = '$type'").use { rs ->
                while (rs.next()) names += rs.getString("name")
            }
        }
        return names
    }

    /**
     * Every row of [table], every column of [columns], as text, in rowid order. Compared over the
     * columns that existed before the step, this is the row "word for word", not a row count.
     */
    private fun rowsOf(table: String, columns: Collection<String>): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        val select = columns.joinToString(", ") { "`$it`" }
        db.createStatement().use { st ->
            st.executeQuery("SELECT $select FROM `$table` ORDER BY rowid").use { rs ->
                while (rs.next()) rows += columns.indices.map { rs.getString(it + 1) }
            }
        }
        return rows
    }

    private fun allRows(columnsByTable: Map<String, Set<String>>): Map<String, List<List<String?>>> =
        columnsByTable.mapValues { (table, columns) -> rowsOf(table, columns) }

    private val ftsColumns = setOf(
        "emailId", "accountId", "mailboxId", "threadId", "subject", "sender", "body", "preview",
        "receivedAt", "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
    )


    private val contactsShapes = mapOf(
        "accountId" to Triple("TEXT", 1, null as String?),
        "cardId" to Triple("TEXT", 1, null),
        "name" to Triple("TEXT", 1, null),
        "email" to Triple("TEXT", 1, null),
    )
    private val stateShapes = mapOf(
        "accountId" to Triple("TEXT", 1, null as String?),
        "state" to Triple("TEXT", 1, null),
    )

    /** `PRAGMA table_info`'s `pk` column: the primary-key columns in key order. */
    private fun primaryKeyOf(table: String): List<String> {
        val keyed = mutableListOf<Pair<Int, String>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) rs.getInt("pk").takeIf { it > 0 }?.let { keyed += it to rs.getString("name") }
            }
        }
        return keyed.sortedBy { it.first }.map { it.second }
    }

    // --- (a) the two tables -------------------------------------------------------------------------

    @Test fun v29to30_createsTheAddressBookCopyAndItsState() {
        seedV29()
        assertEquals(false, "server_contacts" in namesIn("table"))

        migrate29to30()

        assertEquals(contactsShapes, columnShapes("server_contacts"))
        assertEquals(listOf("accountId", "cardId", "email"), primaryKeyOf("server_contacts"))
        assertEquals(stateShapes, columnShapes("server_contacts_state"))
        assertEquals(listOf("accountId"), primaryKeyOf("server_contacts_state"))
    }

    /** One row per address: a card with two addresses fits, the same address twice in a card does not. */
    @Test fun v29to30_keysOneRowPerAddressOfACardOfAnAccount() {
        seedV29()
        migrate29to30()

        db.createStatement().use { st ->
            st.executeUpdate("INSERT INTO server_contacts VALUES ('accA', 'c1', 'Alex', 'a@example.org')")
            st.executeUpdate("INSERT INTO server_contacts VALUES ('accA', 'c1', 'Alex', 'b@example.org')")
            st.executeUpdate("INSERT INTO server_contacts VALUES ('accB', 'c1', 'Alex', 'a@example.org')")
        }
        assertEquals(3, count("SELECT COUNT(*) FROM server_contacts"))
        val refused = runCatching {
            db.createStatement().use { it.executeUpdate("INSERT INTO server_contacts VALUES ('accA', 'c1', 'X', 'a@example.org')") }
        }
        assertEquals(true, refused.isFailure)
    }

    // --- (b) nothing else moved ---------------------------------------------------------------------

    @Test fun v29to30_addsTwoTablesAndTouchesNothingElse() {
        seedV29()
        val shapesBefore = allShapes()
        val tablesBefore = namesIn("table")
        val indexesBefore = namesIn("index")

        migrate29to30()

        assertEquals(shapesBefore, allShapes())
        assertEquals(tablesBefore + "server_contacts" + "server_contacts_state", namesIn("table"))
        assertEquals(
            "no index but the two primary keys' own",
            indexesBefore,
            namesIn("index").filterNot { it.startsWith("sqlite_autoindex_server_contacts") }.toSet(),
        )
    }

    // --- (c) every row survives, word for word ------------------------------------------------------

    @Test fun v29to30_everyRowOfEveryTableSurvivesWordForWord() {
        seedV29()
        val columnsBefore = shapedTables.associateWith { columnsOf(it) } + ("email_fts" to ftsColumns)
        val before = allRows(columnsBefore)
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it.isEmpty() }.keys.toList(),
        )

        migrate29to30()

        assertEquals(before, allRows(columnsBefore))
        assertEquals(
            "the unsent message in the outbox is still there",
            1,
            count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `textBody` = 'see you at six' AND `state` = 'QUEUED'"),
        )
    }

    // --- (d) the step has to be REGISTERED, and the version has to follow ----------------------------

    @Test fun theRegisteredMigrationsCarryAPublishedInstallToTheAddressBookCopy() {
        seedPublishedV15()

        val reached = chainRegisteredFrom15(upTo = Int.MAX_VALUE)

        assertEquals(
            "only the REGISTERED migrations ran: the chain must arrive at v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        assertEquals(
            "only the REGISTERED migrations ran: `server_contacts` must be there, otherwise Room has " +
                "no 29->30 path and drops the whole database, outbox included",
            contactsShapes,
            columnShapes("server_contacts"),
        )
        assertEquals(stateShapes, columnShapes("server_contacts_state"))
        assertEquals(1, count("SELECT COUNT(*) FROM `outbox` WHERE `subject` = 'Never sent' AND `attemptCount` = 2"))
        assertEquals(1, count("SELECT COUNT(*) FROM `local_drafts` WHERE `subject` = 'Only here'"))
    }

    @Test fun theRegisteredMigrationsReachTheDeclaredSchemaVersion() {
        val reached = SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 15 }.maxOf { it.endVersion }

        assertEquals(
            "the registered chain stops at v$reached while the database declares v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        assertEquals("this branch takes the schema to v30 (`server_contacts`)", 30, SCHEMA_VERSION)
    }

    // --- (e) the entities name the columns the step creates ------------------------------------------

    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { assertEquals("${type.simpleName}.${it.name} must be a String (TEXT NOT NULL)", String::class.java, it.type) }
            .map { it.name }.toSet()

    @Test fun theEntitiesAndTheStepAgreeOnEveryColumn() {
        assertEquals(contactsShapes.keys, fieldsOf(ServerContactEntity::class.java))
        assertEquals(stateShapes.keys, fieldsOf(ServerContactStateEntity::class.java))
    }

    // --- (f) Room opens the migrated tables: nullability and key order, as its `TableInfo` compares -----

    /** Each field of [type] and whether its constructor refuses a null there, which Room reads as `NOT NULL`. */
    private fun nonNullByField(type: Class<*>): Map<String, Boolean> {
        val fields = type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val constructor = type.declaredConstructors.singleOrNull { it.parameterTypes.toList() == fields.map { f -> f.type } }
            ?: error("No constructor of ${type.simpleName} takes exactly its backing fields, in order")
        return fields.withIndex().associate { (i, field) ->
            val args = Array<Any?>(fields.size) { "x" }
            args[i] = null
            val refused = try {
                constructor.newInstance(*args)
                false
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException
                check(cause is NullPointerException && cause.message.orEmpty().substringAfterLast("parameter ").trim() == field.name) {
                    "Constructing ${type.simpleName} with a null for '${field.name}' threw $cause, not Kotlin's check of that parameter"
                }
                true
            }
            field.name to refused
        }
    }

    private val entitySource: String by lazy {
        val relative = "src/main/kotlin/app/sterna/core/data/db/ServerContactDao.kt"
        generateSequence(java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(java.io.File(it, relative), java.io.File(it, "core/data/$relative")) }
            .firstOrNull { it.isFile }?.readText()
            ?: error("cannot find $relative")
    }

    /**
     * The primary key the `@Entity` of [entity] declares, in order: its `primaryKeys`, else its
     * `@PrimaryKey` parameters. Read as text because Room's annotations are not kept at run time.
     */
    private fun declaredPrimaryKey(entity: String, source: String = entitySource): List<String> {
        val at = source.indexOf("data class $entity(")
        check(at >= 0) { "no `data class $entity(` in ServerContactDao.kt" }
        val annotation = source.lastIndexOf("@Entity(", at)
        check(annotation >= 0) { "no @Entity above $entity" }
        Regex("""primaryKeys\s*=\s*\[([^\]]*)]""").find(source.substring(annotation, at))?.let { keys ->
            return Regex("\"(\\w+)\"").findAll(keys.groupValues[1]).map { it.groupValues[1] }.toList()
        }
        val parameters = source.substring(at, source.indexOf("\n)", at))
        return Regex("""@PrimaryKey\s+val\s+(\w+)""").findAll(parameters).map { it.groupValues[1] }.toList()
    }

    /** Where [entity] and the migrated [table] disagree on nullability or key order, each as a sentence. */
    private fun disagreements(
        entity: Class<*>,
        table: String,
        nonNull: Map<String, Boolean> = nonNullByField(entity),
        primaryKey: List<String> = declaredPrimaryKey(entity.simpleName),
    ): List<String> {
        val columns = columnShapes(table)
        val out = mutableListOf<String>()
        nonNull.forEach { (name, refusesNull) ->
            val notNull = columns[name]?.second == 1
            if (refusesNull != notNull) {
                out += "`$table`.`$name` is ${if (notNull) "NOT NULL" else "nullable"} but " +
                    "${entity.simpleName}.$name is ${if (refusesNull) "non-null" else "nullable"}"
            }
        }
        val keyed = primaryKeyOf(table)
        if (primaryKey != keyed) out += "`$table` is keyed on $keyed but ${entity.simpleName} declares $primaryKey"
        return out
    }

    @Test fun roomAcceptsTheMigratedTables() {
        seedV29()
        migrate29to30()

        assertEquals(
            "Room compares every column's nullability and primary-key position at open time and " +
                "refuses a migrated database that differs: every updated install would crash on start",
            emptyList<String>(),
            disagreements(ServerContactEntity::class.java, "server_contacts") +
                disagreements(ServerContactStateEntity::class.java, "server_contacts_state"),
        )
    }

    /** The shape `ServerContactEntity` would have with `name: String?`. */
    data class NullableNameEntity(val accountId: String, val cardId: String, val name: String?, val email: String)

    @Test fun theCheckSeesANullableFieldOverANotNullColumn() {
        seedV29()
        migrate29to30()

        assertEquals(
            listOf("`server_contacts`.`name` is NOT NULL but NullableNameEntity.name is nullable"),
            disagreements(NullableNameEntity::class.java, "server_contacts", primaryKey = listOf("accountId", "cardId", "email")),
        )
    }

    @Test fun theCheckSeesAPrimaryKeyDeclaredInAnotherOrder() {
        seedV29()
        migrate29to30()
        val swapped = "@Entity(tableName = \"server_contacts\", primaryKeys = [\"cardId\", \"accountId\", \"email\"])\n" +
            "data class ServerContactEntity(\n    val accountId: String,\n)\n"

        assertEquals(listOf("cardId", "accountId", "email"), declaredPrimaryKey("ServerContactEntity", swapped))
        assertEquals(
            listOf("`server_contacts` is keyed on [accountId, cardId, email] but ServerContactEntity declares [cardId, accountId, email]"),
            disagreements(ServerContactEntity::class.java, "server_contacts", primaryKey = declaredPrimaryKey("ServerContactEntity", swapped)),
        )
        assertEquals(listOf("accountId"), declaredPrimaryKey("ServerContactStateEntity"))
    }
}
