package app.sterna.push

import app.sterna.core.data.account.MailProtocol

/**
 * The two UnifiedPush decisions, as pure functions: whether an account may register now,
 * and what an arriving endpoint means. Nothing here knows a Context, a distributor or a
 * MailRepository — [UnifiedPushManager] keeps every side effect, this file keeps the rules,
 * and that is the only reason this subsystem can be tested at all.
 */
object RelayPush {

    /** How long REGISTERING/VERIFYING counts as in-flight before the watchdog retries. */
    const val PENDING_GRACE_MS = 2 * 60 * 1000L

    /** Cooldown before a FAILED account may re-register (worker cycles retry for us). */
    const val FAILED_RETRY_MS = 15 * 60 * 1000L

    /**
     * May this account send a UnifiedPush.register right now?
     *
     * Three windows, unchanged: never registered, a FAILED whose cooldown has elapsed, or a
     * pending state gone stale (lazy watchdog, no timers). The FAILED cooldown is load-bearing:
     * a failure invokes onTransportStateChanged → apply() → back here, and without it that is a
     * tight register/fail loop against the mail server.
     *
     * A non-JMAP account rides them only once its owner asked for a relay address: an IMAP server
     * has nothing to subscribe to, so an unasked-for registration would publish an address nobody
     * will ever post to. JMAP is unaffected by [relayRequested].
     */
    fun shouldRegister(
        protocol: MailProtocol,
        relayRequested: Boolean,
        status: UpStatus,
        statusSinceMillis: Long,
        now: Long,
    ): Boolean {
        if (!mayUsePush(protocol, relayRequested)) return false
        val stalePending = (status == UpStatus.REGISTERING || status == UpStatus.VERIFYING) &&
            now - statusSinceMillis >= PENDING_GRACE_MS
        val failedRetryDue = status == UpStatus.FAILED && now - statusSinceMillis >= FAILED_RETRY_MS
        return status == UpStatus.NONE || failedRetryDue || stalePending
    }

    /**
     * May this account touch the UnifiedPush subsystem AT ALL?
     *
     * JMAP always may — it holds a PushSubscription and the transport is its own. Anything else
     * only once its owner asked for a relay address: an IMAP server has nothing to subscribe to.
     *
     * ⛔ Split out of [shouldRegister] because the manager needs this one question EARLIER than the
     * registration window: an account that may not use push at all must leave before
     * `ensureDistributor()`, whose picker is the only UI this subsystem has — while a JMAP account
     * must go through it whatever the registration window says, or a freshly installed distributor
     * goes unnoticed for the whole FAILED cooldown. Two call sites, one rule, stated once.
     */
    fun mayUsePush(protocol: MailProtocol, relayRequested: Boolean): Boolean =
        protocol == MailProtocol.JMAP || relayRequested

    /**
     * The user tapped "Get an address": what to persist before registering.
     *
     * ⛔ The status goes back to NONE, and that is the whole point. Withdrawing an address
     * unregisters the instance; the distributor answers; `onUnregistered` writes FAILED stamped
     * NOW — so "Remove address" immediately followed by "Get an address" hit the 15-minute FAILED
     * cooldown of [shouldRegister] and did nothing at all, in silence, which is exactly the
     * on-screen lie this work exists to remove. That cooldown guards the AUTOMATIC path
     * (a failure calls onTransportStateChanged → apply() → back into shouldRegister, a tight
     * loop against the server); it was never meant to punish a deliberate tap, and it stays
     * untouched for every path that is not one.
     */
    fun onRelayRequested(prev: UpAccountState, now: Long): UpAccountState =
        prev.copy(relayRequested = true, status = UpStatus.NONE, statusSinceMillis = now)

    /**
     * The address this account may SHOW, or null.
     *
     * ⛔ Not simply the stored endpoint: `markFailed` and `onUnregistered` keep that field, so
     * returning it whatever the status puts a dead address on screen as a live one — an address
     * the user then pastes into a relay that will post to it for ever with nothing listening.
     * Only a relay the user asked for, whose address is published (or has since been posted to),
     * has an address to show.
     */
    fun publishedAddress(state: UpAccountState?): String? {
        if (state == null || !state.relayRequested) return null
        if (state.status != UpStatus.PUBLISHED && state.status != UpStatus.ACTIVE) return null
        return state.endpoint
    }

