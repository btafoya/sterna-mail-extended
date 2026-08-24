package app.sterna

import android.app.Application
import android.content.Context
import android.util.Log
import app.sterna.core.data.DataFactory
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.mail.BodyCachePurge
import app.sterna.core.data.mail.LocalDraftScheduler
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OutboxScheduler
import app.sterna.core.data.mail.localDraftUploadJobs
import app.sterna.core.data.mail.scheduledSendJobs
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.JmapClient
import app.sterna.drafts.LocalDraftUploads
import app.sterna.pgp.OpenKeychainPgpEngine
import app.sterna.push.UnifiedPushManager
import app.sterna.push.UnifiedPushStateStore
import app.sterna.security.AppLock
import app.sterna.send.Outbox
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.ui.connect.OAuthCodeSignIn
import app.sterna.ui.connect.OutlookSignIn
import app.sterna.widget.RecentMailWidgetDraw
import app.sterna.widget.RecentMailWidgetPresence
import app.sterna.widget.RecentMailWidgetPush
import app.sterna.widget.UnreadWidgetDraw
import app.sterna.widget.UnreadWidgetPresence
import app.sterna.widget.UnreadWidgetPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual DI container — holds app-wide singletons. */
class AppContainer(context: Context) {
    /** Content Uris shared into the app (ACTION_SEND), awaiting attachment by the next compose
     *  screen (Codeberg #45). A transient one-shot handoff between MainActivity and ComposeScreen;
     *  the compose screen reads and clears it. */
    var pendingShareUris: List<android.net.Uri> = emptyList()

    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    /** `core/jmap` is plain Kotlin/JVM and cannot see `android.util.Log`, so the app hands it the
     *  sink. The tag below is byte-for-byte half of the `adb logcat -s PushService:* JmapClient:*`
     *  filter already given to reporters, and is pinned by a test. Info/warn levels only —
     *  proguard-rules.pro strips debug and verbose in release, which is what reporters run. */
    private val jmapClient: JmapClient = JmapClient { message, error ->
        if (error == null) Log.i("JmapClient", message) else Log.w("JmapClient", message, error)
    }

    /** OpenPGP via the OpenKeychain provider (binds lazily; harmless when not installed). */
    val pgpEngine: OpenKeychainPgpEngine =
        OpenKeychainPgpEngine(context.applicationContext, settingsRepository)
    private val dataLayer =
        DataFactory.create(context.applicationContext, jmapClient, accountStore, pgpEngine, settingsRepository)
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val appLock: AppLock = AppLock(accountStore)

    /** UnifiedPush transport state machine (issue #17); inert without a distributor. */
    val unifiedPushManager: UnifiedPushManager = UnifiedPushManager(
        context.applicationContext,
        accountStore,
        mailRepository,
        UnifiedPushStateStore(context.applicationContext),
    )

    /**
     * App-lifetime scope for work that must outlive a screen (e.g. Undo-send hold-back, or putting
     * a queued message back in the outbox as the composer that held it is being popped — #70).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sendOutbox: SendOutbox = SendOutbox(appScope)

    /** Drives the Outlook OAuth device flow app-scoped, so it survives the browser round-trip. */
    val outlookSignIn: OutlookSignIn = OutlookSignIn(mailRepository, appScope, context.applicationContext)

    /** Drives the authorization-code grant app-scoped: that flow LEAVES the app for a browser and
     *  comes back through MainActivity, long after a screen-scoped owner would be gone (#55). */
    val oauthCodeSignIn: OAuthCodeSignIn =
        OAuthCodeSignIn(mailRepository, appScope, context.applicationContext)

