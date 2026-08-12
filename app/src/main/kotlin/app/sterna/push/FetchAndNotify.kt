package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.AccountGoneException
import app.sterna.core.data.mail.accountGoneCause
import kotlinx.coroutines.flow.first

/**
 * The one fetch+notify pass shared by every delivery path (live [PushService],
 * periodic [MailFetchWorker]): refresh the account's watched folders and diff each
 * against its persisted per-folder baseline ([NewMailNotifier]). A single
 * implementation keeps the paths from drifting apart or double-notifying.
 * [onInboxRefreshed] is the foreground complement: the same diff over an inbox the
 * UI just refreshed itself (Codeberg #50).
 */
object FetchAndNotify {

    /**
     * @param includeInbox false when something else owns the inbox (IMAP IDLE is live;
     *   the worker then only needs the watched extras).
     * @param includeExtras false to refresh the inbox alone (an IMAP IDLE event — IDLE
     *   only ever signals the INBOX; watched extras belong to the periodic worker).
     * @param resetBaselines true to reseed the INBOX baseline silently (service
     *   (re)start: the user is opening the app and sees the inbox, so announcing its
     *   backlog would be noise). Watched extras always diff — the user does NOT see
     *   a Sieve folder at app-open, so mail that arrived while push was down must
     *   still notify (issue #16).
     */
    suspend fun run(
        context: Context,
        credentials: AccountCredentials,
        includeInbox: Boolean = true,
        includeExtras: Boolean = true,
        resetBaselines: Boolean = false,
    ) {
        // Outside the `try` because the catch below needs the store too: the line it prints has to
        // say WHY the account was not found, and only the store knows.
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        try {
            val extras = if (includeExtras) store.watchedFolders(credentials.id) else emptySet()
            val refreshes = container.mailRepository.refreshAccountFolders(
                credentials,
                extraFolderIds = extras,
                includeInbox = includeInbox,
                onMissing = { staleId ->
                    // Deleted/renamed server-side: the watch intent is gone — prune quietly.
                    store.setFolderWatched(credentials.id, staleId, watched = false)
                    NewMailNotifier.clear(context, credentials.id, staleId)
                },
            )
            // The inbox is always the first refresh when requested (see refreshAccountFolders).
            val inboxId = if (includeInbox) refreshes.firstOrNull()?.mailboxId else null
            if (inboxId != null) NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxId)
            val unarchiveOnReply = container.settingsRepository.unarchiveOnReply.first()
            // ⭐⛔ ONE budget for this ACCOUNT's whole pass, built HERE and not inside the loop: the
            // diff below runs once per folder, so a budget built per folder would give an account
            // watching three extra folders 4 × 6 reads and up to 4 × 4 s of blocking IMAP work
            // under its single mutex, on one radio wake-up. The second folder inherits what the
            // first one left, and a folder that arrives with nothing left reads nothing. Per
            // account, because that is the unit of the mutex and of the wake-up alike.
            val previewBudget = NotificationPreviews.PreviewBudget(System::currentTimeMillis)
            refreshes.forEach { folder ->
                val isInbox = folder.mailboxId == inboxId
                val folderName = if (isInbox) null else folder.name
                val hasBaseline = NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId)
                // Codeberg #50 (opt-in): BEFORE this pass advances OR reseeds the inbox baseline,
                // pull the archived members of threads that just received genuinely-new inbox
                // mail back into the Inbox, so the list the user lands on is already whole.
                // Keyed to the same baseline+age-floor diff as the notifications, so mail the
                // cache merely caught up on (scroll-back, re-sync) can never trigger it — and
                // run ahead of ANY seed, because the silent app-open/account-switch reseed
                // (resetBaselines) used to swallow a just-arrived reply into the baseline before
                // it was ever diffed: reply from a sibling account, switch back, and the new
                // conversation appeared with its archived members gone for good. The re-filed
                // members join the processed set: they enter the baseline with this pass (never
                // announced as "new" later), and the per-thread collapse keeps the new reply as
                // the one notification. Best-effort — a failed move must not cost the
                // notification (or the reseed).
                val returned = if (isInbox && hasBaseline && unarchiveOnReply) {
                    val threads = NewMailNotifier.newSince(context, credentials.id, folder.mailboxId, folder.emails)
                        .mapNotNull { it.threadId }
                        .toSet()
                    runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                // What the folder REMEMBERS: everything the pass read (not only what it hydrated for
                // the diff), plus the members it just re-filed. The two sets are different sizes on
                // purpose — see MailRepository.notifyRead.
                val remembered = folder.baselineIds + returned.map { it.id }
                if (seedsSilently(resetBaselines, isInbox, hasBaseline)) {
                    // First sight of a folder (or an explicit reset): seed silently instead of
                    // flooding notifications for its whole existing content.
                    NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)
                } else {
                    // The folder's departures ride along: what the server said left it during THIS
                    // refresh, so a message deleted from another client loses its banner (#134).
                    // Empty on IMAP and on the seed path above, which has no banner to take down.
                    // The folder's preview sources ride along too: on IMAP the opening line has to
                    // be READ, and the section to read it from is what THIS refresh's page already
                    // learned from the server. Dropped here, the notifier would have to ask the
                    // server to describe every message a second time — which is exactly what
                    // carrying it exists to avoid. Empty on JMAP, where the row has its own.
                    NewMailNotifier.notifyDiff(
                        context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered,
                        folder.departedIds,
                        folder.previewSources,
                        previewBudget,
                    )
                }
            }
        } catch (gone: AccountGoneException) {
            // The refusal MailRepository's guards throw when the account this pass writes for was
            // signed out mid-flight. It arrives here as a cancellation, and this is the single
            // funnel of the four delivery paths — PushFetchWorker, MailFetchWorker and PushService
            // each turn whatever comes out of this call into a Log.w/Log.e with a stack, and the
            // worker into two more wake-ups besides (Result.retry()). Every sign-out would print
            // an error for a gesture that SUCCEEDED, which is exactly what #130 took out of this
            // log. Dropped here, with one line saying so.
            //
            // ⛔ It must stay the FIRST arm: AccountGoneException IS a CancellationException, so
            // any arm above naming that type (or Throwable) takes it first and this one becomes
            // unreachable without a word.
            //
            // ⚠ WHAT the line claims is not this catch's to decide, because the exception carries
            // two different facts: `AccountStore.accounts()` answers an UNREADABLE blob with an
            // empty list too, so the guard fires with nobody having signed anything out. This line
            // used to assert "signed out" in both cases, which turned a storage failure into a
            // deliberate gesture in the logcat a reporter sends us. [accountGoneCause] answers,
            // from a FRESH `accountsUnreadable()` read — the blob is re-read on every call, so what
            // is printed is the state at the moment of the failure, not one captured earlier.
            //
            // Deliberately still Log.i, unreadable case included: the user-facing signal for that
            // state is the startup screen, not this log, and a second decision here (which level?)
            // is one more thing that can drift away from the sentence it accompanies.
            android.util.Log.i(
                "FetchAndNotify",
                "background pass: ${accountGoneCause(credentials.id, store.accountsUnreadable(), passKind = "pass")}, dropped",
                gone,
            )
        }
    }

    /**
     * Foreground counterpart of [run] for one just-refreshed inbox (Codeberg #50): the UI
     * list refresh syncs the cache directly, without a push/worker pass, so new mail it
     * lands sits outside the notifier baselines until the next pass — which, with the app
     * in the foreground, is exactly when a reply should pull its archived thread back.
     * No-op while unarchive-on-reply is OFF (the default foreground path never touches a
     * baseline) and for IMAP (no server thread ids). When ON this is a normal diff pass
     * over the cached inbox: unarchive on the genuinely-new diff, then advance the shared
     * baseline — through the usual notify-and-seed when the account notifies (whichever of
     * this hook or a concurrent push pass runs first announces; the other then diffs
     * empty), or a silent seed when the account's notifications are off (nothing may be
     * announced, but the trigger must stay exactly-once so a re-archive is respected).
     *
     * No departures travel from here (#134): this reads the CACHE, and a cache read states what
     * the folder holds, never what left it. The refresh that filled that cache reported its own
     * departures on its own path — this hook must not guess at them from an absence.
     */
    suspend fun onInboxRefreshed(context: Context, credentials: AccountCredentials, inboxMailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val container = (context.applicationContext as Application).container
        if (!container.settingsRepository.unarchiveOnReply.first()) return
        NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxMailboxId)
        // The same bounded read the push/worker pass gets ([MailRepository.notifyRead]): this hook
        // runs on EVERY foreground inbox refresh when unarchive-on-reply is on, and it writes the
        // SAME baseline, so the two passes must read and remember the same way. Whatever this one
        // announced-but-did-not-remember, the other would announce again.
        val read = container.mailRepository.notifyRead(credentials.id, inboxMailboxId)
        val emails = read.emails
        if (!NewMailNotifier.hasBaseline(context, credentials.id, inboxMailboxId)) {
            NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)
            return
        }
        val threads = NewMailNotifier.newSince(context, credentials.id, inboxMailboxId, emails)
            .mapNotNull { it.threadId }
            .toSet()
        val returned = runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
            .getOrDefault(emptyList())
        val remembered = read.baselineIds + returned.map { it.id }
        if (container.accountStore.notificationsEnabled(credentials.id)) {
            NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned, remembered)
        } else {
            NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)
        }
    }
}

/**
 * Does this folder take its content into the baseline silently, instead of announcing the diff?
 *
 * Two independent reasons, and the second is not negotiable: a folder seen for the first time has
 * no baseline to diff against, so announcing would mean notifying its entire existing content — a
 * fresh install, a newly watched folder or a baseline version bump would empty weeks of mail into
 * the notification shade. It holds whatever [resetBaselines] says.
 *
 * [resetBaselines] is the narrow one: the inbox the user is looking at, on an arm the user caused
 * (see [shouldResetBaseline], which decides which accounts that is). Watched extras never take it —
 * a Sieve folder is not on screen at app-open, so mail filed there while push was down must still
 * notify (issue #16).
 */
internal fun seedsSilently(resetBaselines: Boolean, isInbox: Boolean, hasBaseline: Boolean): Boolean =
    (resetBaselines && isInbox) || !hasBaseline
