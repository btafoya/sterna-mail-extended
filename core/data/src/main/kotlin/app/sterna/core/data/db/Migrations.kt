package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL that creates the `outbox` table. Kept as a constant so the 9→10 migration and a
 * plain JVM unit test execute the exact same statement.
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
 * Additive 9→10: add the persistent `outbox` table without touching any existing table.
 * The outbox holds unsent mail (user data), so it must never be destroyed on upgrade —
 * unlike the disposable cache, which can fall back to a destructive rebuild.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(OUTBOX_CREATE_SQL)
    }
}

/**
 * Additive 11→12: OpenPGP columns on `outbox` (mode + path of the pre-built
 * PGP/MIME entity file). Outbox rows are user data — never rebuilt destructively.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
    }
}

/**
 * 12→13: `mailboxes` gains `accountId` (composite key), so every account keeps its own
 * folder rows/counters. The table is a disposable server mirror — rebuild it in place
 * (repopulated on the next refresh) instead of letting the destructive fallback wipe the
 * whole DB, which would destroy queued outbox mail.
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
 * SQL that creates the `emails` table with the composite `(accountId, id)` primary key. Kept as a
 * constant so the 15→16 migration and a plain JVM unit test build the exact table that migration
 * must produce (column set, nullability and PK order; column order is irrelevant to Room's
 * validation, the PK column order is not).
 *
 * This is the **v16** shape, deliberately frozen: [MIGRATION_16_17] adds `recipientsJson` on top
 * with an `ALTER TABLE`, so folding that column in here would make the ALTER fail on a duplicate
 * column for anyone coming from v15. The table Room validates at open time is this plus the 16→17
 * column.
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
 * Rebuild [table] under its new composite-key DDL [createSql], copying every row over by explicit
 * [columns] (never positionally). Old single-column keys were globally unique, so no collision is
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
 * server draft an edited message came from, destroyed once the send succeeds so no duplicate
 * lingers in Drafts (#63). Both tables hold unsent user mail — never rebuilt destructively.
 *
 * This is the RELEASED v14 schema. The multi-account composite-key change (issue #31) is layered
 * on top as [MIGRATION_15_16], not folded in here.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftEmailId` TEXT")
    }
}

/**
 * Exact `CREATE VIRTUAL TABLE` Room generates for [EmailFtsEntity] with the
 * `remove_diacritics=1` tokenizer (copied verbatim from Room's own `createAllTables`
 * output). The 14→15 migration recreates the table with this string so Room's startup
 * identity check (`FtsTableInfo`) matches; any drift from Room's generated form would throw
 * "Migration didn't properly handle email_fts".
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
 * 14→15: rebuild the `email_fts` search index with `remove_diacritics=1`.
 *
 * The old table was created with `remove_diacritics=2`, which the unicode61 tokenizer only
 * understands on SQLite >= 3.27 (2019). Devices on Android <= 9 ship an older SQLite that
 * rejects the argument, so Room's `CREATE VIRTUAL TABLE` failed and the app crashed on first
 * DB access at startup (#71). `remove_diacritics=1` folds the common accents we need and is
 * supported everywhere.
 *
 * The tokenizer is baked into the table's CREATE statement, so we cannot ALTER it: drop and
 * recreate. `email_fts` is an FTS4 virtual table — dropping it also drops its shadow tables
 * (`email_fts_content/_segments/_segdir/_docsize/_stat`). The index is a disposable derivative
 * of `emails` (and the background crawl), never user data, so rebuilding it is safe; a
 * destructive fallback, by contrast, would also wipe the outbox (queued unsent mail).
 *
 * Repopulate from the cached `emails` rows so search is not empty right after the upgrade —
 * the exact query [EmailFtsDao.insertFromEmails] uses (headers only, `body` empty; the full
 * mailbox is re-covered by the background index crawl). Crawled-only rows that were not in the
 * display cache are lost on drop and re-indexed by that crawl.
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
 * to the composite `(accountId, id)` — a JMAP email id is unique only within its JMAP account
 * (RFC 8620 §1.6.2), so two accounts under one login (issue #31) can each hold a message with the
 * same id; a single-column key let one account's sync clobber (or serve back) the other's row.
 *
 * Layered on top of the RELEASED v15 schema (1.3.11's FTS rebuild), not folded into it: real
 * installs are on v15, and that is the upgrade path every user takes.
 *
 * Non-destructive: every existing row is copied over verbatim. The v15 tables already carry a
 * non-null, populated `accountId` column (present since before 1.3.10) — the single pre-multi-
 * account login wrote its own accountId into every cache row — so the rebuild simply promotes
 * that existing value into the composite key; no backfill of a missing column is needed. Old
 * single-column ids were globally unique, so no collision is possible on the way in. `snoozed`
 * is user data, so preserving it is mandatory; the caches merely avoid a pointless re-download.
 *
 * `email_fts` is left untouched: it is a standalone FTS4 table (no `content=` link to `emails`,
 * no triggers), so rebuilding `emails` under it neither drops nor invalidates the search index.
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
 * Additive 16→17: `emails` gains `recipientsJson`, the message's `To:` addresses
 * ([EmailRecipients], see [EmailEntity.recipientsJson]).
 *
 * Sent/Drafts rows show who the mail went TO instead of its sender (#59), but the recipients only
 * ever lived in a process-lifetime memo — so from a cold cache the row fell back to the sender and
 * was corrected a network round-trip later, which is the flicker #63 reported. Persisting them
 * makes the row right from the first frame, offline included.
 *
 * A plain `ALTER TABLE … ADD COLUMN`, layered on top of [MIGRATION_15_16] rather than folded into
 * it: v16 is already carried by test installs (and covered by `EmailsMigrationSqlTest` from a real
 * v15 schema), and rewriting a released migration in place would fail Room's identity check on the
 * next launch. Nullable with no default, so every existing row keeps its data and simply reads back
 * as "no recipients" — the pre-v17 behaviour. No backfill: the addresses are not held locally, so
 * an old row gains them at its next sync.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `recipientsJson` TEXT")
    }
}

/**
 * SQL that creates the `purge_snapshot` table ([PurgeSnapshotEntity]). Kept as a constant so the
 * 17→18 migration and a plain JVM unit test build the exact same table.
 */
