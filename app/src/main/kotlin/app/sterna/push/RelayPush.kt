package app.sterna.push

import app.sterna.core.data.account.MailProtocol

/** The UnifiedPush decisions, as pure functions; [UnifiedPushManager] keeps every side effect. */
object RelayPush {

    /** In-flight window for REGISTERING: that wait is on the local distributor, and it is short. */
    const val PENDING_GRACE_MS = 2 * 60 * 1000L

    /** In-flight window for VERIFYING. 5 min 33 is the only verification latency ever observed on
     *  this server, which is what makes 2 minutes too short; 10 is a choice, and it does not cover
     *  the failing account (14 min 35 between two creations, no verification), the cap below does. */
    const val VERIFYING_GRACE_MS = 10 * 60 * 1000L

    /** Creations for one endpoint before giving up. Failed stops the hammering and is nameable on
     *  screen: it makes `unifiedPushMissing` true, which qualifies the direct-connection line. */
    const val MAX_CREATE_ATTEMPTS = 3

    /** Cooldown before a failed account may re-register (worker cycles retry for us). */
    const val FAILED_RETRY_MS = 15 * 60 * 1000L

    /** May this account register right now? Never registered, a stale pending state, or a failure past its cooldown. */
    fun shouldRegister(
        protocol: MailProtocol,
        relayRequested: Boolean,
        status: UpStatus,
        statusSinceMillis: Long,
        now: Long,
    ): Boolean {
        if (!mayUsePush(protocol, relayRequested)) return false
        val stalePending = (status == UpStatus.REGISTERING || status == UpStatus.VERIFYING) &&
            now - statusSinceMillis >= pendingGraceMs(status)
        val failedRetryDue = status == UpStatus.FAILED && now - statusSinceMillis >= FAILED_RETRY_MS
        return status == UpStatus.NONE || failedRetryDue || stalePending
    }

    /** The switch went off: whose own subscription is there to give up? None without a record, none under a
     *  sub-account still on (#31), none for a relay address whose one exit is its Remove button: this is final. */
    fun shouldTearDown(
        isLinked: Boolean,
        hasPushState: Boolean,
        linkedAccountStillOn: Boolean,
        relayRequested: Boolean,
    ): Boolean = !isLinked && hasPushState && !linkedAccountStillOn && !relayRequested

    fun distributorGate(chosen: Boolean, installed: Int, declined: Boolean): DistributorGate = when {
        chosen -> DistributorGate.READY
        installed == 0 || declined -> DistributorGate.STAY
        else -> DistributorGate.ASK
    }

    /** May this account touch the UnifiedPush subsystem at all? JMAP always may, anything else
     *  only once its owner asked for a relay address. */
    fun mayUsePush(protocol: MailProtocol, relayRequested: Boolean): Boolean =
        protocol == MailProtocol.JMAP || relayRequested

    /** Should this account be riding UnifiedPush right now, and is not? [pending] keeps a healthy
     *  bring-up silent (#53), [mayUsePush] an account that never asked, published a live address. */
    fun unifiedPushMissing(
        protocol: MailProtocol?,
        relayRequested: Boolean,
        status: UpStatus,
        pending: Boolean,
    ): Boolean =
        protocol != null && mayUsePush(protocol, relayRequested) && !pending &&
            status != UpStatus.PUBLISHED

    /** How long [status] counts as in flight. One window for both waits recreated a subscription
     *  under its own pending verification, on every apply, for as long as the account existed. */
    fun pendingGraceMs(status: UpStatus): Long =
        if (status == UpStatus.VERIFYING) VERIFYING_GRACE_MS else PENDING_GRACE_MS

    /** Which creation for [url] the next one would be; a different address starts its own count. */
    fun createAttemptsFor(prev: UpAccountState, url: String): Int =
        if (prev.endpoint == url) prev.createAttempts + 1 else 1

    /** Is the budget of [MAX_CREATE_ATTEMPTS] spent for [url]? Counted per endpoint: a rotation
     *  starts over, or a newly picked distributor could never bring push back. */
    fun createBudgetSpent(prev: UpAccountState, url: String): Boolean =
        prev.endpoint == url && prev.createAttempts >= MAX_CREATE_ATTEMPTS

    /** The user tapped "Get an address": what to persist before registering, status back to none. */
    fun onRelayRequested(prev: UpAccountState, now: Long): UpAccountState =
        prev.copy(relayRequested = true, status = UpStatus.NONE, statusSinceMillis = now)

