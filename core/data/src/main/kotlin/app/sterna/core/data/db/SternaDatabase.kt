package app.sterna.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The schema version Room is told to open, named rather than written twice: the `@Database`
 * annotation is not readable at runtime (it is not retained), so a test that must check the
 * registered migrations reach the declared version has no other honest way to read it than this
 * very constant. Bumping it without adding the matching step to [SternaDatabase.ALL_MIGRATIONS]
 * is the destructive case: Room finds no path and drops every table, the outbox included.
 */
internal const val SCHEMA_VERSION = 24

@Database(
    entities = [
        EmailEntity::class, EmailFtsEntity::class, EmailBodyEntity::class, MailboxEntity::class,
        ScheduledSendEntity::class, SnoozedEntity::class, RecentContactEntity::class, OutboxEntity::class,
        PurgeSnapshotEntity::class, MailboxUidValidityEntity::class, LocalDraftEntity::class,
    ],
    version = SCHEMA_VERSION,
    exportSchema = false,
)
abstract class SternaDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun emailFtsDao(): EmailFtsDao
    abstract fun emailBodyDao(): EmailBodyDao
    abstract fun mailboxDao(): MailboxDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun snoozedDao(): SnoozedDao
    abstract fun recentContactDao(): RecentContactDao
    abstract fun outboxDao(): OutboxDao
    abstract fun purgeSnapshotDao(): PurgeSnapshotDao
    abstract fun mailboxUidValidityDao(): MailboxUidValidityDao
    abstract fun localDraftDao(): LocalDraftDao

    companion object {
        /**
         * The migrations Room is actually handed — the list IS the decision: a migration that is
         * written, tested and simply absent from here never runs. The builder below ends with
         * [RoomDatabase.Builder.fallbackToDestructiveMigration], so a version whose step is missing
         * from this array is not degraded, it is DROPPED: every table, the outbox included, i.e.
         * mail the user wrote and that never left the phone.
         *
         * A value rather than an argument list so a JVM test can execute it — chain it from a
         * published schema and check where it actually arrives — instead of reading the source
         * text of the call.
         *
         * The outbox holds unsent mail (user data): migrate it additively so a schema bump never
         * destroys a queued send. 13→14 adds draftEmailId to outbox/scheduled_sends (#63); 14→15
         * rebuilds the FTS index (#71); 15→16 rebuilds emails/bodies/snoozed with composite
         * (accountId, id) keys (issue #31), copying every row over — snoozed is user data too, so
         * the migration must never fall back destructively; 16→17 adds the persisted To:
         * recipients (#63); 17→18 adds `purge_snapshot`, the frozen destroy list of an Empty trash
         * (#99); 18→19 records which UIDVALIDITY a snapshot and a folder's cache belong to (#99);
         * 19→20 adds the persisted Reply-To addresses; 20→21 adds the persisted Cc and Bcc, so a
         * reopened draft keeps its copies and "reply all" still answers everyone; 21→22 adds the
         * per-message read-receipt request to `outbox` AND `scheduled_sends`, the two tables a
         * message waits in between the composer and the wire; 22→23 adds `draftUidValidity` to
         * those same two tables — the numbering the composer read the draft under, carried so the
         * delivery destroys it under that numbering instead of reading a fresh one hours later
         * (#99); 23→24 adds `local_drafts`, where a draft saved without a network waits for one
         * (#95) — a third store of text the user typed that the server has not got, and like the
         * other two it is never rebuilt destructively.
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            MIGRATION_22_23, MIGRATION_23_24,
        )

        fun build(context: Context): SternaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SternaDatabase::class.java,
                "sterna.db",
            )
                .addMigrations(*ALL_MIGRATIONS)
                // The rest of the DB is a disposable mirror of the server: if some other schema
                // change has no migration, rebuilding the cache is an acceptable fallback.
                .fallbackToDestructiveMigration()
                .build()
    }
}