    /**
     * What the distributor's endpoint delivery means for this account. The connector redelivers
     * the endpoint on EVERY register, so the no-change case must be recognised or an armed
     * account is knocked back on every cycle.
     */
    fun endpointOutcome(
        protocol: MailProtocol,
        prev: UpAccountState,
        url: String,
        now: Long,
    ): EndpointOutcome {
        if (protocol != MailProtocol.JMAP) {
            // Nobody asked for this address any more. The endpoint arrives on its own schedule, so
            // a withdrawal can lose the race to a delivery already in flight: without this the
            // record would be written straight back to PUBLISHED, and the account would hold a
            // published address its owner had just removed — the one thing an explicit request is
            // there to prevent.
            if (!prev.relayRequested) return EndpointOutcome.Ignore
            // Same address, already published or already armed: nothing to write. Without this,
            // every register would drag an ACTIVE relay back down to PUBLISHED and the account
            // would look like it had never received anything.
            if (prev.endpoint == url && (prev.status == UpStatus.PUBLISHED || prev.status == UpStatus.ACTIVE)) {
                return EndpointOutcome.Ignore
            }
            // Rotation: the address the user had pasted into their relay just died, so the wake
            // it had earned dies with it. Carrying lastDeliveryMillis over a copy() would leave
            // the screen showing a delivery that belonged to an address nobody posts to any more.
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
        val pendingFresh = prev.status == UpStatus.VERIFYING && now - prev.statusSinceMillis < PENDING_GRACE_MS
        if (prev.endpoint == url && prev.subscriptionId != null &&
            (pendingFresh || prev.status == UpStatus.ACTIVE)
        ) {
            return EndpointOutcome.Ignore
        }
        return EndpointOutcome.Subscribe
    }

    /**
     * A payload really arrived through this account's endpoint. Returns what to persist, or null
     * when there is nothing to write — the caller must not save on every message.
     *
     * ⛔ THIS, and not the existence of an address, is what arms a relay. The subsystem enrols the
     * single installed distributor silently, so installing a notification app for some unrelated
     * reason publishes an address here; switching the transport on that alone would drop an IMAP
     * account's permanent connection in favour of an address nobody ever posts to — mail half an
     * hour late behind a screen reading "push".
     *
     * ⛔ Null for a JMAP account (it holds a [UpAccountState.subscriptionId]): its ACTIVE is the
     * server's PushVerification and a mere message must not counterfeit it.
     */
    fun onDelivery(prev: UpAccountState, now: Long): DeliveryUpdate? {
        if (!prev.relayRequested || prev.subscriptionId != null) return null
        return when (prev.status) {
            // First delivery: the address is proven live. The status moved, so its timestamp moves.
            UpStatus.PUBLISHED -> DeliveryUpdate(
                prev.copy(status = UpStatus.ACTIVE, statusSinceMillis = now, lastDeliveryMillis = now),
                armed = true,
            )
            // Already armed: stamp it and nothing else. armed = false keeps the caller from
            // rebuilding every connection once per incoming message.
            UpStatus.ACTIVE ->
                if (prev.lastDeliveryMillis == now) null
                else DeliveryUpdate(prev.copy(lastDeliveryMillis = now), armed = false)
            // No address published (or one we gave up on): nothing to arm.
            else -> null
        }
    }
}

/**
 * What [RelayPush.onDelivery] tells the manager to write. [armed] is true on the one delivery that
 * took the account from PUBLISHED to ACTIVE — the only one that is a transport change; carried in
 * the return value precisely so the caller cannot re-derive it and get it wrong.
 */
data class DeliveryUpdate(val state: UpAccountState, val armed: Boolean)

/** What [RelayPush.endpointOutcome] tells the manager to do with an arriving endpoint. */
sealed interface EndpointOutcome {

    /** Same endpoint, account already covered by it — do nothing at all. */
    data object Ignore : EndpointOutcome

    /** Relay account: persist [state] and stop. No server call follows this. */
    data class Relay(val state: UpAccountState) : EndpointOutcome

    /** JMAP account: run the PushSubscription path (destroy, create, verify). */
    data object Subscribe : EndpointOutcome
}
