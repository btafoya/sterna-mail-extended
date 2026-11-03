package app.sterna.widget

import app.sterna.MainActivity
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.RecentEmailRow

/**
 * What one row asks the app to open: the three identifiers a tap carries, and nothing else.
 */
internal data class RecentRowTap(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String?,
)

/**
 * The rule that derives [RecentRowTap] from a database row, kept apart from Android so it can be
 */
internal object RecentMailWidgetTap {

    /**
     * Blank is absent, on the two that may be. `MainActivity.parseEmailOpen` already reads both
     */
    fun of(row: RecentEmailRow): RecentRowTap = RecentRowTap(
        emailId = row.id,
        accountId = row.accountId.takeIf { it.isNotBlank() },
        mailboxId = row.mailboxId.takeIf { it.isNotBlank() },
    )

    fun templateExtras(kind: RecentWidgetKind, accounts: List<StoredAccount>): Map<String, Boolean> =
        kind.opensAs?.let { UnreadWidgetTap.extraOf(UnreadWidgetTap.of(it, accounts)) }?.let { mapOf(it to true) } ?: emptyMap()

    fun rowExtras(kind: RecentWidgetKind, tap: RecentRowTap): Map<String, String> =
        if (kind.opensAs != null) emptyMap() else messageExtras(tap)

    private fun messageExtras(tap: RecentRowTap): Map<String, String> = buildMap {
        put(MainActivity.EXTRA_OPEN_EMAIL_ID, tap.emailId)
        tap.accountId?.let { put(MainActivity.EXTRA_OPEN_ACCOUNT_ID, it) }
        tap.mailboxId?.let { put(MainActivity.EXTRA_OPEN_MAILBOX_ID, it) }
    }
}
