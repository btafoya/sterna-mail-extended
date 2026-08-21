package app.sterna.core.data.db

/**
 * One line of the "latest messages" home-screen widget: exactly what a line can DRAW ([subject],
 * [fromName]/[fromEmail], [seen], [receivedAt]) or OPEN ([id] + [accountId], the composite key of
 * `emails` — a JMAP id is unique only inside its account, #31), plus [sortKey] and [mailboxId],
 * which the caller needs to order and to name the folder it came from.
 *
 * ⛔ There is deliberately NO `preview` here, and this type exists mainly to make that absence
 * structural. [EmailEntity] carries one, and a widget sits on an UNLOCKED home screen in front of
 * whoever is holding the phone: the body of a message must never reach it, under any setting.
 * Returning the entity would put the column one `row.preview` away from being drawn by a later
 * reader who never read this comment; not carrying it across means no such reader can exist.
 * ⛔ Do not add the column here "for a subtitle" — that is the same decision, re-taken by accident.
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

/**
 * The columns of [RecentEmailRow], as the SELECT list of the widget's query. Named one by one
 * rather than `SELECT *` on purpose: `*` would hand Room the `preview` column too, and the whole
 * point of [RecentEmailRow] is that the body never leaves the database.
 */
internal const val RECENT_EMAIL_ROW_COLUMNS: String =
    "id, accountId, mailboxId, subject, fromName, fromEmail, seen, sortKey, receivedAt"