    init {
        val appContext = context.applicationContext
        // Fallback new-mail poll: keeps notifications flowing when live push is killed
        // (Android 15+ FGS policies, OEM battery managers — Codeberg #11). Periodic work
        // persists across reboots; a healthy push makes each run a no-op.
        app.sterna.push.MailFetchWorker.ensureScheduled(appContext)
        // A UnifiedPush transport change (verified, failed, unregistered) re-evaluates
        // which accounts still need a direct connection.
        unifiedPushManager.onTransportStateChanged = {
            // Background trigger: diff, never reseed (gap mail must still notify).
            app.sterna.push.PushController.apply(appContext, userInitiated = false)
        }
        // Let the data layer arm the delivery worker from any send call site (compose, RSVP, …).
        mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }
        // The same for a draft the server could not be given (#95): the save books the deferred
        // upload itself, gated on connectivity, so a draft written in a lift leaves on its own when
        // the phone comes out. Without this the only bookings were the startup re-arm and the
        // worker re-booking itself, i.e. the draft waited for the app to die and start again.
        mailRepository.localDraftScheduler = LocalDraftScheduler { accountId, id, delay ->
            LocalDraftUploads.enqueue(appContext, accountId, id, delay)
        }
        // A linked sub-account pruned on reconcile (access revoked, Codeberg #31) drops its
        // notification baselines too, exactly like a sign-out — the data layer cannot reach them.
        mailRepository.onAccountPruned = { app.sterna.push.NewMailNotifier.clear(appContext, it) }
        // A folder the server renumbered (Codeberg #99): every id in it is new, so its baseline
        // describes messages that no longer exist under those numbers. Cleared here, the next
        // pass seeds the folder silently instead of announcing its whole content as new mail.
        mailRepository.onMailboxRenumbered = { accountId, mailboxId ->
            app.sterna.push.NewMailNotifier.clear(appContext, accountId, mailboxId)
        }
        // Catch-up sweep of cached mail belonging to accounts that no longer exist (Codeberg
        // #121) — rows a removed account left behind, which nothing syncs, nothing prunes and no
        // action can reach. The account list is handed over as a READER, not as a list: the sweep
        // calls it after it has taken its inventory, so an account created while the database was
        // opening (a shared mailbox discovered by the first refresh, #31) is already in it and
        // cannot be mistaken for an orphan. An empty list sweeps nothing (OrphanedAccountCache):
        // purging before the accounts are known would wipe a healthy install's whole cache.
        appScope.launch {
            runCatching { storageRepository.purgeOrphanedAccounts { accountStore.accounts().map { it.id } } }
                .onFailure { android.util.Log.w("Sterna", "orphaned-cache sweep failed; retried next start", it) }
        }
        // One-shot on crossing a version THRESHOLD (BODY_CACHE_PURGE_VERSION{,_2} — two so far:
        // the unsubscribe headers, then the read receipts of #148), never on every update: drop
        // the cached message bodies. A third need adds a third constant, it does not raise one.
        // A body serialised by an older build is short of whatever field the new one learned to
        // read, and openMessage serves the cache before looking at anything — so without this, a
        // new reader feature is invisible on precisely the mail already in the cache: the twenty
        // newest of the inbox, kept warm by the prefetch. Bodies only, and they refill themselves
        // on the next open.
        appScope.launch {
            runCatching {
                BodyCachePurge(appContext).onceForVersion(BuildConfig.VERSION_CODE) {
                    mailRepository.clearCachedBodies()
                }
            }.onFailure { android.util.Log.w("Sterna", "body-cache upgrade purge failed; retried next start", it) }
        }
        // Re-arm any send left mid-flight (WorkManager persists jobs, but re-checking is a safety
        // net). ⛔ `keepExisting = true`: this runs while WorkManager may ALREADY have restored the
        // job, possibly mid-delivery. A REPLACE would cancel a healthy send in flight, and since
        // the worker parks a row it finds in SENDING, the user would read "interrupted, may
        // already have been sent" at every cold start on a send nothing interrupted. It would
        // also reset the backoff of an offline send at every launch.
        appScope.launch {
            // First rescue any row stranded in EDITING by a process death mid-edit (#70): its
            // composer is gone, so it becomes a normal QUEUED send again rather than sitting
            // off-worker forever. The re-arm below then schedules it like any other queued item.
            mailRepository.revertEditingOutbox()
            mailRepository.unfinishedOutbox().forEach { item ->
                val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                Outbox.enqueue(appContext, item.id, delay, keepExisting = true)
            }
        }
        // The same safety net for a draft the phone kept because the server could not be reached
        // (#95). WorkManager persists its jobs, but a reboot, a restore or a version where the row
        // was written and the job was not leaves a draft waiting on nothing — and unlike a send,
        // nothing else will ever come and ask about it.
        //
        // ⛔ What the rescue picks up, and it is TWO states and no more: a row left UPLOADING by a
        // process that died mid-attempt (nobody is looking at it any more, and no composer can have
        // taken it — `localDraftMayBeTakenForEdit` refuses that state), and a row left STAGING mid
        // file-copy, which comes back flagged `bodyIsLossy` so it cannot destroy the server original
        // it names.
        //
        // ⛔ **And NOT an EDITING row — do not "repair" that back in.** This runs from appScope
        // while Android is still restoring the back stack, i.e. BEFORE the composer that was killed
        // with a draft open has read its row back (it takes EDITING again on purpose). Flipping it
        // to PENDING here hands the text being typed to the upload worker, which deletes the row and
        // its attachment directory on a successful upload: the user's next "Save" mints a second
        // row, so two drafts on the server and the edit lost. That is measured, on IMAP and on JMAP.
        // EDITING is given back by `MailRepository.releaseLocalDraftEdit`, and no sweep takes it
        // back. (It is not claimed that nothing else ever overwrites the state — the upload worker
        // still writes UPLOADING after reading the row, which is its own, older window.)
        //
        // ⛔ Per account, and the rescue BEFORE the scheduling — a row still UPLOADING when the
        // queue is read gets a work item that finds it already in flight. The rows come back
        // backed-off ones included, each with its own delay: nothing here gives up on a draft, it
        // only asks about it less often.
        appScope.launch {
            runCatching {
                mailRepository.accountsWithLocalDrafts().forEach { accountId ->
                    mailRepository.revertUnfinishedLocalDrafts(accountId)
                    localDraftUploadJobs(
                        accountId,
                        mailRepository.localDraftsAwaitingUpload(accountId),
                        System.currentTimeMillis(),
                    ).forEach { LocalDraftUploads.enqueue(appContext, it.accountId, it.id, it.delayMillis) }
                }
            }.onFailure { Log.w("Sterna", "local-draft re-arm failed; retried next start", it) }
        }
        // And the same safety net for a message the user asked to send LATER. Of the three durable
        // queues this is the one that waits the longest — hours, by construction — so it is the
        // likeliest to outlive the WorkManager job that was going to fire it. The row is then left
        // in the "Scheduled" screen with an hour in the past and nothing behind it, it is the only
        // copy of the message, and nothing else ever comes and asks about it. An overdue row is
        // re-armed like any other, at a delay of zero: it leaves LATE rather than never.
        //
        // ⛔ NOT a restore: `allowBackup` is false and `sterna.db` is excluded from backup and from
        // device transfer, so a restore brings back neither the row nor the job. What is readable
        // here is that the table and WorkManager's jobs are two different files, so one survives
        // the other; and that `ComposeViewModel.scheduleSend` writes the row and books its job as
        // two operations, so a death between them leaves a row nothing is attached to.
        //
        // ⚠ And it gives `ScheduledSendWorker`'s departure check a caller it did not have: a row
        // belonging to an account that is PROVABLY gone used to sit here unfired for ever, and is
        // now taken at the next start and dropped, silently. That is the same verdict the worker
        // has always reached at firing time — this only makes it arrive sooner, and only on a
        // departure `accountDepartureIsProven` accepts; an unreadable account keeps its row.
        //
        // ⛔ `keepExisting = true`, the same trap as the outbox above and one notch worse here:
        // ScheduledSendWorker deposits the message in the outbox and THEN deletes the scheduled
        // row, and the two are not one transaction. A REPLACE landing on a worker between them
        // cancels it, the deletion never runs, the row survives — and the replacement job, whose
        // hour is by then past, deposits the same message a second time.
        appScope.launch {
            runCatching {
                scheduledSendJobs(mailRepository.scheduledSends(), System.currentTimeMillis())
                    .forEach { ScheduledSends.enqueue(appContext, it.id, it.delayMillis, keepExisting = true) }
            }.onFailure { Log.w("Sterna", "scheduled-send re-arm failed; retried next start", it) }
        }
        // The home-screen widget's number, pushed as the mail moves (#112). The widget polls
        // nothing, so without this it only redraws when the system asks — at placement, at boot,
        // after an update — and sits on a stale figure while the drawer next to it decrements.
        // ONE collection covers everything that happens while the process lives: the fifteen-odd
        // read/unread, swipe, delete and multi-select writes in MailRepository, live push, and the
        // foreground refresh. Wiring those call sites one by one is the alternative, and it is a
        // list that would go out of date the first time a gesture is added.
        //
        // ⛔ Gated on a widget being PLACED, and that gate is the point: collecting the unified
        // total for the life of every process subscribes two global Room aggregates on behalf of
        // the large majority who will never place a widget. UnreadWidgetPresence follows the
        // system's own onEnabled/onDisabled, so the gate opens and closes without a restart, and
        // UnreadWidgetPush drops the subscription when the last cell goes.
        //
        // rethrowIfCancelled: a draw that fails is a stale cell and nothing more, but the app
        // scope being cancelled must not be counted as one more failed draw.
        appScope.launch {
            UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext)) {
                mailRepository.observeUnifiedInboxUnreadByAccount()
            }.collect { unreadByAccount ->
                runCatching { UnreadWidgetDraw.redraw(appContext, unreadByAccount) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "unread widget not redrawn; the cell keeps its number", it) }
            }
        }
        // The SECOND widget's rows, pushed the same way and for the same defect, measured at the
        // bench on 2026-08-18: a message marked read in the app left its row on the home screen
        // still carrying an unread dot. The data was already right — the trigger was missing.
        // RecentMailWidgetDraw.refresh had three callers (the tail of a delivery pass, and the two
        // settings writes that tighten what a row may say), and none of them follows a message
        // being read, deleted, moved, or an account being removed.
        //
        // ⛔ Its own launch and its own gate: RecentMailWidgetPresence is seeded from THIS
        // provider's placed cells, and sharing the counter's would let each widget's presence
        // decide the other one's freshness.
        //
        // ⛔ forget() BEFORE refresh(), as at both settings writes: the row factory falls back to
        // the last snapshot it managed to read when its own read times out, and that snapshot is
        // the mail as it WAS. Without dropping it, a slow read repaints exactly the rows this
        // redraw exists to replace.
        //
        // ⛔ The emitted rows are not drawn. The cell is projected by RecentMailWidgetDraw.read,
        // which consults the notification-content setting and the app lock; drawing the rows from
        // here would put senders and subjects on a home screen that has hidden them.
        appScope.launch {
            RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext)) {
                mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT)
            }.collect {
                RecentMailWidgetDraw.forget()
                runCatching { RecentMailWidgetDraw.refresh(appContext) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "latest-messages widget not redrawn; the cell keeps its rows", it) }
            }
        }
    }
}

class SternaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for ViewModels (which receive the Application). */
val Application.container: AppContainer
    get() = (this as SternaApplication).container
