package app.sterna.core.data.db

import androidx.room.Entity

/**
 * Cached mailbox (folder) for the navigation drawer. Keyed by (accountId, id): servers
 * like Stalwart number mailboxes per-account, so two accounts' folders can share a bare
 * id — every account keeps its own rows (and counters) side by side, and a count nudge
 * or role lookup can never hit a sibling account's folder.
 */
@Entity(tableName = "mailboxes", primaryKeys = ["accountId", "id"])
data class MailboxEntity(
    /** Local StoredAccount id owning this folder (NOT the JMAP accountId). */
    val accountId: String,
    val id: String,
    val name: String,
    val role: String?,
    /** Parent mailbox id for nested folders (JMAP); null = top-level. */
    val parentId: String? = null,
    val sortOrder: Int,
    val totalEmails: Int,
    val unreadEmails: Int,
    /**
     * Whether the SERVER reports this folder as subscribed (IMAP `LSUB`, JMAP `isSubscribed`) —
     * Codeberg #174. A statement of fact about the account, cached so the drawer does not need the
     * network to be drawn.
     *
     * ⭐ `true` when nobody said otherwise, and that default is the decision, not paperwork: a
     * folder whose subscription was never read is UNKNOWN, and the unknown must never hide a
     * folder. Rows cached before v26 read back `1` for the same reason
     * ([app.sterna.core.data.db.MIGRATION_25_26]).
     *
     * A plain non-null `Boolean` with `INTEGER NOT NULL DEFAULT 1` on the column, exactly like
     * [OutboxEntity.requestReceipt] and for the same reason: the Kotlin default is not a
     * `@ColumnInfo` default, so Room's `TableInfo` carries none on the entity side and skips the
     * default comparison at open time (b/136515305).
     */
    val isSubscribed: Boolean = true,
)
