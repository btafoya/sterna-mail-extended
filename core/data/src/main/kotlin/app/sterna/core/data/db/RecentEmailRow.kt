package app.sterna.core.data.db

/**
 * One line of the "latest messages" home-screen widget.
 *
 * Deliberately NO `preview`: a widget sits on an UNLOCKED home screen, so the body must
 * never reach it — returning [EmailEntity] instead would put it one `row.preview` away.
 */
data class RecentEmailRow(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val subject: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    /** Epoch millis derived from `receivedAt` — what the list orders on. */
    val sortKey: Long,
    val receivedAt: String?,
)

/** SELECT list of [RecentEmailRow]'s query, named one by one — `SELECT *` would hand Room
 *  the `preview` column too. */
internal const val RECENT_EMAIL_ROW_COLUMNS: String =
    "id, accountId, mailboxId, subject, fromName, fromEmail, seen, sortKey, receivedAt"
