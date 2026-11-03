package app.sterna

import android.app.Application
import android.content.Context
import android.util.Log
import app.sterna.contacts.AndroidContacts
import app.sterna.contacts.SenderNames
import app.sterna.contacts.watchContacts
import app.sterna.core.data.DataFactory
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.MoveMemory
import app.sterna.core.data.mail.BodyCachePurge
import app.sterna.core.data.mail.LocalDraftScheduler
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OutboxScheduler
import app.sterna.core.data.mail.localDraftUploadJobs
import app.sterna.core.data.mail.scheduledSendJobs
import app.sterna.core.data.pgp.SmimeCertificates
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.search.SearchHistoryRepository
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.OrphanedAccountCache
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.JmapClient
import app.sterna.drafts.LocalDraftUploads
import app.sterna.pgp.BouncyCastleSmimeEngine
import app.sterna.pgp.KeyChainSmimeKeys
import app.sterna.pgp.OpenKeychainPgpEngine
import app.sterna.pgp.androidTrustedAuthorities
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
import app.sterna.widget.RecentWidgetKind
import app.sterna.widget.UnreadWidgetDraw
import app.sterna.widget.UnreadWidgetKind
import app.sterna.widget.UnreadWidgetPresence
import app.sterna.widget.UnreadWidgetPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    /** Content Uris shared into the app (ACTION_SEND), awaiting attachment by the next compose
     *  screen (#45). A one-shot handoff; the compose screen reads and clears it. */
    var pendingShareUris: List<android.net.Uri> = emptyList()

    val linkBodies = app.sterna.ui.compose.LinkBodyHandOff()

    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val moveMemory: MoveMemory = MoveMemory.of(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    val searchHistoryRepository: SearchHistoryRepository =
        SearchHistoryRepository(context.applicationContext, settingsRepository.searchHistory)
    /** `core/jmap` is plain Kotlin/JVM and cannot see `android.util.Log`, so the app hands it the
     *  sink. The tag is half of the `adb logcat` filter given to reporters and is pinned by a test;
     *  info/warn only, release stripping debug and verbose. */
    private val jmapClient: JmapClient = JmapClient { message, error ->
        if (error == null) Log.i("JmapClient", message) else Log.w("JmapClient", message, error)
    }

    /** OpenPGP via the OpenKeychain provider (binds lazily; harmless when not installed). */
    val pgpEngine: OpenKeychainPgpEngine =
        OpenKeychainPgpEngine(context.applicationContext, settingsRepository)
    val smimeEngine: SmimeEngine = BouncyCastleSmimeEngine(trustAnchors = ::androidTrustedAuthorities)
    val smimeKeys: SmimeKeySource = KeyChainSmimeKeys(context.applicationContext)
    private val dataLayer =
        DataFactory.create(
            context.applicationContext, jmapClient, accountStore, pgpEngine, settingsRepository,
            searchHistory = searchHistoryRepository,
            // What the IMAP client names itself as to the server (RFC 2971 ID, #173). It can only
            // come from here: core/imap and core/data have no BuildConfig.
            clientVersion = BuildConfig.VERSION_NAME,
            smimeEngine = smimeEngine,
            smimeKeys = smimeKeys,
        )
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val smimeCertificates: SmimeCertificates = dataLayer.smimeCertificates
    val appLock: AppLock = AppLock(accountStore)

    /** UnifiedPush transport state machine (issue #17); inert without a distributor. */
    val unifiedPushManager: UnifiedPushManager = UnifiedPushManager(
        context.applicationContext,
        accountStore,
        mailRepository,
        UnifiedPushStateStore(context.applicationContext),
    )

    /** App-lifetime scope for work that must outlive a screen, the Undo-send hold-back, or putting
     *  a queued message back in the outbox as its composer is popped (#70). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sendOutbox: SendOutbox = SendOutbox(appScope)

    /** The startup recovery of the outbox, parking every row left editing by a process death, then
     *  re-arming the unfinished sends, as a Job a rebuilt composer `join()`s before taking its row
     * back, that row being parked failed otherwise. A Job to join, never a `runBlocking` in
     *  `onCreate`: this touches Room, and blocking the main thread on a migration is an ANR. */
    val outboxRecovery: Job

    val senderNames: SenderNames = context.applicationContext.let { appContext ->
        SenderNames(
            scope = appScope,
            enabled = settingsRepository.contactSuggestions,
            read = { withContext(Dispatchers.IO) { AndroidContacts.all(appContext) } },
            watch = { onChange -> watchContacts(appContext, onChange) },
        )
    }

    /** Drives the Outlook OAuth device flow app-scoped, so it survives the browser round-trip. */
    val outlookSignIn: OutlookSignIn = OutlookSignIn(mailRepository, appScope, context.applicationContext)

    /** Drives the authorization-code grant app-scoped: that flow leaves the app for a browser and
     *  comes back through MainActivity, long after a screen-scoped owner would be gone (#55). */
    val oauthCodeSignIn: OAuthCodeSignIn =
        OAuthCodeSignIn(mailRepository, appScope, context.applicationContext)

    init {
        val appContext = context.applicationContext
        // Fallback new-mail poll, for when live push is killed (#11). A healthy push makes each
        // run a no-op.
        app.sterna.push.MailFetchWorker.ensureScheduled(appContext)
        // A UnifiedPush transport change re-evaluates which accounts need a direct connection.
        unifiedPushManager.onTransportStateChanged = {
            // Background trigger: diff, never reseed (gap mail must still notify).
            app.sterna.push.PushController.apply(appContext, userInitiated = false)
        }
        // Let the data layer arm the delivery worker from any send call site (compose, RSVP, …).
        mailRepository.sending.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }
        // The same for a draft the server could not be given (#95): the save books the deferred
        // upload itself. Without this the only bookings were the startup re-arm and the worker's.
        mailRepository.drafts.localDraftScheduler = LocalDraftScheduler { accountId, id, delay ->
            LocalDraftUploads.enqueue(appContext, accountId, id, delay)
        }
        // A linked sub-account pruned on reconcile (#31) drops its notification baselines too,
        // exactly like a sign-out, the data layer cannot reach them.
        mailRepository.accounts.onAccountPruned = {
            app.sterna.push.NewMailNotifier.clear(appContext, it)
            app.sterna.push.MailChannels.sync(appContext, accountStore)
        }
        appScope.launch {
            runCatching { app.sterna.push.MailChannels.sync(appContext, accountStore) }
                .onFailure { Log.w("Sterna", "notification channels not reconciled; retried next start", it) }
        }
        // A folder the server renumbered (#99): every id in it is new, so its baseline describes
        // messages that no longer exist. Cleared here, the next pass seeds it silently.
        mailRepository.onMailboxRenumbered = { accountId, mailboxId ->
            app.sterna.push.NewMailNotifier.clear(appContext, accountId, mailboxId)
        }
        // Catch-up sweep of cached mail belonging to accounts that no longer exist (#121). The
        // account list is handed over as a reader, so an account created while the database was
        // opening (#31) cannot be mistaken for an orphan; an empty list sweeps nothing.
        appScope.launch {
            runCatching { storageRepository.purgeOrphanedAccounts { accountStore.accounts().map { it.id } } }
                .onSuccess { swept -> OrphanedAccountCache.sweptLine(swept)?.let { Log.i("Sterna", it) } }
                .onFailure { android.util.Log.w("Sterna", "orphaned-cache sweep failed; retried next start", it) }
        }
        // One-shot on crossing a version threshold, never on every update: drop the cached bodies.
        // A body serialised by an older build is short of whatever field the new one learned to
        // read, and openMessage serves the cache first. Bodies only, and they refill on the next open.
        appScope.launch {
            runCatching {
                BodyCachePurge(appContext).onceForVersion(BuildConfig.VERSION_CODE) {
                    mailRepository.clearCachedBodies()
                }
            }.onFailure { android.util.Log.w("Sterna", "body-cache upgrade purge failed; retried next start", it) }
        }
        // Re-arm any send left mid-flight. `keepExisting = true`: WorkManager may already have
        // restored the job mid-delivery, and a replace would cancel a healthy send, after which
        // every cold start reads "interrupted, may already have been sent".
        outboxRecovery = appScope.launch {
            // First park any row stranded in editing by a process death (#70): failed, visible and
            // reopenable, so only a gesture makes it leave. Requeued here it would go out at 0
            // while the restored composer holds the same text, twice.
            mailRepository.sending.parkInterruptedOutboxEdits()
            mailRepository.sending.unfinishedOutbox().forEach { item ->
                val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                Outbox.enqueue(appContext, item.id, delay, keepExisting = true)
            }
        }
        // The same safety net for a draft the phone kept because the server could not be reached
        // (#95): a reboot can leave one waiting on nothing, and nothing else asks about it.
        //
        // What the rescue picks up is two states and no more: a row left UPLOADING by a process
        // that died mid-attempt, and one left STAGING mid file-copy, which comes back flagged
        // `bodyIsLossy` so it cannot destroy the server original it names.
        //
        // And not an editing row, do not "repair" that back in. This runs before the killed
        // composer has read its row back, so flipping it to pending hands the text being typed to
        // the upload worker, which deletes the row on success: two drafts on the server and the edit
        // lost. Measured, on IMAP and on JMAP. Per account, and the rescue before the scheduling.
        appScope.launch {
            runCatching {
                mailRepository.drafts.accountsWithLocalDrafts().forEach { accountId ->
                    mailRepository.drafts.revertUnfinishedLocalDrafts(accountId)
                    localDraftUploadJobs(
                        accountId,
                        mailRepository.drafts.localDraftsAwaitingUpload(accountId),
                        System.currentTimeMillis(),
                    ).forEach { LocalDraftUploads.enqueue(appContext, it.accountId, it.id, it.delayMillis) }
                }
            }.onFailure { Log.w("Sterna", "local-draft re-arm failed; retried next start", it) }
        }
        // And the same for a message the user asked to send later. Of the three durable queues this
        // one waits the longest, so it is the likeliest to outlive its job, and it is the only copy
        // of the message. An overdue row is re-armed at a delay of zero: it leaves late, not never.
        //
        // Not a restore: `allowBackup` is false and `sterna.db` is excluded from backup. What is
        // readable here is that the table and WorkManager's jobs are two different files, and that
        // `scheduleSend` writes the row and books its job as two operations. It also gives
        // `ScheduledSendWorker`'s departure check a caller it did not have.
        //
        // `keepExisting = true`, one notch worse here: the worker deposits the message and then
        // deletes the row, not in one transaction, so a replace between them sends it twice.
        appScope.launch {
            runCatching {
                scheduledSendJobs(mailRepository.sending.scheduledSends(), System.currentTimeMillis())
                    .forEach { ScheduledSends.enqueue(appContext, it.id, it.delayMillis, keepExisting = true) }
            }.onFailure { Log.w("Sterna", "scheduled-send re-arm failed; retried next start", it) }
        }
        // The home-screen widget's number, pushed as the mail moves (#112). The widget polls
        // nothing, so without this it sits on a stale figure while the drawer decrements. One
        // collection covers every write, live push and foreground refresh; wiring those call sites
        // one by one is a list that goes out of date the first time a gesture is added.
        //
        // Gated on a widget being placed: collecting the unified total for the life of every
        // process subscribes a global Room query on behalf of the majority who never place
        //
        appScope.launch {
            UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext, UnreadWidgetKind.Inboxes), accountStore.accountsFlow) {
                UnreadWidgetKind.Inboxes.source(this@AppContainer)
            }.collect { unreadByAccount ->
                runCatching { UnreadWidgetDraw.redraw(appContext, UnreadWidgetKind.Inboxes, unreadByAccount) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "unread widget not redrawn; the cell keeps its number", it) }
            }
        }
        appScope.launch {
            UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext, UnreadWidgetKind.EveryFolder), accountStore.accountsFlow) {
                UnreadWidgetKind.EveryFolder.source(this@AppContainer)
            }.collect { unreadByAccount ->
                runCatching { UnreadWidgetDraw.redraw(appContext, UnreadWidgetKind.EveryFolder, unreadByAccount) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "every-folder unread widget not redrawn; the cell keeps its number", it) }
            }
        }
        // The second widget's rows, pushed the same way and for the same defect: a message marked
        // read in the app left its row on the home screen carrying an unread dot, the data being
        // right and the trigger missing.
        //
        // Its own launch and its own gate: RecentMailWidgetPresence is seeded from THIS provider's
        // cells, and sharing the counter's would let each widget decide the other's freshness.
        appScope.launch {
            RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Inboxes), accountStore.accountsFlow) {
                RecentWidgetKind.Inboxes.source(this@AppContainer)
            }.collect {
                RecentMailWidgetDraw.forget(RecentWidgetKind.Inboxes)
                runCatching { RecentMailWidgetDraw.refresh(appContext, RecentWidgetKind.Inboxes) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "latest-messages widget not redrawn; the cell keeps its rows", it) }
            }
        }
        appScope.launch {
            RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Unread), accountStore.accountsFlow) {
                RecentWidgetKind.Unread.source(this@AppContainer)
            }.collect {
                RecentMailWidgetDraw.forget(RecentWidgetKind.Unread)
                runCatching { RecentMailWidgetDraw.refresh(appContext, RecentWidgetKind.Unread) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "unread-messages widget not redrawn; the cell keeps its rows", it) }
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

val Application.container: AppContainer
    get() = (this as SternaApplication).container
