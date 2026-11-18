package app.sterna.core.data

import android.content.Context
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.db.SternaDatabase
import app.sterna.core.data.mail.FlightMarkerPrefs
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.LOCAL_DRAFT_FILES_DIR
import app.sterna.core.data.mail.MailboxPendingExpungeStore
import app.sterna.core.data.mail.MailboxUidValidityStore
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthTokenRefresher
import app.sterna.core.data.mail.SyncStateStore
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.SmimeCertificates
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.search.SearchHistoryRepository
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.ATTACHMENT_STORE_DIR
import app.sterna.core.data.storage.AttachmentStore
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.flow.flowOf

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
        val smimeCertificates: SmimeCertificates,
    )

    fun create(
        context: Context,
        client: JmapClient,
        accountStore: AccountStorage,
        pgpEngine: PgpEngine? = null,
        settings: SettingsRepository? = null,
        searchHistory: SearchHistoryRepository? = null,
        /**
         * `BuildConfig.VERSION_NAME` of `:app`, handed down to [ImapClient] so it can name itself
         */
        clientVersion: String? = null,
        smimeEngine: SmimeEngine? = null,
        smimeKeys: SmimeKeySource? = null,
    ): DataLayer {
        val appContext = context.applicationContext
        val database = SternaDatabase.build(appContext)
        // The IMAP service verifies each folder's UIDVALIDITY through this store, and it is the
        // store, not the service, that says what a renumbering invalidates (Codeberg #99).
        val pendingExpunges = MailboxPendingExpungeStore(database.pendingExpungeDao())
        val attachmentStore = AttachmentStore(java.io.File(appContext.filesDir, ATTACHMENT_STORE_DIR))
        val uidValidity = MailboxUidValidityStore(
            database.mailboxUidValidityDao(), database.emailBodyDao(), database.purgeSnapshotDao(),
            database.emailFtsDao(),
            pendingExpunges,
            attachmentStore,
        )
        // One tree, handed to both readers: the repository that stages a draft's bytes there and
        // the sign-out that has to clear them (#95). Two literals would be one rename away from a
        // purge that sweeps an empty directory and reports success.
        val localDraftFiles = java.io.File(appContext.filesDir, LOCAL_DRAFT_FILES_DIR)
        val outgoingDates = OutgoingDateZones(settings?.quotedDatesUtc ?: flowOf(false))
        val imapService = ImapMailService(
            ImapClient(clientVersion), SmtpClient(), OAuthTokenRefresher(OAuthClient(), accountStore),
            uidValidity,
            pendingExpunges,
            outgoingDates = outgoingDates,
        )
        val smimeCertificates = database.smimeCertificateDao()
        return DataLayer(
            mailRepository = MailRepository(
                client, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), imapService,
                database.scheduledSendDao(), database.snoozedDao(), database.recentContactDao(),
                accountStore,
                outboxDao = database.outboxDao(),
                purgeSnapshotDao = database.purgeSnapshotDao(),
                localDraftDao = database.localDraftDao(),
                serverContactDao = database.serverContactDao(),
                mailboxUidValidityDao = database.mailboxUidValidityDao(),
                outboxFilesDir = java.io.File(appContext.filesDir, "outbox"),
                // Its own tree: a draft's staged bytes outlive any send, and the outbox's purge
                // has nothing to say about them (#95).
                localDraftFilesDir = localDraftFiles,
                scheduledFilesDir = java.io.File(appContext.filesDir, "scheduled"),
                pgpEngine = pgpEngine,
                smimeEngine = smimeEngine,
                smimeKeys = smimeKeys,
                smimeCertificates = smimeCertificates,
                settings = settings,
                syncStateStore = SyncStateStore(appContext),
                flightMarkerStore = FlightMarkerPrefs(appContext),
                logInfo = { tag, message, error ->
                    if (error == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, error)
                },
                logWarn = { tag, message, error ->
                    if (error == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, error)
                },
                outgoingDates = outgoingDates,
                searchHistory = searchHistory,
                attachmentStore = attachmentStore,
            ),
            storageRepository = StorageRepository(
                appContext, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), database.snoozedDao(), database.purgeSnapshotDao(),
                database.mailboxUidValidityDao(),
                database.pendingExpungeDao(),
                // Signing out takes the account's unsent drafts and their staged bytes with it:
                // user data, so it goes on that event and on no cache purge (#95, #121).
                localDraftDao = database.localDraftDao(),
                localDraftFilesDir = localDraftFiles,
                serverContactDao = database.serverContactDao(),
                attachmentStore = attachmentStore,
                sweepAccountPhotos = accountStore::sweepOrphanedPhotos,
            ),
            smimeCertificates = smimeCertificates,
        )
    }
}
