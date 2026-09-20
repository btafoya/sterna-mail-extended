package app.sterna.push

import android.content.Context
import android.util.Log
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.keepsPushForLinkedAccount
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.PushKeys
import app.sterna.core.jmap.model.PushMessagePayload
import app.sterna.core.jmap.model.PushSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.time.Instant

/** The UnifiedPush transport state machine (#17), one instance per app. */
class UnifiedPushManager(
    private val context: Context,
    private val accountStore: AccountStore,
    private val repo: MailRepository,
    private val store: UnifiedPushStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Invoked whenever an account's transport state changes so the owner can re-evaluate
     *  connections. A callback rather than a reference, to avoid a circular dependency. */
    var onTransportStateChanged: (() -> Unit)? = null

    private val _needsDistributorChoice = MutableStateFlow(false)

    /** True while a distributor is installed, none chosen and the picker not refused. */
    val needsDistributorChoice: StateFlow<Boolean> = _needsDistributorChoice.asStateFlow()

    fun distributorInstalled(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Is a distributor chosen? Asking is the only case with UI, and it is asked whatever the
     *  number installed: enrolling one on its owner's behalf subscribes her to a stranger (#204). */
    private fun ensureDistributor(): Boolean {
        val gate = RelayPush.distributorGate(
            chosen = UnifiedPush.getAckDistributor(context) != null ||
                UnifiedPush.getSavedDistributor(context) != null,
            installed = UnifiedPush.getDistributors(context).size,
            declined = store.distributorDeclined(),
        )
        if (gate == DistributorGate.ASK) _needsDistributorChoice.value = true
        return gate == DistributorGate.READY
    }

    fun distributorChosen(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        _needsDistributorChoice.value = false
        rearmFailedAccounts()
        onTransportStateChanged?.invoke()
    }

    /** Clear the failed a lost distributor stamped, ahead of `onTransportStateChanged` whose
     *  apply() reads it back. From the in-person pick only: the connector drops its own saved
     *  distributor on the last unregister, so `ensureDistributor` asks again and would loop here. */
    private fun rearmFailedAccounts() {
        val credentials = accountStore.allCredentials()
        synchronized(this) {
            credentials.forEach { account ->
                store.load(account.id)
                    ?.let { RelayPush.onDistributorChosen(it, System.currentTimeMillis()) }
                    ?.let { store.save(account.id, it) }
            }
        }
    }

    /** The picker answered no: written down, or every apply() raises it again. */
    fun rememberDistributorRefusal() {
        store.setDistributorDeclined(true)
        _needsDistributorChoice.value = false
    }

    fun hideDistributorChoiceForNow() {
        _needsDistributorChoice.value = false
    }

    /** The way back from that refusal. The caller must be a front: replayed, this re-asks for ever. */
    fun allowDistributorChoice() {
        store.setDistributorDeclined(false)
    }

    // A linked sub-account rides its login's one PushSubscription (#31), so its status is the login's.
    fun isActive(accountId: String): Boolean =
        store.load(accountStore.account(accountId)?.loginKey() ?: accountId)?.status == UpStatus.ACTIVE

    /** Reality check before transport decisions: the saved distributor was uninstalled while
     *  accounts rode it, so mark them failed and let their direct connections resume. Called by the
     *  controller and the worker themselves, hence no [onTransportStateChanged] here. */
    fun reconcileDistributorPresence() {
        val saved = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        if (saved != null && saved in UnifiedPush.getDistributors(context)) return
        // The connector lookups above stay outside the monitor; only read-decide-write is locked.
        val credentials = accountStore.allCredentials()
        synchronized(this) {
            credentials.forEach { account ->
                store.load(account.id)
                    ?.takeIf { it.status != UpStatus.NONE && it.status != UpStatus.FAILED }
                    ?.let {
                        store.save(
                            account.id,
                            it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()),
                        )
                    }
            }
        }
    }

    /** Registration/verification in flight and not stale, the EventSource stays up meanwhile. */
    fun isPending(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&
            System.currentTimeMillis() - state.statusSinceMillis < RelayPush.pendingGraceMs(state.status)
    }

    /** Read-only dossier for [RelayPush.unifiedPushMissing]: the account should be on UnifiedPush
     *  and is not. Asks the connector nothing, the status line reads this twice a second. */
    fun unifiedPushMissing(accountId: String, protocol: MailProtocol?): Boolean {
        val state = store.load(accountId)
        return RelayPush.unifiedPushMissing(
            protocol,
            state?.relayRequested == true,
            state?.status ?: UpStatus.NONE,
            isPending(accountId),
        )
    }

    /** Saved distributor's package name while this account rides UnifiedPush (status line). */
    fun distributorLabel(): String? = UnifiedPush.getAckDistributor(context)
        ?: UnifiedPush.getSavedDistributor(context)

    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** Drive the account towards active: register when none/failed, re-register when a pending
     *  state went stale (a lazy watchdog, no timers). The decision is [RelayPush.shouldRegister]. */
    @Synchronized
    fun ensureRegistered(credentials: AccountCredentials) {
        // Only the login holds a PushSubscription; its sub-accounts (#31) are fanned to on delivery.
        if (accountStore.account(credentials.id)?.isLinked == true) return
        // Read, don't create: an IMAP account nobody asked a relay for must leave no trace here,
        // and must not reach ensureDistributor(), whose picker is this subsystem's only UI.
        val known = store.load(credentials.id)
        if (!RelayPush.mayUsePush(credentials.protocol, known?.relayRequested ?: false)) return
        // ensureDistributor() runs before the registration window: its picker is the one piece of
        // UI here, and behind shouldRegister a failed account cannot reach it for the cooldown.
        if (!ensureDistributor()) return
        if (!RelayPush.shouldRegister(
                protocol = credentials.protocol,
                relayRequested = known?.relayRequested ?: false,
                status = known?.status ?: UpStatus.NONE,
                statusSinceMillis = known?.statusSinceMillis ?: 0L,
                now = System.currentTimeMillis(),
            )
        ) {
            return
        }
        val state = store.getOrCreate(credentials.id)
        store.save(credentials.id, state.copy(status = UpStatus.REGISTERING, statusSinceMillis = System.currentTimeMillis()))
        val relay = credentials.protocol != MailProtocol.JMAP
        scope.launch {
            // VAPID (RFC 9749) when the server advertises it. A relay account must not ask:
            // pushVapidKey is a JMAP call, and its host speaks IMAP.
            val vapid = if (relay) null else runCatching { repo.pushVapidKey(credentials) }.getOrNull()
            UnifiedPush.register(context, credentials.id, null, vapid)
        }
    }

    /** Asked in person: it lifts a refused picker, [RelayPush.onRelayRequested] clears the status. */
    fun requestRelayAddress(credentials: AccountCredentials) {
        synchronized(this) {
            val state = store.getOrCreate(credentials.id)
            store.save(credentials.id, RelayPush.onRelayRequested(state, System.currentTimeMillis()))
        }
        allowDistributorChoice()
        ensureRegistered(credentials)
    }

    /** The user withdrew the relay address: forget the endpoint, then unregister the instance. */
    fun dropRelayAddress(credentials: AccountCredentials) {
        synchronized(this) {
            val state = store.load(credentials.id) ?: return
            if (state.subscriptionId != null) {
                Log.w(TAG, "dropRelayAddress on an account holding a subscription — ignored: ${credentials.id}")
                return
            }
            store.save(
                credentials.id,
                state.copy(
                    endpoint = null,
                    subscriptionId = null,
                    status = UpStatus.NONE,
                    statusSinceMillis = System.currentTimeMillis(),
                    relayRequested = false,
                    lastDeliveryMillis = 0,
                ),
            )
        }
        UnifiedPush.unregister(context, credentials.id)
        onTransportStateChanged?.invoke()
    }

    fun relayRequested(accountId: String): Boolean = store.load(accountId)?.relayRequested == true

    /** The published relay address, or null unless there is a live one to show.
     *  [RelayPush.publishedAddress] holds the rule. */
    fun relayEndpoint(accountId: String): String? = RelayPush.publishedAddress(store.load(accountId))

    fun relayLastDeliveryMillis(accountId: String): Long = store.load(accountId)?.lastDeliveryMillis ?: 0

    /** Is this account served by an armed relay, one the user asked for, holding no PushSubscription. */
    fun relayArmed(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return state.relayRequested && state.subscriptionId == null && state.status == UpStatus.ACTIVE
    }

    fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {
        val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
        if (syncSwitchedOff(accountId)) return
        scope.launch {
            // The connector redelivers the endpoint on every register, so the no-change case has to
            // be recognised; a relay account stops at its own state. Read, decide and write in
            // one block, under ensureRegistered's monitor: read outside the lock and a withdrawal
            // landing in between is overwritten, the removed address coming back published.
            val (prev, outcome) = synchronized(this@UnifiedPushManager) {
                val current = store.getOrCreate(accountId)
                val decided = RelayPush.endpointOutcome(
                    credentials.protocol, current, endpoint.url, System.currentTimeMillis(),
                )
                if (decided is EndpointOutcome.Relay) store.save(accountId, decided.state)
                current to decided
            }
            when (outcome) {
                EndpointOutcome.Ignore -> return@launch
                is EndpointOutcome.Relay -> {
                    Log.i(TAG, "Relay address published for $accountId")
                    onTransportStateChanged?.invoke()
                    return@launch
                }
                EndpointOutcome.Subscribe -> Unit
            }
            // Before the destroy on purpose: a verification that is merely slow still has a live
            // subscription to land on, and onMessage's Verification arm takes it to active alone.
            if (RelayPush.createBudgetSpent(prev, endpoint.url)) {
                Log.w(TAG, "PushSubscription never verified after ${prev.createAttempts} creations for $accountId")
                markFailed(accountId)
                return@launch
            }
            if (prev.subscriptionId != null) {
                // Rotation or a lapsed verification: the old subscription is dead, drop it.
                runCatching { repo.destroyPushSubscription(credentials, prev.subscriptionId) }
            }
            val keys = endpoint.pubKeySet
            if (keys == null) {
                // Without WebPush keys the server can't encrypt; stay on the direct connection.
                Log.w(TAG, "UnifiedPush endpoint without WebPush keys for $accountId")
                markFailed(accountId)
                return@launch
            }
            runCatching {
                val created = repo.createPushSubscription(
                    credentials,
                    PushSubscription(
                        deviceClientId = prev.deviceClientId,
                        url = endpoint.url,
                        // RFC 8291 wants base64url; the connector's encoding varies by version,
                        // and Stalwart rejects the standard alphabet outright.
                        keys = PushKeys(p256dh = base64Url(keys.pubKey), auth = base64Url(keys.auth)),
                        expires = utc(System.currentTimeMillis() + EXPIRES_MS),
                        types = listOf("Email"),
                    ),
                )
                store.save(
                    accountId,
                    prev.copy(
                        endpoint = endpoint.url,
                        subscriptionId = created.id,
                        status = UpStatus.VERIFYING,
                        statusSinceMillis = System.currentTimeMillis(),
                        createAttempts = RelayPush.createAttemptsFor(prev, endpoint.url),
                        expiresAtMillis = parseUtc(created.expires)
                            ?: (System.currentTimeMillis() + EXPIRES_MS),
                    ),
                )
                Log.i(TAG, "PushSubscription created for $accountId, awaiting verification")
            }.onFailure {
                Log.w(TAG, "PushSubscription create failed for $accountId", it)
                markFailed(accountId)
            }
        }
    }

    fun onMessage(accountId: String, message: PushMessage) {
        when (val payload = decodePayload(accountId, message)) {
            is PushMessagePayload.Verification -> {
                // A relay address is a topic anyone may post to. Without this guard a stranger
                // posts {"@type":"PushVerification"} and it is carried into verifyPushSubscription
                // with the account's credentials against an IMAP host: the failure calls markFailed,
                // so that stranger switches the relay off for fifteen minutes at will.
                //
                // The question is the protocol, not the stored subscriptionId, which is written
                // only once createPushSubscription has returned, a guard on it would depend on
                // which of two messages wins a race.
                val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
                if (credentials.protocol != MailProtocol.JMAP) {
                    Log.w(TAG, "Push verification on a relay account — ignored: $accountId")
                    return
                }
                if (syncSwitchedOff(accountId)) return
                scope.launch {
                    // Promptly: servers time the round-trip out (Stalwart: ~1 min).
                    runCatching {
                        repo.verifyPushSubscription(credentials, payload.pushSubscriptionId, payload.verificationCode)
                    }.onSuccess {
                        store.save(
                            accountId,
                            store.getOrCreate(accountId).copy(
                                status = UpStatus.ACTIVE,
                                statusSinceMillis = System.currentTimeMillis(),
                                createAttempts = 0,
                            ),
                        )
                        Log.i(TAG, "UnifiedPush ACTIVE for $accountId")
                        onTransportStateChanged?.invoke()
                    }.onFailure {
                        Log.w(TAG, "PushSubscription verify failed for $accountId", it)
                        markFailed(accountId)
                    }
                }
            }
            // One PushSubscription per login; fan the wake out to the login and every sub-account
            // sharing it (#31). For linked sub-accounts this is a catch-up ride, their guaranteed
            // delivery being the periodic poll.
            is PushMessagePayload.Change -> {
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
            // Unknown/undecryptable payload: treat as a bare wake signal, still fetch.
            null -> {
                // Log.i, not d: release builds strip d/v, and this is the evidence when push
                // misbehaves on a distributor I cannot test.
                Log.i(TAG, "Unparsed push payload (${message.content.size}B, decrypted=${message.decrypted})")
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
        }
    }

    /** Mail really came through this account's endpoint, the only thing that arms a relay
     *  ([RelayPush.onDelivery] holds the rule). Called from the two arms that carry mail, never
     *  from Verification. */
    private fun recordDelivery(accountId: String) {
        // Read, decide and write under ensureRegistered's monitor. This is the only write that
        // arms the transport, and it runs on the connector's thread while a rotation writes from
        // Dispatchers.IO: outside the lock, a payload on the old address persists over the
        // rotation. The callback below stays outside, it re-enters the manager.
        val update = synchronized(this) {
            val prev = store.load(accountId) ?: return
            val decided = RelayPush.onDelivery(prev, System.currentTimeMillis()) ?: return
            store.save(accountId, decided.state)
            decided
        }
        // Only the crossing from published to active: this callback rebuilds every connection.
        if (update.armed) {
            Log.i(TAG, "UnifiedPush relay armed by a delivery for $accountId")
            onTransportStateChanged?.invoke()
        }
    }

    /** Wake [loginId] and each sub-account riding its subscription (#31), each on its own sync switch, never the login's. */
    private fun enqueueForLogin(loginId: String) {
        (listOf(loginId) + accountStore.linkedAccounts(loginId).map { it.id }).distinct()
            .filterNot { syncSwitchedOff(it) }
            .forEach { PushFetchWorker.enqueue(context, it) }
    }

    /** Extract the JMAP payload from a push delivery. Straight parse first, the connector having
     *  already decrypted or the server pushed plaintext; Stalwart then needs a second step, since it
     *  base64url-encodes the whole aes128gcm blob instead of POSTing raw octets (RFC 8030). */
    private fun decodePayload(accountId: String, message: PushMessage): PushMessagePayload? {
        val text = message.content.toString(Charsets.UTF_8)
        PushMessagePayload.parse(text)?.let { return it }
        if (message.decrypted) return null
        return runCatching {
            val binary = android.util.Base64.decode(
                text.trim(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
            DefaultKeyManager(context).decrypt(accountId, binary)
                ?.toString(Charsets.UTF_8)
                ?.let { PushMessagePayload.parse(it) }
        }.getOrNull()
    }

    fun onRegistrationFailed(accountId: String, reason: FailedReason) {
        Log.w(TAG, "UnifiedPush registration failed for $accountId: $reason")
        markFailed(accountId)
    }

    fun onUnregistered(accountId: String) {
        Log.i(TAG, "UnifiedPush unregistered for $accountId")
        // Failed, not none: none re-registers instantly through the transport callback and
        // ping-pongs against a distributor that keeps answering with unregister.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    /** Renew before expiry; both arms re-read under the monitor, their snapshot predating a round-trip. */
    suspend fun renewIfNeeded(credentials: AccountCredentials) {
        val state = store.load(credentials.id) ?: return
        if (state.status != UpStatus.ACTIVE || state.subscriptionId == null) return
        if (state.expiresAtMillis - System.currentTimeMillis() > RENEW_MARGIN_MS) return
        runCatching {
            val applied = repo.renewPushSubscription(
                credentials, state.subscriptionId, utc(System.currentTimeMillis() + EXPIRES_MS),
            )
            val renewedUntil = parseUtc(applied) ?: (System.currentTimeMillis() + EXPIRES_MS)
            val renewed = synchronized(this) {
                val current = store.load(credentials.id) ?: return@synchronized null
                val merged = RelayPush.onRenewed(current, state.subscriptionId, renewedUntil)
                if (merged != null) store.save(credentials.id, merged)
                merged
            }
            if (renewed == null) {
                Log.i(TAG, "PushSubscription renewed for a subscription ${credentials.id} no longer holds")
            }
        }.onFailure {
            Log.w(TAG, "PushSubscription renew failed for ${credentials.id} — re-registering", it)
            synchronized(this) {
                val current = store.load(credentials.id) ?: return@synchronized
                val merged = RelayPush.onRenewFailed(current, System.currentTimeMillis())
                if (merged != null) store.save(credentials.id, merged)
            }
            ensureRegistered(credentials)
        }
    }

    /** Sign-out: unregister the instance and best-effort destroy the subscription. */
    fun teardown(credentials: AccountCredentials) {
        val state = store.load(credentials.id)
        store.clear(credentials.id)
        UnifiedPush.unregister(context, credentials.id)
        val subscriptionId = state?.subscriptionId ?: return
        scope.launch { runCatching { repo.destroyPushSubscription(credentials, subscriptionId) } }
    }

    /** Sync switched off: give up this account's own subscription on both sides, decided under the
     *  monitor and done outside it. The caller is PushController.apply(), so no callback from here. */
    fun tearDownAfterSyncOff(credentials: AccountCredentials) {
        val due = synchronized(this) {
            val state = store.load(credentials.id)
            RelayPush.shouldTearDown(
                isLinked = accountStore.account(credentials.id)?.isLinked == true,
                hasPushState = state != null,
                linkedAccountStillOn = accountStore.keepsPushForLinkedAccount(credentials.id),
                relayRequested = state?.relayRequested == true,
            )
        }
        if (!due) return
        teardown(credentials)
    }

    /** Unregister is not instant: a late endpoint or verification rebuilds the subscription just given up (#204). */
    private fun syncSwitchedOff(accountId: String): Boolean {
        if (accountStore.notificationsEnabled(accountId)) return false
        Log.i(TAG, "UnifiedPush event for an account with sync off — ignored: $accountId")
        return true
    }

    private fun markFailed(accountId: String) {
        // Under the monitor, like every state write not inside a coroutine's own
        // read-decide-write: this one is called from failed coroutines and from the connector.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    private fun unregisterOrphan(accountId: String) {
        Log.w(TAG, "UnifiedPush event for unknown account $accountId — unregistering")
        store.clear(accountId)
        UnifiedPush.unregister(context, accountId)
    }

    private fun credentialsFor(accountId: String): AccountCredentials? =
        accountStore.allCredentials().firstOrNull { it.id == accountId }

    /** Re-encode any base64 variant as base64url with padding: Stalwart's decoder requires canonical
     *  padding and rejects the RFC 7515-style unpadded form. */
    private fun base64Url(value: String): String {
        val bytes = try {
            android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )
    }

    private fun utc(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun parseUtc(utc: String?): Long? =
        utc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        private const val TAG = "UnifiedPush"

        /** Requested subscription lifetime; the server may cap it (we store what it applied). */
        private const val EXPIRES_MS = 7L * 24 * 60 * 60 * 1000

        /** Renew when expiring within two periodic-worker cycles. */
        private const val RENEW_MARGIN_MS = 2 * 30 * 60 * 1000L

    }
}
