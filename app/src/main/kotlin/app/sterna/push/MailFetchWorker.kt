package app.sterna.push

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.AppContainer
import app.sterna.container
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.keepsPushForLinkedAccount
import app.sterna.core.data.mail.ServerContactsSync
import app.sterna.core.jmap.Jmap
import java.util.concurrent.TimeUnit

/**
 * Fallback new-mail poll for when live push is down (#11). Android 15+ budgets long-running
 */
class MailFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Coverage matrix (#16/#17): a JMAP EventSource open for this account covers its whole
        // watched set, IMAP IDLE only the INBOX. A UnifiedPush-active account gets the same safety
        // poll, its endpoint being able to die silently with the status still active.
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        val keptForSubAccount = watched
            .filter { !store.notificationsEnabled(it.id) && store.keepsPushForLinkedAccount(it.id) }
        for (credentials in keptForSubAccount) {
            runCatching { up.renewIfNeeded(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush renew check failed for ${credentials.id}", it) }
        }
        // Until this line the cycle ended on the same SUCCESS as one that fetched everything. Four
        // different things arrive here and only one is a choice, so the three facts that separate
        // them are read here and handed to the line.
        if (accounts.isEmpty()) {
            Log.i(
                TAG,
                PushController.nothingToPollLine(
                    stored = store.accounts().size,
                    candidates = watched.size,
                    accountsUnreadable = store.accountsUnreadable(),
                ),
            )
            syncServerContactsOfEveryAccount(container)
            return Result.success()
        }
        for (credentials in accounts) {
            // Failed registrations retry on this cadence (cooldown inside the manager).
            runCatching { up.ensureRegistered(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush register check failed for ${credentials.id}", it) }
            runCatching { up.renewIfNeeded(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush renew check failed for ${credentials.id}", it) }
            if (up.isActive(credentials.id)) {
                runCatching { FetchAndNotify.run(applicationContext, credentials) }
                    .onFailure { Log.w(TAG, "Safety poll failed for account ${credentials.id}", it) }
                continue
            }
            // A linked sub-account gets no live push of its own (#31): the server only delivers
            // StateChanges for the login's member accounts, so its EventSource does not cover it.
            val linked = store.account(credentials.id)?.isLinked == true
            // Asked per account, never "is the service up": the service outlives the failure of
            // every connection it holds (#61). False when it is down or the account is unknown, so
            // the default is to poll, a wasted delta costs a request, a wrong skip costs mail.
            val connected = PushService.isConnected(credentials.id)
            val pollInbox = shouldPollInbox(connected, linked)
            // Read once, so the count the line reports is the one the decision was taken on.
            val watchedFolders = store.watchedFolders(credentials.id)
            val pollExtras = hasExtrasToPoll(
                isImap = credentials.protocol == MailProtocol.IMAP,
                watchedFolders = watchedFolders,
            )
            // The widest of the silent exits: on JMAP [hasExtrasToPoll] is false by construction,
            // so an account we hold a handle for is skipped whole.
            if (!pollInbox && !pollExtras) {
                Log.i(TAG, PushController.pollSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))
                continue
            }
            // The half-skip, and the one that costs mail if the handle is lying (IMAP only). Said
            // before the fetch, so the order in logcat is the order of events.
            if (!pollInbox) {
                Log.i(TAG, PushController.inboxSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))
            }
            runCatching {
                FetchAndNotify.run(applicationContext, credentials, includeInbox = pollInbox)
            }.onFailure { Log.w(TAG, "Fallback fetch failed for account ${credentials.id}", it) }
        }
        syncServerContactsOfEveryAccount(container)
        return Result.success()
    }

    private suspend fun syncServerContactsOfEveryAccount(container: AppContainer) {
        val stored = container.accountStore.accounts()
        val readable = stored.mapNotNull { container.accountStore.credentials(it.id) }
        addressBookLeftOutLine(stored, readable.map { it.id })?.let { Log.w(TAG, it) }
        for (credentials in readable) {
            runCatching { container.mailRepository.accounts.syncServerContacts(credentials) }
                .onFailure { Log.w(TAG, "Address book refresh failed for account ${credentials.id}", it) }
                .onSuccess { outcome -> serverContactsLine(credentials.id, outcome)?.let { Log.i(TAG, it) } }
        }
    }

    companion object {
        private const val TAG = "MailFetchWorker"
        private const val WORK_NAME = "mail-fetch-fallback"

        /** Idempotent: keeps the existing schedule if one is already enqueued. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailFetchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Does the fallback cycle owe this account's INBOX a poll?
 */
internal fun shouldPollInbox(pushConnected: Boolean, linked: Boolean): Boolean = !pushConnected || linked

/**
 * With the inbox covered, is there anything left worth a cycle? Only IMAP's watched extras, IDLE
 */
internal fun hasExtrasToPoll(isImap: Boolean, watchedFolders: Set<String>): Boolean =
    isImap && watchedFolders.isNotEmpty()

internal fun serverContactsLine(accountId: String, outcome: ServerContactsSync): String? = when (outcome) {
    ServerContactsSync.Imap -> null
    ServerContactsSync.Linked ->
        "Address book not read for account $accountId: a linked sub-account is never given its login's book"
    is ServerContactsSync.NoAddressBook ->
        if (outcome.capabilityListed) {
            "Address book not read for account $accountId: the session lists ${Jmap.CONTACTS_CAPABILITY} " +
                "but names no account for it"
        } else {
            "Address book not read for account $accountId: the session does not list ${Jmap.CONTACTS_CAPABILITY}"
        }
    is ServerContactsSync.Refreshed -> {
        val read = if (outcome.whole) {
            "whole book read, ${outcome.cards} cards"
        } else {
            "${outcome.cards} cards changed, ${outcome.destroyed} removed"
        }
        val state = if (outcome.stateKept) "state kept" else "no state kept, the next pass rereads it"
        "Address book of account $accountId: $read, ${outcome.addresses} addresses written, $state"
    }
}

internal fun addressBookLeftOutLine(stored: List<StoredAccount>, readable: List<String>): String? {
    val leftOut = stored.filter { it.protocol != MailProtocol.IMAP }.map { it.id }.filterNot { it in readable }
    if (leftOut.isEmpty()) return null
    return "Address book not read for account(s) ${leftOut.joinToString(", ")}: their credentials could not be read"
}
