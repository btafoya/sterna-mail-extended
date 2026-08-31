package app.sterna.push

import android.content.Context
import android.util.Log
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.MailProtocol
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

/**
 * The UnifiedPush transport state machine (issue #17), one instance per app.
 * UnifiedPush instance ids ARE local account ids; one JMAP PushSubscription per
 * account credential. The connector owns all WebPush crypto: it generates the
 * P-256 keypair + auth secret per registration and hands us decrypted payloads —
 * Sterna implements no crypto and stores no keys.
 *
 * Never registers when no distributor is installed, so without one no UnifiedPush
 * code path runs at all and the direct connections behave exactly as before.
 */
class UnifiedPushManager(
    private val context: Context,
    private val accountStore: AccountStore,
    private val repo: MailRepository,
    private val store: UnifiedPushStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Invoked whenever an account's transport state changes (registered, verified,
     * failed, unregistered) so the owner can re-evaluate connections. Wired by the
     * app container; kept as a callback to avoid a circular dependency.
     */
    var onTransportStateChanged: (() -> Unit)? = null

    private val _needsDistributorChoice = MutableStateFlow(false)

    /** True while >1 distributor is installed and none chosen — the UI shows the picker. */
    val needsDistributorChoice: StateFlow<Boolean> = _needsDistributorChoice.asStateFlow()

    /** A distributor is installed (regardless of whether one is saved yet). */
    fun distributorInstalled(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Make sure a distributor is chosen. One installed → saved silently; several and
     * none saved → raise the picker (only case with UI, per the UX rules). False while
     * no usable choice exists.
     */
    private fun ensureDistributor(): Boolean {
        if (UnifiedPush.getAckDistributor(context) != null) return true
        if (UnifiedPush.getSavedDistributor(context) != null) return true
        val distributors = UnifiedPush.getDistributors(context)
        return when {
            distributors.isEmpty() -> false
            distributors.size == 1 -> {
                UnifiedPush.saveDistributor(context, distributors.single())
                true
            }
            else -> {
                _needsDistributorChoice.value = true
                false
            }
        }
    }

    /** The user picked [packageName] in the distributor dialog. */
    fun distributorChosen(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        _needsDistributorChoice.value = false
        onTransportStateChanged?.invoke()
    }

    /** Dialog dismissed without a choice: stay on direct connections (re-asked next apply). */
    fun dismissDistributorChoice() {
        _needsDistributorChoice.value = false
    }

    // A linked sub-account has no subscription of its own — it rides its login's one PushSubscription
    // (issue #31), so its transport status is the login's.
    fun isActive(accountId: String): Boolean =
        store.load(accountStore.account(accountId)?.loginKey() ?: accountId)?.status == UpStatus.ACTIVE

    /**
     * Reality check before transport decisions: the saved distributor was uninstalled
     * (or unsaved) while accounts were riding it — mark them FAILED so their direct
     * connections resume. Called by the controller/worker themselves, hence no
     * [onTransportStateChanged] here.
     */
    fun reconcileDistributorPresence() {
        val saved = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        if (saved != null && saved in UnifiedPush.getDistributors(context)) return
        // The connector lookups above stay OUTSIDE the monitor (they are binder calls); only the
        // read-decide-write below is protected, like every other state write in this file.
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

    /** Registration/verification in flight and not stale — the EventSource stays up meanwhile. */
    fun isPending(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&
            System.currentTimeMillis() - state.statusSinceMillis < RelayPush.PENDING_GRACE_MS
    }

    /** Saved distributor's package name while this account rides UnifiedPush (status line). */
    fun distributorLabel(): String? = UnifiedPush.getAckDistributor(context)
        ?: UnifiedPush.getSavedDistributor(context)

    /** Installed distributor package names, for the picker dialog. */
    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /**
     * Drive the account towards ACTIVE: register when NONE/FAILED, re-register when a
     * pending state went stale (lazy watchdog — no timers). No-op for an IMAP account whose
     * owner never asked for a relay address, and whenever no distributor is usable.
     *
     * The decision itself is [RelayPush.shouldRegister] — it is pure and tested; everything
     * left here is side effects.
     */
    // Synchronized: apply() runs from several call sites in quick succession (account
    // switch, transport callbacks) — without it the load/save race registers many times.
    @Synchronized
    fun ensureRegistered(credentials: AccountCredentials) {
        // Only the login holds a PushSubscription; its sub-accounts (issue #31) are served by it and
        // fanned to on delivery, so registering them would create redundant subscriptions.
        if (accountStore.account(credentials.id)?.isLinked == true) return
        // Read, don't create: an IMAP account nobody asked a relay for must leave no trace here —
        // and must not reach ensureDistributor(), whose picker is the one piece of UI in this
        // subsystem. Hence that ONE question runs first, on a possibly absent record.
        val known = store.load(credentials.id)
        if (!RelayPush.mayUsePush(credentials.protocol, known?.relayRequested ?: false)) return
        // ⛔ And ensureDistributor() runs BEFORE the registration window, not after it. It is not
        // only a check: it silently enrols a single installed distributor and it is what raises the
        // picker. Behind shouldRegister, an account that had just been marked FAILED — which is
        // precisely what reconcileDistributorPresence does when the distributor is uninstalled —
        // stopped reaching it for the whole 15-minute cooldown, so a freshly installed replacement
        // went unseen. The window itself is asked next, and only decides whether to register.
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
            // VAPID (RFC 9749) when the server advertises it; Stalwart doesn't yet. A relay
            // account must NOT ask: pushVapidKey is a JMAP call, and its host speaks IMAP.
            val vapid = if (relay) null else runCatching { repo.pushVapidKey(credentials) }.getOrNull()
            UnifiedPush.register(context, credentials.id, null, vapid)
        }
    }

    /**
     * The user asked for a relay address on this account (settings).
     *
     * ⛔ [RelayPush.onRelayRequested] puts the status back to NONE, and that is the correction, not
     * a detail: "Remove address" unregisters the instance, the distributor answers, [onUnregistered]
     * writes FAILED stamped now — so tapping "Get an address" straight afterwards fell inside the
     * 15-minute FAILED cooldown and did nothing at all, with not a word on screen. That cooldown
     * exists to stop the AUTOMATIC loop (a failure calls onTransportStateChanged → apply() → back
     * into shouldRegister); a deliberate tap is not a loop, and every automatic path still rides it.
     *
     * Under the same monitor as [ensureRegistered]: [onNewEndpoint] persists from Dispatchers.IO.
     */
    fun requestRelayAddress(credentials: AccountCredentials) {
        synchronized(this) {
            val state = store.getOrCreate(credentials.id)
            store.save(credentials.id, RelayPush.onRelayRequested(state, System.currentTimeMillis()))
        }
        ensureRegistered(credentials)
    }

    /**
     * The user withdrew the relay address: forget the endpoint, then unregister the instance.
     *
     * ⛔ It talks to no server, which is only true of an account that never held a
     * PushSubscription — so an account that DOES hold one is refused here rather than served
     * quietly. This is [teardown]'s twin minus the destroy call: run on a JMAP account it would
     * erase the subscription id locally and leave the subscription alive on the server, posting to
     * an endpoint that no longer exists, with a second one created at the next registration. The
     * screen only offers it on IMAP; this is a public function, and that is not a guard.
     *
     * Under the same monitor as [ensureRegistered], and the connector call outside it: holding the
     * lock across an IPC would block every registration for its duration.
     */
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

    /** Has the user asked for a relay address on this account? */
    fun relayRequested(accountId: String): Boolean = store.load(accountId)?.relayRequested == true

    /**
     * The published relay address, or null unless there is a live one to show —
     * [RelayPush.publishedAddress] holds the rule and is executed by RelayPushTest.
     */
    fun relayEndpoint(accountId: String): String? = RelayPush.publishedAddress(store.load(accountId))

    /** When a payload last arrived through the relay endpoint (0 = never). */
    fun relayLastDeliveryMillis(accountId: String): Long = store.load(accountId)?.lastDeliveryMillis ?: 0

    /**
     * Is this account served by an ARMED relay — one the user asked for, that holds no
     * PushSubscription of its own, and through which a wake has really arrived?
     *
     * ⛔ One question over ONE record, and it lives here rather than in the caller precisely so it
     * cannot be reassembled from three reads that answer about three different moments. All three
     * facts carry: [UpAccountState.relayRequested] because the subsystem enrols a distributor
     * silently and an address may exist that nobody asked for; a null [UpAccountState.subscriptionId]
     * because a JMAP account's ACTIVE is the server's PushVerification, not a delivery; and ACTIVE
     * itself because PUBLISHED means an address published and never posted to — that account is
     * still served by its IMAP connection, and the screen must go on saying so.
     */
    fun relayArmed(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return state.relayRequested && state.subscriptionId == null && state.status == UpStatus.ACTIVE
    }

    /** The distributor delivered (or rotated) this instance's endpoint. */
    fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {
        val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
        scope.launch {
            // The connector redelivers the endpoint on every register, so the no-change case
            // has to be recognised; and a relay account stops at its own state, since there is
            // no subscription to create against an IMAP host. Both are [RelayPush.endpointOutcome].
            //
            // ⛔ Read, decide and write in ONE block, under the same monitor as ensureRegistered.
            // This runs on Dispatchers.IO while the settings screen can be writing: read the state
            // outside the lock and a withdrawal landing in between is overwritten by a state built
            // on a 'prev' from before it — the address the user just removed comes back, published.
            // The lock covers state only; the network calls below are deliberately outside it.
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
            if (prev.subscriptionId != null) {
                // Rotation or a lapsed verification: the old subscription is dead — drop it.
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
                        // RFC 8291 wants base64url; the connector's encoding varies by
                        // version, and Stalwart rejects the standard alphabet outright
                        // ("Failed to decode keys") — normalize before sending.
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

    /** A payload arrived through the endpoint (connector-decrypted, or Stalwart-quirky). */
    fun onMessage(accountId: String, message: PushMessage) {
        when (val payload = decodePayload(accountId, message)) {
            is PushMessagePayload.Verification -> {
                // ⛔ A relay address is a topic ANYONE may post to. Without this guard, a stranger
                // who knows the address posts {"@type":"PushVerification"} and the payload is
                // carried straight into verifyPushSubscription → jmapAuth with the account's
                // credentials, against a host that speaks IMAP: the failure calls markFailed, so
                // that stranger switches the relay off for fifteen minutes, silently, as often as
                // they like — and on an OAuth account a real token refresh is burnt first.
                //
                // ⛔ The question is the PROTOCOL, not the stored subscriptionId. That id is
                // written only once createPushSubscription has RETURNED, so a first verification
                // arriving before that return would have been dropped in silence and the account
                // left VERIFYING until it went stale — a guard that depends on which of two
                // messages wins a race. The protocol does not race: JMAP is the only thing that
                // can hold a subscription to verify, and a relay account, which is all this guard
                // was ever for, is still refused. No payload logged.
                val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
                if (credentials.protocol != MailProtocol.JMAP) {
                    Log.w(TAG, "Push verification on a relay account — ignored: $accountId")
                    return
                }
                scope.launch {
                    // Promptly: servers time the round-trip out (Stalwart: ~1 min).
                    runCatching {
                        repo.verifyPushSubscription(credentials, payload.pushSubscriptionId, payload.verificationCode)
                    }.onSuccess {
                        store.save(
                            accountId,
                            store.getOrCreate(accountId).copy(status = UpStatus.ACTIVE, statusSinceMillis = System.currentTimeMillis()),
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
            // sharing it (issue #31). The server only pushes changes for the login's member
            // accounts (never ACL-shared sub-accounts — verified on Stalwart), so for linked
            // sub-accounts this fan-out is a catch-up ride on the login's wakes; their own
            // guaranteed delivery is [MailFetchWorker]'s periodic poll.
            is PushMessagePayload.Change -> {
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
            // Unknown/undecryptable payload: treat as a bare wake signal — still fetch.
            null -> {
                // Log.i, not d: release builds strip d/v, and when push misbehaves on a distributor
                // I cannot test, this line (size + decrypted flag only, no payload) is the evidence.
                Log.i(TAG, "Unparsed push payload (${message.content.size}B, decrypted=${message.decrypted})")
                recordDelivery(accountId)
                enqueueForLogin(accountId)
            }
        }
    }

    /**
     * Mail really came through this account's endpoint — the only thing that arms a relay
     * ([RelayPush.onDelivery] holds the rule, including "not for JMAP": the Verification arm above
     * owns that path and must not be short-circuited by a message). Called from the two arms that
     * carry mail, never from Verification.
     */
    private fun recordDelivery(accountId: String) {
        // ⛔ Read, decide and write under the SAME monitor as ensureRegistered and onNewEndpoint.
        // This is the ONLY write that arms the transport, and it runs on the connector's thread
        // while an endpoint rotation writes from Dispatchers.IO. Outside the lock: the rotation
        // persists {new address, PUBLISHED, never woken}, a payload that arrived on the OLD address
        // and read its record before that persists {old address, ACTIVE, woken just now} on top —
        // IMAP IDLE dropped for an address that is already dead, with "last wake: just now"
        // underneath it on screen. The callback below stays outside: it re-enters the manager.
        val update = synchronized(this) {
            val prev = store.load(accountId) ?: return
            val decided = RelayPush.onDelivery(prev, System.currentTimeMillis()) ?: return
            store.save(accountId, decided.state)
            decided
        }
        // Only the PUBLISHED → ACTIVE crossing: this callback rebuilds every connection, so
        // invoking it per message would be a storm.
        if (update.armed) {
            Log.i(TAG, "UnifiedPush relay armed by a delivery for $accountId")
            onTransportStateChanged?.invoke()
        }
    }

    /** Wake the login [loginId] and every sub-account riding its subscription (issue #31). */
    private fun enqueueForLogin(loginId: String) {
        (listOf(loginId) + accountStore.linkedAccounts(loginId).map { it.id }).distinct()
            .forEach { PushFetchWorker.enqueue(context, it) }
    }

    /**
     * Extract the JMAP payload from a push delivery. Straight parse first (the
     * connector already decrypted, or the server pushed plaintext). Stalwart then
     * needs a second step: it base64url-encodes the whole aes128gcm blob instead of
     * POSTing raw octets (RFC 8030), so the connector never recognizes it as
     * encrypted — decode it ourselves and decrypt through the connector's key
     * manager (its keys, its API).
     */
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
        // FAILED, not NONE: NONE re-registers instantly through the transport callback,
        // which ping-pongs against a distributor that keeps answering with unregister
        // (e.g. the user revoked Sterna inside it). FAILED rides the retry cooldown.
        // Under the monitor: this arrives on the connector's thread, against the settings screen.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    /**
     * Renew the subscription when it expires within two worker cycles; called from the
     * periodic worker. A failed renewal falls back to a full re-registration.
     */
    suspend fun renewIfNeeded(credentials: AccountCredentials) {
        val state = store.load(credentials.id) ?: return
        if (state.status != UpStatus.ACTIVE || state.subscriptionId == null) return
        if (state.expiresAtMillis - System.currentTimeMillis() > RENEW_MARGIN_MS) return
        runCatching {
            val applied = repo.renewPushSubscription(
                credentials, state.subscriptionId, utc(System.currentTimeMillis() + EXPIRES_MS),
            )
            store.save(
                credentials.id,
                state.copy(expiresAtMillis = parseUtc(applied) ?: (System.currentTimeMillis() + EXPIRES_MS)),
            )
        }.onFailure {
            Log.w(TAG, "PushSubscription renew failed for ${credentials.id} — re-registering", it)
            store.save(credentials.id, state.copy(status = UpStatus.NONE))
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

    private fun markFailed(accountId: String) {
        // Under the monitor like every other state write that is not inside a coroutine's own
        // read-decide-write: this one is called from failed coroutines and from the connector.
        synchronized(this) {
            store.load(accountId)?.let {
                store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
            }
        }
        onTransportStateChanged?.invoke()
    }

    /** An instance we no longer have an account for (removed account) — clean up. */
    private fun unregisterOrphan(accountId: String) {
        Log.w(TAG, "UnifiedPush event for unknown account $accountId — unregistering")
        store.clear(accountId)
        UnifiedPush.unregister(context, accountId)
    }

    private fun credentialsFor(accountId: String): AccountCredentials? =
        accountStore.allCredentials().firstOrNull { it.id == accountId }

    /**
     * Re-encode any base64 variant (url-safe or standard, padded or not) as base64url
     * WITH padding: Stalwart's decoder (rust base64 URL_SAFE engine) requires canonical
     * padding and rejects the RFC 7515-style unpadded form outright; padded input is
     * accepted by lenient decoders elsewhere.
     */
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
