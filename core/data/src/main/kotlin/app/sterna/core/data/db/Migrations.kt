package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL that creates the `outbox` table. A constant so the 9→10 migration and a JVM unit test
 * execute the exact same statement.
 *
 * Rule for every migration in this file: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so a step that is written but NOT registered in
 * [SternaDatabase.ALL_MIGRATIONS] makes Room drop every table — `outbox`, `scheduled_sends` and
 * `local_drafts` included, i.e. mail the user wrote that never left the phone.
 */
const val OUTBOX_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `outbox` (" +
        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
        "`accountId` TEXT NOT NULL, " +
        "`recipients` TEXT NOT NULL, " +
        "`cc` TEXT, " +
        "`bcc` TEXT, " +
        "`subject` TEXT NOT NULL, " +
        "`textBody` TEXT NOT NULL, " +
        "`htmlBody` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`inReplyTo` TEXT, " +
        "`references` TEXT, " +
        "`attachmentsJson` TEXT NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`notBeforeMillis` INTEGER NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`attemptCount` INTEGER NOT NULL, " +
        "`lastError` TEXT, " +
        "`lastAttemptMillis` INTEGER)"

/**
 * Additive 9→10: add the persistent `outbox` table. The outbox holds unsent mail, so it must never
 * be destroyed on upgrade — unlike the disposable cache.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(OUTBOX_CREATE_SQL)
    }
}

/** Additive 11→12: OpenPGP columns on `outbox` (mode + path of the pre-built PGP/MIME entity). */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
    }
}

/**
 * 12→13: `mailboxes` gains `accountId` (composite key). The table is a disposable server mirror —
 * rebuild it in place instead of letting the destructive fallback wipe the whole DB, which would
 * destroy queued outbox mail.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `mailboxes`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailboxes` (" +
                "`accountId` TEXT NOT NULL, " +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`role` TEXT, " +
                "`parentId` TEXT, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`totalEmails` INTEGER NOT NULL, " +
                "`unreadEmails` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `id`))",
        )
    }
}

/**
 * The `emails` table with the composite `(accountId, id)` primary key. A constant so the 15→16
 * migration and a JVM unit test build the exact table that migration must produce (column set,
 * nullability, PK order — column order is irrelevant to Room's validation, PK column order is not).
 *
 * The **v16** shape, deliberately frozen: [MIGRATION_16_17] adds `recipientsJson` on top with an
 * `ALTER TABLE`, so folding later columns in here makes the ALTER fail on a duplicate column for
 * anyone coming from v15.
 */
const val EMAILS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `emails` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, " +
        "`subject` TEXT, " +
        "`preview` TEXT, " +
        "`receivedAt` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, " +
        "`flagged` INTEGER NOT NULL, " +
        "`hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The index Room derives from `@Index("mailboxId")` on `emails` (name: `index_<table>_<column>`). */
const val EMAILS_MAILBOX_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_emails_mailboxId` ON `emails` (`mailboxId`)"

/** The `email_bodies` table with the composite key ([EmailBodyEntity]); shared with the JVM test. */
const val EMAIL_BODIES_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `email_bodies` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`bodyJson` TEXT NOT NULL, " +
        "`inlineImagesJson` TEXT NOT NULL, " +
        "`fetchedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The `snoozed` table with the composite key ([SnoozedEntity]); shared with the JVM test. */
