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
 * **v27 → v28**: `scheduled_sends` gains `failedAttempts`, the delivery failures stored on the row
 * itself ([ScheduledSendEntity.failedAttempts]). The worker decides to give up on this count, and
 * giving up destroys the row, which is the only copy of the message.
 */
class ScheduledSendFailuresMigrationSqlTest {
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

    private fun seedV27() {
        seedPublishedV15()
        assertEquals(
            "the registered chain must reach v27 before this branch's step can be measured",
            27,
            chainRegisteredFrom15(upTo = 27),
        )
    }

    private fun migrate27to28() = registeredStep(27).migrate(supportDb())

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

    // --- (a) the column -----------------------------------------------------------------------------

    @Test fun v27to28_addsTheFailureCounterToTheScheduledSends() {
        seedV27()
        assertEquals(
            "the counter must not exist before its step runs, otherwise this measures the seed",
            false,
            "failedAttempts" in columnsOf("scheduled_sends"),
        )

        migrate27to28()

        assertTrue(
            "`scheduled_sends.failedAttempts` is missing: the worker has no count of its own to " +
                "read and gives up on WorkManager's, which also counts the runs that attempted nothing",
            "failedAttempts" in columnsOf("scheduled_sends"),
        )
    }

    /**
     * Not NULL because the entity field is a non-null Int, and SQLite then requires a default to
     * add it to a populated table. 0 because a row scheduled before this step has failed nothing
     * that was counted: any other default spends retries it never had.
     */
    @Test fun v27to28_declaresTheCounterAsANotNullIntegerDefaultingToZero() {
        seedV27()
        migrate27to28()

        assertEquals(Triple("INTEGER", 1, "0"), columnShapes("scheduled_sends")["failedAttempts"])
        assertEquals(
            "a row scheduled before the step must read back as zero failures, never NULL",
            listOf(listOf("Later", "0"), listOf("Bare", "0")),
            rowsOf("scheduled_sends", listOf("subject", "failedAttempts")),
        )
    }

    // --- (b) nothing else moved ---------------------------------------------------------------------

    @Test fun v27to28_addsNothingElseAnywhere() {
        seedV27()
        val shapesBefore = allShapes()
        val tablesBefore = namesIn("table")
        val indexesBefore = namesIn("index")

        migrate27to28()

        val expected = shapesBefore.toMutableMap()
        expected["scheduled_sends"] =
            shapesBefore.getValue("scheduled_sends") + ("failedAttempts" to Triple("INTEGER", 1, "0"))
        assertEquals(
            "the step adds exactly one column, to `scheduled_sends`: the outbox already counts its " +
                "own `attemptCount`, and no other table has a stake in this",
            expected,
            allShapes(),
        )
        assertEquals("no table is created or dropped", tablesBefore, namesIn("table"))
        assertEquals("no index is created or dropped", indexesBefore, namesIn("index"))
    }

    // --- (c) every row survives, word for word ------------------------------------------------------

    @Test fun v27to28_everyRowOfEveryTableSurvivesWordForWord() {
        seedV27()
        val columnsBefore = shapedTables.associateWith { columnsOf(it) } + ("email_fts" to ftsColumns)
        val before = allRows(columnsBefore)
        assertEquals(
            "the seed itself must hold a row everywhere, otherwise this proves nothing",
            emptyList<String>(),
            before.filterValues { it.isEmpty() }.keys.toList(),
        )
        assertEquals(
            "the filled scheduled row must carry its later columns, otherwise they are not watched",
            listOf(listOf("1", "4242")),
            rowsOf("scheduled_sends", listOf("requestReceipt", "draftUidValidity")).take(1),
        )

        migrate27to28()

        assertEquals(before, allRows(columnsBefore))
        assertEquals(1, count("SELECT COUNT(*) FROM `scheduled_sends` WHERE `id` = 1 AND `subject` = 'Later'"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_fts` WHERE `email_fts` MATCH 'Hell*'"))
    }

    // --- (d) the step has to be REGISTERED, and the version has to follow ----------------------------

    @Test fun theRegisteredMigrationsCarryAPublishedInstallToTheFailureCounter() {
        seedPublishedV15()

        val reached = chainRegisteredFrom15(upTo = Int.MAX_VALUE)

        assertEquals(
            "only the REGISTERED migrations ran: the chain must arrive at v$SCHEMA_VERSION",
            SCHEMA_VERSION,
            reached,
        )
        assertEquals(
            "only the REGISTERED migrations ran: `scheduled_sends.failedAttempts` must be there, " +
                "otherwise Room has no 27->28 path and drops the whole database, scheduled mail included",
            Triple("INTEGER", 1, "0"),
            columnShapes("scheduled_sends")["failedAttempts"],
        )
        assertEquals(
            listOf(listOf("Later", "the only copy of this text", "0"), listOf("Bare", "body", "0")),
            rowsOf("scheduled_sends", listOf("subject", "textBody", "failedAttempts")),
        )
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
        assertEquals(
            "the schema now declares v29 (`pending_expunge`)",
            29,
            SCHEMA_VERSION,
        )
    }
}
