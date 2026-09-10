package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.resolveDeliveryMode

/**
 * One account reduced to what the transport decision needs, so that decision can be taken, and
 * tested, without a container. A null [deliveryMode] is an account that has never chosen one.
 */
internal data class AccountDelivery(
    val id: String,
    val isLinked: Boolean,
    val unifiedPushActive: Boolean,
    val protocol: MailProtocol,
    val deliveryMode: DeliveryMode?,
)

/** How one account's new mail arrives, [globalDelivery] standing in wherever it chose nothing. */
internal fun transportOf(account: AccountDelivery, globalDelivery: DeliveryMode): Transport {
    val batterySaver =
        resolveDeliveryMode(account.deliveryMode, globalDelivery) == DeliveryMode.BATTERY_SAVER
    return when {
        // A linked sub-account holds no live push of its own (#31): the server never puts an
        // ACL-shared account's StateChanges on the login's EventSource.
        account.isLinked -> Transport.PERIODIC
        // Not gated on JMAP: an IMAP relay rides this too, but only once active, a payload
        // really arrived, or any distributor would trade IMAP IDLE for a dead endpoint.
        account.unifiedPushActive -> Transport.UNIFIED_PUSH
        batterySaver -> Transport.PERIODIC
        account.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE
        else -> Transport.IMAP_IDLE
    }
}

/**
 * The accounts a connection of ours has to be held open for. Delivery being each account's own
 * setting, a single one left on instant delivery answers here and holds the foreground service and
 * its permanent notification up for all of them.
 */
internal fun directAccountIds(accounts: List<AccountDelivery>, globalDelivery: DeliveryMode): List<String> =
    accounts.filter { transportOf(it, globalDelivery).isDirect }.map { it.id }