    /** A distributor picked in person: what to persist, or null when nothing is due. The explicit
     *  gesture clears failed and the spent create budget; `relayRequested` never, it asks no address. */
    fun onDistributorChosen(prev: UpAccountState, now: Long): UpAccountState? =
        if (prev.status != UpStatus.FAILED) null
        else prev.copy(status = UpStatus.NONE, statusSinceMillis = now, createAttempts = 0)

    /** The address this account may show, or null. `markFailed` and `onUnregistered` both keep the
     *  stored endpoint, so returning it whatever the status shows a dead address as a live one. */
    fun publishedAddress(state: UpAccountState?): String? {
        if (state == null || !state.relayRequested) return null
        if (state.status != UpStatus.PUBLISHED && state.status != UpStatus.ACTIVE) return null
        return state.endpoint
    }

    /** What the distributor's endpoint delivery means. The connector redelivers it on every
     *  register, so the no-change case must be recognised or an armed account is knocked back. */
    fun endpointOutcome(
        protocol: MailProtocol,
        prev: UpAccountState,
        url: String,
        now: Long,
    ): EndpointOutcome {
        if (protocol != MailProtocol.JMAP) {
            // Nobody asked for this address any more: the endpoint lost a race to the withdrawal.
            if (!prev.relayRequested) return EndpointOutcome.Ignore
            // Same address, already published or armed: a write drags an active relay to published.
            if (prev.endpoint == url && (prev.status == UpStatus.PUBLISHED || prev.status == UpStatus.ACTIVE)) {
                return EndpointOutcome.Ignore
            }
            // Rotation: the old address is dead, and a carried lastDeliveryMillis dates a wake to it.
            return EndpointOutcome.Relay(
                prev.copy(
                    endpoint = url,
                    subscriptionId = null,
                    status = UpStatus.PUBLISHED,
                    statusSinceMillis = now,
                    lastDeliveryMillis = 0,
                ),
            )
        }
        val pendingFresh = prev.status == UpStatus.VERIFYING &&
            now - prev.statusSinceMillis < pendingGraceMs(prev.status)
        if (prev.endpoint == url && prev.subscriptionId != null &&
            (pendingFresh || prev.status == UpStatus.ACTIVE)
        ) {
            return EndpointOutcome.Ignore
        }
        return EndpointOutcome.Subscribe
    }

    /** A payload really arrived through this endpoint: what to persist, or null when nothing changes. */
    fun onDelivery(prev: UpAccountState, now: Long): DeliveryUpdate? {
        if (!prev.relayRequested || prev.subscriptionId != null) return null
        return when (prev.status) {
            // First delivery: the address is proven live. The status moved, so its timestamp moves.
            UpStatus.PUBLISHED -> DeliveryUpdate(
                prev.copy(status = UpStatus.ACTIVE, statusSinceMillis = now, lastDeliveryMillis = now),
                armed = true,
            )
            // Already armed: stamp it and nothing else, or the caller rebuilds every connection.
            UpStatus.ACTIVE ->
                if (prev.lastDeliveryMillis == now) null
                else DeliveryUpdate(prev.copy(lastDeliveryMillis = now), armed = false)
            // No address published (or one we gave up on): nothing to arm.
            else -> null
        }
    }

    fun onRenewed(
        current: UpAccountState,
        renewedSubscriptionId: String,
        expiresAtMillis: Long,
    ): UpAccountState? =
        if (current.subscriptionId != renewedSubscriptionId) null
        else current.copy(expiresAtMillis = expiresAtMillis)

    /** Null writes nothing, and that refusal is what leaves a FAILED landed meanwhile standing. */
    fun onRenewFailed(current: UpAccountState, now: Long): UpAccountState? =
        if (current.status != UpStatus.ACTIVE) null
        else current.copy(status = UpStatus.NONE, statusSinceMillis = now)
}

/** What [RelayPush.onDelivery] tells the manager to write. [armed] is true on the one delivery that
 *  took the account from published to active, the only one that is a transport change. */
data class DeliveryUpdate(val state: UpAccountState, val armed: Boolean)

enum class DistributorGate { READY, STAY, ASK }

sealed interface EndpointOutcome {

    /** Same endpoint, account already covered by it, do nothing at all. */
    data object Ignore : EndpointOutcome

    /** Relay account: persist [state] and stop. No server call follows this. */
    data class Relay(val state: UpAccountState) : EndpointOutcome

    /** JMAP account: run the PushSubscription path (destroy, create, verify). */
    data object Subscribe : EndpointOutcome
}
