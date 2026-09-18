package app.sterna.push

import android.app.NotificationManager
import android.content.Context
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.StoredAccount

/** The app's notification channels as [MailChannels.reconcile] sees them: the seam that lets
 *  the decision run on the JVM. */
interface MailChannelShelf {
    /** Every channel the app has, deleted ones excluded. */
    fun channelIds(): List<String>

    fun anyActiveOn(channelId: String): Boolean

    /** Create the new-mail channel of [accountId] named after [accountLabel], or rename it. */
    fun upsert(accountId: String, accountLabel: String)

    fun delete(channelId: String)
}

/** One new-mail channel per account, kept in line with the stored accounts. */
object MailChannels {

    /**
     * Upserts a channel per account and deletes the `new_mail:` channel of each account gone.
     * The channels are listed before [accounts] is read: a channel is only created for an account
     * already stored, so none listed can belong to an account the read would miss.
     */
    fun reconcile(shelf: MailChannelShelf, accounts: () -> List<StoredAccount>, unreadable: () -> Boolean) {
        val existing = shelf.channelIds()
        val present = accounts()
        // A list that failed to decode reads as empty, and would delete every account's channel
        // along with what the user set on it.
        if (unreadable()) return
        present.forEach { shelf.upsert(it.id, it.label()) }
        val kept = present.mapTo(mutableSetOf()) { Notifications.mailChannelId(it.id) }
        existing.filter { it.startsWith(Notifications.MAIL_CHANNEL_PREFIX) && it !in kept }.forEach { shelf.delete(it) }
        // Deleting a channel takes its notifications down, and a send failure posted there by an
        // older build may carry the only copy of a salvaged text. Retried at the next reconcile.
        val legacy = Notifications.LEGACY_CHANNEL_MAIL
        if (legacy in existing && !shelf.anyActiveOn(legacy)) shelf.delete(legacy)
    }

    /** [reconcile] against the real `NotificationManager` and [store]. */
    fun sync(context: Context, store: AccountStorage) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val shelf = object : MailChannelShelf {
            override fun channelIds(): List<String> = manager.notificationChannels.map { it.id }

            override fun anyActiveOn(channelId: String): Boolean =
                manager.activeNotifications.any { it.notification.channelId == channelId }

            override fun upsert(accountId: String, accountLabel: String) =
                Notifications.ensureMailChannel(context, accountId, accountLabel)

            override fun delete(channelId: String) = manager.deleteNotificationChannel(channelId)
        }
        reconcile(shelf, accounts = { store.accounts() }, unreadable = { store.accountsUnreadable() })
    }
}