const val PURGE_SNAPSHOT_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `purge_snapshot` (" +
        "`purgeId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`emailId` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`purgeId`, `accountId`, `emailId`))"

/**
 * Additive 17→18: the `purge_snapshot` table (Codeberg #99). An "Empty trash" now records the
 * exact messages the user confirmed destroying and the held-back purge destroys only those,
 * instead of re-reading the folder when it finally runs and taking along whatever arrived
 * meanwhile.
 *
 * Purely additive — no existing table is touched, so nothing can be lost on upgrade. The table
 * starts empty, which is the correct state: a purge confirmed on the previous version has no
 * snapshot, and a purge with no snapshot destroys nothing.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_CREATE_SQL)
    }
}

/**
 * The UIDVALIDITY a snapshot was taken under ([PurgeSnapshotEntity.uidValidity]). NULLABLE, and
 * the null is load-bearing: a snapshot written by the previous version carries no value, which
 * reads as "cannot be verified" and destroys NOTHING — the same choice `MessageDestroyWorker`
 * already makes for a purge enqueued before it knew about snapshots.
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
 * Additive 18→19: remember which numbering an IMAP folder's UIDs belong to (Codeberg #99).
 *
 * A server that renumbers a folder (a migration, a rebuilt index, a restore) bumps its
 * UIDVALIDITY, and every UID cached for it then names a different message — or nothing.
 * Two records, two lifetimes: one carried by an Empty-trash snapshot, so a held-back purge can
 * refuse to destroy against a folder that has been renumbered under it; one per folder, so the
 * body cache — the one store no refresh prunes — can be dropped when the numbering moves.
 *
 * Purely additive: the new column is nullable with no default and the new table starts empty,
 * both of which read as "not known", which is the conservative state.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL)
        db.execSQL(MAILBOX_UIDVALIDITY_CREATE_SQL)
    }
}

/**
 * Additive 19→20: `emails` gains `replyToJson`, the message's `Reply-To:` addresses
 * ([EmailRecipients], see [EmailEntity.replyToJson]).
 *
 * A sender that sets `Reply-To` is naming another mailbox to answer — a list, a `support@` behind
 * a `no-reply@`, a ticketing alias. Answering the `From` instead posts the reply to an address the
 * sender set aside, and it looks sent. The address therefore has to survive in the cache, so an
 * offline reply and the notification's quick reply see it too.
 *
 * A plain `ALTER TABLE … ADD COLUMN`, on the model of [MIGRATION_16_17], layered on top rather
 * than folded into a released migration (rewriting one in place fails Room's identity check on the
 * next launch). Nullable with no default, so every existing row keeps its data and reads back as
 * "no Reply-To" — the pre-v20 behaviour. No backfill: the header is not held locally, so an old
 * row gains it at its next sync.
 *
 * Skipping this migration is not a cosmetic loss: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so an unhandled schema bump wipes the whole database —
 * the outbox included, i.e. mail written and never sent.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `replyToJson` TEXT")
    }
}

/**
 * Additive 20→21: `emails` gains `ccJson` and `bccJson`, the message's copied recipients
 * ([EmailRecipients], see [EmailEntity.ccJson] and [EmailEntity.bccJson]).
 *
 * The cached row carried the `To:` since v17 but never the copies, and it is the row a reopened
 * draft and a "reply all" are rebuilt from. So reopening a draft came back with its Cc and Bcc
 * fields emptied — valid addresses included, the Cc/Bcc row folded shut, nothing on screen saying
 * a recipient had gone — and replying to all silently left out everyone in copy.
 *
 * Two plain `ALTER TABLE … ADD COLUMN`, on the model of [MIGRATION_19_20], layered on top rather
 * than folded into a released migration or into `EMAILS_CREATE_SQL` (rewriting either in place
 * breaks the installs that replay the older statements and fails Room's identity check on the
 * next launch). Nullable with no default, so every existing row keeps its data and reads back as
 * "no copies" — the pre-v21 behaviour. No backfill: the addresses are not held locally, so an old
 * row gains them at its next sync.
 *
 * Skipping this migration is not a cosmetic loss: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so an unhandled schema bump wipes the whole database —
 * the outbox included, i.e. mail written and never sent.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `ccJson` TEXT")
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `bccJson` TEXT")
    }
}

/**
 * Additive 21→22: `outbox` and `scheduled_sends` each gain `requestReceipt`, the per-message
 * "ask for a read receipt" choice ([OutboxEntity.requestReceipt], [ScheduledSendEntity.requestReceipt]).
 *
 * BOTH tables, not one. A message the user asked a receipt for can wait in either — the outbox for
 * the undo window (or a failed retry), the scheduled table for hours — and a scheduled send only
 * becomes an outbox row when its worker fires. A column on one table alone drops the request in
 * the handover, silently, and the mail goes out asking nothing.
 *
 * These two tables are the ones that hold UNSENT MAIL. `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so this step missing from
 * [SternaDatabase.ALL_MIGRATIONS] — written but not registered — does not degrade anything: Room
 * finds no 21→22 path and DROPS every table, the queued messages included.
 *
 * `INTEGER NOT NULL DEFAULT 0` rather than the nullable `TEXT` of the columns above, because the
 * entity field is a non-null `Boolean`: SQLite requires a default to add a NOT NULL column to a
 * populated table, and Room compares defaults at open time only when the ENTITY declares one (a
 * Kotlin default is not a `@ColumnInfo(defaultValue = …)`, b/136515305). Existing rows therefore
 * read back as 0 — "no receipt asked for", exactly what they were queued as.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive 22→23: `outbox` and `scheduled_sends` each gain `draftUidValidity`, the IMAP numbering
 * the composer read `draftEmailId` under ([OutboxEntity.draftUidValidity],
 * [ScheduledSendEntity.draftUidValidity]).
 *
 * The column exists because the draft is destroyed at DELIVERY time, in a headless worker, long
 * after the composer is gone: an offline send leaves when the network returns, a scheduled one at
 * its hour. A `UID EXPUNGE` only names a message inside one UIDVALIDITY, so a number looked up then
 * is the number a background sync recorded AFTER a renumbering — compared with itself it reads
 * "same", and the expunge lands on whatever message now holds those UIDs in Drafts (#99, the shape
 * of #122 on a folder the user writes in).
 *
 * BOTH tables, exactly as [MIGRATION_21_22] and for the same reason, with a worse failure: a
 * scheduled send waits in `scheduled_sends` for hours and only becomes an outbox row when
 * [app.sterna.send.ScheduledSendWorker] fires. A column on one table alone drops the frozen number
 * in that hand-over, silently, and the destroy goes out blind hours later.
 *
 * `INTEGER` NULLABLE with NO DEFAULT — the shape of `draftEmailId TEXT` above, not that of
 * `requestReceipt INTEGER NOT NULL DEFAULT 0`: the entity field is a `Long?`, and Room's
 * `TableInfo` compares affinity and nullability at open time.
 *
 * **No backfill, deliberately.** Rows already queued read back `NULL`, and `null` DESTROYS
 * NOTHING. There is nothing honest to backfill from: nobody knows which numbering those rows were
 * enqueued under, and inventing the folder's current one is precisely the read this fix removes.
 * The cost of `NULL` is a draft left behind in Drafts once — visible, and one tap to delete; the
 * cost of guessing is an expunge under a numbering nobody can confirm, which is not recoverable.
 *
 * Written AND registered in [SternaDatabase.ALL_MIGRATIONS]: unregistered, Room finds no 22→23
 * path and `fallbackToDestructiveMigration()` DROPS every table — the outbox and the scheduled
 * sends with it, i.e. mail the user wrote that never left the phone.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftUidValidity` INTEGER")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftUidValidity` INTEGER")
    }
}

/**
 * SQL that creates the `local_drafts` table ([LocalDraftEntity]) — drafts written on the phone and
 * not yet on the server (#95). Kept as ONE constant, on the model of [OUTBOX_CREATE_SQL], so the
 * 23→24 migration and the JVM test build the exact same table instead of two copies that drift.
 *
 * Every column of the entity is here, the ones the first volet does not read included. The table
 * is created EMPTY, so no `DEFAULT` is needed to add a NOT NULL column — and none is written, which
 * keeps this shape identical to the one Room's own `createAllTables` gives a fresh install. The
 * entity declares no `@ColumnInfo(defaultValue = …)` either: a Kotlin default is not a column
 * default, and declaring one would make Room compare defaults at open time (b/136515305).
 *
 * The types are not decoration. Room's `TableInfo.Column.equals` compares the affinity and the
 * nullability of every column at open time, so `String` → `TEXT NOT NULL`, `String?` → `TEXT`,
 * `Long`/`Int`/`Boolean` → `INTEGER NOT NULL`, and the enum → `TEXT NOT NULL`. A column of the
 * right name and the wrong shape fails `validateMigration` on EVERY launch, and the user's only way
 * out is a reinstall — which takes the outbox with it.
 *
 * `PRIMARY KEY(accountId, id)`, in that order, like `emails` since issue #31: two accounts of one
 * server each mint ids in their own space.
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
 * Additive 23→24: the `local_drafts` table (#95), where a draft saved with no network waits for
 * one instead of dying with the process that held it.
 *
 * Purely additive, on the model of [MIGRATION_17_18]: no existing table is read or touched, so
 * nothing can be lost on the way up. The table starts empty, which is the correct state — an
 * install coming from v23 has no draft that was ever kept locally, because there was nowhere to
 * keep one.
 *
 * Why the table exists at all rather than a flagged row in `emails`: four cache-pruning paths
 * delete a row `emails` holds that the server does not know about — the reconcile, the ghost sweep
 * (which takes the body too), the retention prune — and the only shield any of them honours is a
 * 45-second recently-mutated window that no draft path arms. See [LocalDraftEntity].
 *
 * Written is not registered. This step missing from [SternaDatabase.ALL_MIGRATIONS] does not
 * degrade anything: `SternaDatabase.build` ends with `fallbackToDestructiveMigration()`, so Room
 * finds no 23→24 path and DROPS every table — `outbox` and `scheduled_sends` included, i.e. mail
 * the user wrote and that never left the phone.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(LOCAL_DRAFTS_CREATE_SQL)
    }
}

/**
 * Additive 24→25: `emails` gains `uidValidity`, the IMAP numbering each cached row was READ under
 * ([EmailEntity.uidValidity], Codeberg #99).
 *
 * What it closes: the numbering was tracked per FOLDER (`mailbox_uidvalidity`) while the ids a
 * destroy names come from ROWS that carried none. A background pass that finds a folder renumbered
 * records the NEW number and leaves the old rows in place — nothing prunes them on IMAP — so a
 * destroy froze the new number, the SELECT compared it with itself, and `UID EXPUNGE` went out on
 * UIDs that now name other, live messages. With the numbering ON the row, what is opposed is what
 * was read.
 *
 * One plain `ALTER TABLE … ADD COLUMN`, on the model of [MIGRATION_19_20], layered on top rather
 * than folded into a released migration (rewriting one breaks the installs that replay the older
 * statements and fails Room's identity check on the next launch). Nullable with no default: every
 * existing row keeps its data and reads back as "no numbering to oppose", which destroys nothing.
 * No backfill, deliberately — writing the folder's CURRENT number into old rows would license
 * precisely the expunge this column exists to refuse.
 *
 * Skipping this migration is not a cosmetic loss: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so this step missing from [SternaDatabase.ALL_MIGRATIONS] —
 * written but not registered — has Room find no 24→25 path and DROP every table: `outbox`,
 * `scheduled_sends` and `local_drafts` included, i.e. mail the user wrote that never left.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `uidValidity` INTEGER")
    }
}

/**
 * Additive 25→26: `mailboxes` gains `isSubscribed`, what the server reports about the folder's
 * subscription ([MailboxEntity.isSubscribed], Codeberg #174) — IMAP's `LSUB`, JMAP's
 * `isSubscribed`. The column only exists here; reading it off the wire and acting on it are later
 * steps, and until they land every row simply says "subscribed".
 *
 * One plain `ALTER TABLE … ADD COLUMN`, on the model of [MIGRATION_21_22], layered on top rather
 * than folded into a released migration (rewriting one breaks the installs that replay the older
 * statements and fails Room's identity check on the next launch).
 *
 * `INTEGER NOT NULL DEFAULT 1`, and the `1` is not cosmetic. The entity field is a non-null
 * `Boolean`, so SQLite requires a default to add the column to a populated table — but the VALUE
 * of that default is the decision: a folder cached before v26 was cached by a build that never
 * asked the server about subscription, so what it holds is not "unsubscribed", it is UNKNOWN, and
 * the unknown never hides anything. `DEFAULT 0` would take every folder of every existing install
 * out of the drawer at the first launch after the update, on no evidence at all.
 *
 * Room compares defaults at open time only when the ENTITY declares one via
 * `@ColumnInfo(defaultValue = …)`, and a Kotlin default is not one (b/136515305) — so the `1` here
 * is safe, exactly as the `0` of [MIGRATION_21_22] is.
 *
 * Skipping this migration is not a cosmetic loss: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so this step missing from [SternaDatabase.ALL_MIGRATIONS] —
 * written but not registered — has Room find no 25→26 path and DROP every table: `outbox`,
 * `scheduled_sends` and `local_drafts` included, i.e. mail the user wrote that never left.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `mailboxes` ADD COLUMN `isSubscribed` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Additive 26→27: `emails` gains the composite index `(accountId, mailboxId, sortKey)` — the three
 * columns a list query reads a folder by, in the order it reads them.
 *
 * `(accountId, mailboxId)` is the SCOPE: `folderScopeSql` selects on (account, folder) PAIRS and
 * never on a bare mailbox id (Codeberg #121 — a server numbers folders per account, so a bare id
 * lets a sibling account's Inbox leak into the list), which is why `accountId` leads. `sortKey`
 * comes last because it is the ORDER (`ORDER BY sortKey DESC`) and the join that picks a thread's
 * representative row in the conversation view (`e.accountId = g.gacc AND e.sortKey = g.maxKey`),
 * both applied to rows the first two columns have already narrowed down.
 *
 * What it closes: with `Index("mailboxId")` alone, the representative join of `conversationSql` had
 * nothing to lean on and walked the account's whole primary key once per collapsed thread, while
 * the two `GROUP BY` sub-queries reached the folder by the mailbox id alone — every account's rows
 * carrying that server-assigned id, in no useful order. Switching folder with the conversation view
 * ON left the list blank for a second or two on mail already cached and with no sync running; with
 * the view OFF the same switch was instant.
 *
 * What this step does NOT close, and it is the honest half: the `threadTotal` sub-query of
 * `conversationSql` carries no folder scope at all, so it still groups the WHOLE table, every
 * account and every folder, and no index can help it as written. It was measured to cost little
 * once this one is in place, but "the list no longer re-reads the folder to collapse it" is the
 * whole of what is claimed here.
 *
 * Additive — one `CREATE INDEX IF NOT EXISTS`, no table rebuilt, no row rewritten, and
 * `index_emails_mailboxId` deliberately left in place (dropping it would need a `DROP INDEX` of its
 * own and buys nothing). The index is NOT folded into [EMAILS_CREATE_SQL]: that constant is the
 * frozen v16 shape every install coming from v15 replays, and rewriting a released migration breaks
 * exactly those installs.
 *
 * The NAME is not decoration: `index_emails_accountId_mailboxId_sortKey` is what Room derives
 * from `@Entity(indices = [Index(value = ["accountId", "mailboxId", "sortKey"])])` on a FRESH
 * install. An updated install must end up with the same name, or Room's `validateMigration` sees an
 * index the entity never declared and refuses to open the database on every launch.
 *
 * Skipping this migration is not a cosmetic loss: `SternaDatabase` ends its builder with
 * `fallbackToDestructiveMigration()`, so this step missing from [SternaDatabase.ALL_MIGRATIONS] —
 * written but not registered — has Room find no 26→27 path and DROP every table: `outbox`,
 * `scheduled_sends` and `local_drafts` included, i.e. mail the user wrote that never left.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_emails_accountId_mailboxId_sortKey` " +
                "ON `emails` (`accountId`, `mailboxId`, `sortKey`)",
        )
    }
}