const val SNOOZED_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `snoozed` (" +
        "`emailId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`until` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `emailId`))"

/** Ordered column lists of the three rebuilt tables (identical in v13 and v14 — only the PK changes). */
const val EMAILS_COLUMNS: String =
    "`id`, `accountId`, `mailboxId`, `threadId`, `subject`, `preview`, `receivedAt`, " +
        "`fromName`, `fromEmail`, `seen`, `flagged`, `hasAttachment`, `sortKey`"
const val EMAIL_BODIES_COLUMNS: String = "`id`, `accountId`, `bodyJson`, `inlineImagesJson`, `fetchedAt`"
const val SNOOZED_COLUMNS: String = "`emailId`, `accountId`, `until`"

/**
 * Rebuild [table] under its new composite-key DDL [createSql], copying every row by explicit
 * [columns], never positionally. Old single-column keys were globally unique, so no collision is
 * possible on the way in.
 */
private fun SupportSQLiteDatabase.rebuildTable(table: String, createSql: String, columns: String) {
    execSQL(createSql.replace("`$table`", "`${table}_new`"))
    execSQL("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
}

/**
 * Additive 13→14 (released as 1.3.10): `outbox` and `scheduled_sends` gain `draftEmailId` — the
 * server draft an edited message came from, destroyed once the send succeeds (#63). This is the
 * RELEASED v14 schema; the composite-key change (#31) is layered on top as [MIGRATION_15_16].
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftEmailId` TEXT")
    }
}

/**
 * Exact `CREATE VIRTUAL TABLE` Room generates for [EmailFtsEntity] with the `remove_diacritics=1`
 * tokenizer, copied verbatim from Room's own `createAllTables` output. Any drift from that form
 * throws "Migration didn't properly handle email_fts" at startup.
 */
const val EMAIL_FTS_CREATE_SQL: String =
    "CREATE VIRTUAL TABLE IF NOT EXISTS `email_fts` USING FTS4(" +
        "`emailId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, `subject` TEXT NOT NULL, `sender` TEXT NOT NULL, `body` TEXT NOT NULL, " +
        "`preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, `hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, tokenize=unicode61 `remove_diacritics=1`, " +
        "notindexed=`emailId`, notindexed=`accountId`, notindexed=`mailboxId`, " +
        "notindexed=`threadId`, notindexed=`preview`, notindexed=`receivedAt`, " +
        "notindexed=`fromName`, notindexed=`fromEmail`, notindexed=`seen`, notindexed=`flagged`, " +
        "notindexed=`hasAttachment`, notindexed=`sortKey`)"

/**
 * 14→15: rebuild the `email_fts` search index with `remove_diacritics=1`. The old table used
 * `remove_diacritics=2`, which unicode61 only understands on SQLite >= 3.27, so devices on
 * Android <= 9 crashed on first DB access (#71).
 *
 * The tokenizer is baked into the CREATE statement, so it cannot be ALTERed: drop and recreate.
 * Dropping an FTS4 table also drops its shadow tables. The index is a disposable derivative of
 * `emails`, never user data. Repopulated from the cached rows with [EmailFtsDao.insertFromEmails]'s
 * query (headers only); crawled-only rows are re-indexed by the background crawl.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `email_fts`")
        db.execSQL(EMAIL_FTS_CREATE_SQL)
        db.execSQL(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
                "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
                "FROM emails",
        )
    }
}

/**
 * 15→16: widen the primary keys of `emails`, `email_bodies` and `snoozed` from the email id alone
 * to the composite `(accountId, id)` — a JMAP email id is unique only within its account (RFC 8620
 * §1.6.2), so two accounts under one login (#31) can each hold a message with the same id.
 *
 * Non-destructive: every row is copied verbatim, and the v15 tables already carry a populated
 * `accountId`, so the rebuild only promotes that value into the key. `email_fts` is left untouched
 * — a standalone FTS4 table with no `content=` link, so rebuilding `emails` neither drops nor
 * invalidates it.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.rebuildTable("emails", EMAILS_CREATE_SQL, EMAILS_COLUMNS)
        db.execSQL(EMAILS_MAILBOX_INDEX_SQL)
        // The old accountId index is subsumed by the new key's prefix.
        db.execSQL("DROP INDEX IF EXISTS `index_email_bodies_accountId`")
        db.rebuildTable("email_bodies", EMAIL_BODIES_CREATE_SQL, EMAIL_BODIES_COLUMNS)
        db.rebuildTable("snoozed", SNOOZED_CREATE_SQL, SNOOZED_COLUMNS)
    }
}

/**
 * Additive 16→17: `emails` gains `recipientsJson` ([EmailEntity.recipientsJson]) — Sent/Drafts rows
 * show who the mail went to (#59), and from a cold cache the row otherwise fell back to the sender
 * until a round-trip corrected it (#63).
 *
 * Layered on top rather than folded into a released migration, here and in every step below:
 * rewriting one in place fails Room's identity check on the next launch. Nullable with no default
 * and no backfill, so an old row reads back empty and gains the column at its next sync.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `recipientsJson` TEXT")
    }
}

/** The `purge_snapshot` table ([PurgeSnapshotEntity]); shared with the JVM test. */
const val PURGE_SNAPSHOT_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `purge_snapshot` (" +
        "`purgeId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`emailId` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`purgeId`, `accountId`, `emailId`))"

/**
 * Additive 17→18: the `purge_snapshot` table (#99). "Empty trash" records the exact messages the
 * user confirmed destroying, so the held-back purge destroys only those instead of re-reading the
 * folder when it runs. The table starts empty, which is the correct state: a purge with no snapshot
 * destroys nothing.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_CREATE_SQL)
    }
}

/**
 * The UIDVALIDITY a snapshot was taken under ([PurgeSnapshotEntity.uidValidity]). NULLABLE, and the
 * null is load-bearing: a snapshot written by the previous version reads as "cannot be verified"
 * and destroys NOTHING.
 */
const val PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL: String =
    "ALTER TABLE `purge_snapshot` ADD COLUMN `uidValidity` INTEGER"

/** The `mailbox_uidvalidity` table ([MailboxUidValidityEntity]); shared with the JVM test. */
const val MAILBOX_UIDVALIDITY_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `mailbox_uidvalidity` (" +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`uidValidity` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `mailboxId`))"

/**
 * Additive 18→19: remember which numbering an IMAP folder's UIDs belong to (#99). A server that
 * renumbers a folder bumps its UIDVALIDITY, and every UID cached for it then names a different
 * message. Two records, two lifetimes: one on an Empty-trash snapshot, so a held-back purge can
 * refuse to destroy against a renumbered folder; one per folder, so the body cache — the one store
 * no refresh prunes — can be dropped when the numbering moves. Both start "not known".
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL)
        db.execSQL(MAILBOX_UIDVALIDITY_CREATE_SQL)
    }
}

/**
 * Additive 19→20: `emails` gains `replyToJson` ([EmailEntity.replyToJson]). A sender that sets
 * `Reply-To` is naming another mailbox to answer, and answering the `From` instead posts the reply
 * to an address the sender set aside — while looking sent. It has to survive in the cache so an
 * offline reply and the notification's quick reply see it too.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `replyToJson` TEXT")
    }
}

/**
 * Additive 20→21: `emails` gains `ccJson` and `bccJson`. The row is what a reopened draft and a
 * "reply all" are rebuilt from, so without the copies a reopened draft came back with Cc and Bcc
 * emptied and "reply all" silently left out everyone in copy.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `ccJson` TEXT")
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `bccJson` TEXT")
    }
}

/**
 * Additive 21→22: `outbox` and `scheduled_sends` each gain `requestReceipt`.
 *
 * BOTH tables, not one: a scheduled send only becomes an outbox row when its worker fires, so a
 * column on one table alone drops the request in the handover and the mail goes out asking nothing.
 *
 * `INTEGER NOT NULL DEFAULT 0` because the entity field is a non-null `Boolean` and SQLite requires
 * a default to add a NOT NULL column to a populated table. Room compares defaults at open time only
 * when the ENTITY declares one via `@ColumnInfo(defaultValue)` (b/136515305).
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive 22→23: `outbox` and `scheduled_sends` each gain `draftUidValidity`, the IMAP numbering
 * the composer read `draftEmailId` under.
 *
 * The draft is destroyed at DELIVERY time, and a `UID EXPUNGE` only names a message inside one
 * UIDVALIDITY: a number looked up then is the one a background sync recorded AFTER a renumbering,
 * so compared with itself it reads "same" and the expunge lands on whatever now holds those UIDs
 * (#99). BOTH tables, as in [MIGRATION_21_22]. No backfill: nobody knows which numbering the
 * queued rows were enqueued under.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftUidValidity` INTEGER")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftUidValidity` INTEGER")
    }
}

/**
 * The `local_drafts` table ([LocalDraftEntity]) — drafts written on the phone and not yet on the
 * server (#95). One constant, like [OUTBOX_CREATE_SQL], so the 23→24 migration and the JVM test
 * build the same table.
 *
 * The table is created empty, so no `DEFAULT` is needed and none is written, which keeps this
 * shape identical to Room's own `createAllTables`. The types are not decoration: Room compares
 * affinity and nullability at open time, so a column of the right name and the wrong shape fails
 * `validateMigration` on EVERY launch and the only way out is a reinstall.
 */
const val LOCAL_DRAFTS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `local_drafts` (" +
        "`accountId` TEXT NOT NULL, " +
        "`id` TEXT NOT NULL, " +
        "`messageId` TEXT NOT NULL, " +
        "`toAddresses` TEXT NOT NULL, " +
        "`cc` TEXT, " +
        "`bcc` TEXT, " +
        "`subject` TEXT NOT NULL, " +
        "`textBody` TEXT NOT NULL, " +
        "`htmlBody` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`inReplyTo` TEXT, " +
        "`references` TEXT, " +
        "`attachmentsJson` TEXT NOT NULL, " +
        "`replacesEmailId` TEXT, " +
        "`replacesUidValidity` INTEGER, " +
        "`bodyIsLossy` INTEGER NOT NULL, " +
        "`requestReceipt` INTEGER NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`updatedAtMillis` INTEGER NOT NULL, " +
        "`notBeforeMillis` INTEGER NOT NULL, " +
        "`attemptCount` INTEGER NOT NULL, " +
        "`lastError` TEXT, " +
        "`lastAttemptMillis` INTEGER, " +
        "`state` TEXT NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/**
 * Additive 23→24: the `local_drafts` table (#95), where a draft saved with no network waits for one
 * instead of dying with the process that held it. The table starts empty, which is correct: an
 * install coming from v23 has no draft that was ever kept locally.
 *
 * Why a table rather than a flagged row in `emails`: four cache-pruning paths delete a row
 * `emails` holds that the server does not know about, and the only shield any of them honours is a
 * 45-second recently-mutated window no draft path arms. See [LocalDraftEntity].
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(LOCAL_DRAFTS_CREATE_SQL)
    }
}

/**
 * Additive 24→25: `emails` gains `uidValidity`, the IMAP numbering each cached row was READ under
 * ([EmailEntity.uidValidity], #99).
 *
 * The numbering was tracked per FOLDER while the ids a destroy names come from ROWS that carried
 * none. A background pass that meets a renumbering records the NEW number and leaves the old rows
 * in place — nothing prunes them on IMAP — so a destroy froze the new number, compared it with
 * itself, and `UID EXPUNGE` went out on UIDs that now name live messages. No backfill: writing
 * the folder's current number into old rows would license precisely that expunge.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `uidValidity` INTEGER")
    }
}

/**
 * Additive 25→26: `mailboxes` gains `isSubscribed`, what the server reports about the folder's
 * subscription ([MailboxEntity.isSubscribed], #174) — IMAP's `LSUB`, JMAP's `isSubscribed`.
 *
 * `INTEGER NOT NULL DEFAULT 1`, and the `1` is the decision, not the syntax: a folder cached
 * before v26 was cached by a build that never asked the server, so what it holds is UNKNOWN, and
 * the unknown never hides anything. `DEFAULT 0` would take every folder of every existing install
 * out of the drawer at the first launch after the update, on no evidence at all.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `mailboxes` ADD COLUMN `isSubscribed` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Additive 26→27: `emails` gains the composite index `(accountId, mailboxId, sortKey)` — the three
 * columns a list query reads a folder by, in the order it reads them: `(accountId, mailboxId)` is
 * the scope (`folderScopeSql` selects on pairs, never a bare mailbox id — #121), `sortKey` the
 * order and the conversation view's representative join.
 *
 * What it does NOT close: `conversationSql`'s `threadTotal` sub-query carries no folder scope, so
 * it still groups the whole table. The NAME is what Room derives from the entity's `@Index` on a
 * fresh install; a different one makes `validateMigration` refuse to open the database.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_emails_accountId_mailboxId_sortKey` " +
                "ON `emails` (`accountId`, `mailboxId`, `sortKey`)",
        )
    }
}
