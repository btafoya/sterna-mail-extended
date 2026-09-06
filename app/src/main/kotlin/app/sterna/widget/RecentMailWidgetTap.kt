package app.sterna.widget

import app.sterna.core.data.db.RecentEmailRow

/**
 * WHAT ONE ROW ASKS THE APP TO OPEN: the three identifiers a tap carries, and nothing else.
 *
 * They are the very three extras a tapped new-mail notification already carries
 * (`MainActivity.EXTRA_OPEN_EMAIL_ID` / `_ACCOUNT_ID` / `_MAILBOX_ID`), so a row opens a message by
 * the route that has been carrying notification taps for releases — with every guard that route
 * already holds: the account that is gone, the folder that is unknown, the message purged from the
 * cache. Nothing new is guarded here, and nothing here explains a failure to open: the app's own
 * generic line does that, and it names no cause (#159).
 *
 * This is NOT drawn. It sits beside [RecentWidgetRow], never inside it: what a row DRAWS may
 * never carry an identifier (see [RecentWidgetRow]'s own note), and what a row OPENS is nothing but
 * identifiers. Keeping them two types is what makes "the home screen must not name anyone" a
 * property of a shape rather than of a reviewer's attention.
 */
internal data class RecentRowTap(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String?,
)

/**
 * The rule that derives [RecentRowTap] from a database row, kept apart from Android so it can be
 * EXECUTED in a JVM test — the same shape and the same reason as [UnreadWidgetTap] next door.
 * `Intent`, `PendingIntent` and `MainActivity` do not run in this suite (there is no Robolectric
 * here), so what a tap ASKS FOR is decided here, and the plumbing that spends it is pinned by a
 * source lint.
 */
internal object RecentMailWidgetTap {

    /**
     * BLANK IS ABSENT, on the two that may be. `MainActivity.parseEmailOpen` already reads both
     * as `getStringExtra(…)?.ifBlank { null }`, and `SternaApp` hands the folder half on only when
     * BOTH are non-null; a blank travelling as a blank would ask for the folder `""` of the account
     * `""`. Deciding it here, once, keeps the widget's request byte-identical to a notification's.
     *
     * The message id is carried as it stands, and nothing is guarded here. A row whose id opens
     * nothing meets the app's own generic line, the same one the message list inside the app meets
     * with the same id — this pane adds no guard and invents no cause (#159).
     */
    fun of(row: RecentEmailRow): RecentRowTap = RecentRowTap(
        emailId = row.id,
        accountId = row.accountId.takeIf { it.isNotBlank() },
        mailboxId = row.mailboxId.takeIf { it.isNotBlank() },
    )
}
