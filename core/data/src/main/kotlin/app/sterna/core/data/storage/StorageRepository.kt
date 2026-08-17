package app.sterna.core.data.storage

import android.content.Context
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.mail.localDraftDirName
import app.sterna.core.data.mail.purgeLocalDraftsOfAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** On-device storage used by the cache, for the Storage & Sync settings screen. */
data class StorageUsage(
    val databaseBytes: Long,
    val attachmentBytes: Long,
    val perAccount: List<AccountUsage>,
) {
    val totalBytes: Long get() = databaseBytes + attachmentBytes
}

/** Cached-message count attributed to one account. */
data class AccountUsage(val accountId: String, val messageCount: Int)

/**
 * Reports and manages the on-device cache (Room DB + downloaded attachments).
 * This is device usage only — distinct from the server mailbox quota
 * (ARCHITECTURE.md → "Storage & data strategy"). Attachments are a re-downloadable
 * cache, so clearing them is always safe.
 */
class StorageRepository(
    private val context: Context,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val snoozedDao: SnoozedDao,
    private val purgeSnapshotDao: PurgeSnapshotDao,
    private val mailboxUidValidityDao: MailboxUidValidityDao,
    /** Drafts written on the phone and not yet on the server (#95) — user data, not cache. */
    private val localDraftDao: LocalDraftDao,
    /** Where those drafts' attachment bytes are staged; the same tree `MailRepository` writes to. */
    private val localDraftFilesDir: File,
) {
    private val attachmentsDir: File get() = File(context.cacheDir, "attachments")

    /**
     * Where compose stages outgoing-attachment bytes (a picked file, a carried forward part, a
     * reopened outbox item, #70). enqueueSend copies these into the durable per-item outbox dir, so
     * once a message is queued the staged copies are orphaned cache — never read again, but not
     * cleaned up before (StorageRepository only swept `attachments`), so they piled up. Counted and
     * cleared here alongside the attachment cache.
     */
    private val outgoingDir: File get() = File(context.cacheDir, "outgoing")

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val dbBytes = DB_FILES.sumOf { context.getDatabasePath(it).safeLength() }
        val attachmentBytes = (attachmentsDir.listFiles()?.sumOf { it.safeLength() } ?: 0L) +
            (outgoingDir.listFiles()?.sumOf { it.safeLength() } ?: 0L)
        val perAccount = emailDao.countsByAccount()
            .map { AccountUsage(it.accountId, it.messageCount) }
        StorageUsage(dbBytes, attachmentBytes, perAccount)
    }

    /** Purge every cached message + mailbox + attachment, keeping accounts/settings. */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        emailDao.deleteAll()
        emailFtsDao.clearAll()
        emailBodyDao.deleteAll()
        mailboxDao.deleteAll()
        // The recorded IMAP numbering describes the cache that has just gone (#99): keeping it
        // would leave "clear cache" incomplete, and the first sync records it again anyway.
        mailboxUidValidityDao.deleteAll()
        clearAttachments()
    }

    /** Cached-message count for one account. */
    suspend fun accountMessageCount(accountId: String): Int = withContext(Dispatchers.IO) {
        emailDao.countForAccount(accountId)
    }

    /**
     * Purge one account's cached messages. Snoozes are user intent, not cache — kept.
     *
     * ⛔ **The attachment FILES go too**, and that is the point of the last line: this used to
     * delete five tables' rows and not one byte on disk. An attachment is written into the cache
     * decrypted — an OpenPGP part included — and `enforceAttachmentCap` has no other caller, so the
     * only thing that ever removed that file was downloading the next one. The user pressed the
     * button carrying her account's name and the decrypted PDF stayed on the phone indefinitely.
     *
     * ⚠ The tree is flat and NOT namespaced per account, so this clears every account's cached
     * attachments, not just this one's. Accepted deliberately: they are re-downloadable bytes, and
     * carving per-account sub-directories would break [enforceAttachmentCap] (`isFile`),
     * [clearAttachments] (`delete()` on a non-empty directory fails silently) and [usage]
     * (`length()` of a directory) all three, in silence. The settings screen says so.
     */
    suspend fun clearAccountCache(accountId: String) = withContext(Dispatchers.IO) {
        emailDao.deleteForAccount(accountId)
        emailFtsDao.clearAccount(accountId)
        emailBodyDao.deleteForAccount(accountId)
        mailboxDao.deleteForAccount(accountId)
        mailboxUidValidityDao.deleteForAccount(accountId)
        clearAttachments()
    }

    /**
     * On sign-out: purge the account's cached rows and the attachment cache.
     * Attachments aren't namespaced per account, so the whole (re-downloadable)
     * cache is cleared to avoid leaking one account's files to another.
     *
     * ⭐ **The local drafts go here and in no other purge, and that is a decision** (#95, #121's
     * neighbourhood). A draft kept on the phone is NOT cache: it is text the user typed that the
     * server never received, so it is ranked with `snoozed` and the outbox on the user-data side —
     * removed when the account that owns it is removed, and left alone by everything that calls
     * itself a cache purge ([clearAllCache], [clearAccountCache], [purgeOrphanedAccounts]). The
     * other three are offered as free of consequence because every byte they drop can be downloaded
     * again; this text cannot.
     *
     * ⚠ Its FILES go with it, not just its rows. Since the store gained a writer, signing out left
     * the attachments of every unsent draft on the phone with no row left to name them by: the
     * directories are named after the draft ids, so they can only be found while the rows are still
     * there — hence the order inside [purgeLocalDraftsOfAccount].
     *
     * ⛔ **One `runCatching` PER STEP**, as [purgeOrphanedAccounts] already does table by table.
     * These are independent deletions, and this call is made in a LOOP over every account a
     * cascading sign-out removes (#31), followed by the orphan sweep (#121). A throw from any one
     * step used to skip the steps after it — the attachment cache included — come back out into
     * `AccountsViewModel.signOut`, stop the cascade on the linked sub-accounts and take the sweep
     * with it. The draft purge, which touches the file system, made that far likelier.
     */
    suspend fun purgeAccount(accountId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { emailDao.deleteForAccount(accountId) }
        runCatching { emailFtsDao.clearAccount(accountId) }
        runCatching { emailBodyDao.deleteForAccount(accountId) }
        runCatching { mailboxDao.deleteForAccount(accountId) }
        runCatching { snoozedDao.deleteForAccount(accountId) }
        // A pending Empty-trash destroy list belongs to the account that is going away (#99).
        runCatching { purgeSnapshotDao.deleteForAccount(accountId) }
        runCatching { mailboxUidValidityDao.deleteForAccount(accountId) }
        // Every row of the account, EDITING and STAGING ones included — an upload queue read would
        // walk past exactly the draft whose composer was open when the user signed out.
        runCatching {
            purgeLocalDraftsOfAccount(
                ids = { localDraftDao.observeForAccount(accountId).first().map { it.id } },
                deleteRows = { localDraftDao.deleteForAccount(accountId) },
                dirOf = { File(localDraftFilesDir, localDraftDirName(it)) },
            )
        }
        runCatching { clearAttachments() }
    }

    /**
     * Sweep the cached mail of accounts that no longer exist, given the accounts that DO
     * ([knownAccountIds] — a reader of `AccountStore.accounts()`' ids). Returns the ids swept.
     *
     * ⛔ THE ORDER OF THE TWO READS IS PART OF THE FIX, which is why the account list arrives as a
     * function and not as a list. Read the accounts first and take the inventory second — as the
     * callers did when they passed `store.accounts().map { it.id }` — and an account created
     * between the two is missing from the list and present in the inventory: its brand-new cache is
     * swept as an orphan. The window is not theoretical. This inventory is usually the process's
     * first database access, so it pays for opening the file and for any pending migration
     * (hundreds of milliseconds on an old device), and the first unified refresh runs in parallel:
     * it can discover a shared mailbox, create its sub-account (Codeberg #31) and write its rows in
     * exactly that gap. Taking the inventory FIRST closes it: an account created after the snapshot
     * has no row in it, so it cannot be named an orphan, and one created before it is in the list
     * read afterwards.
     *
     * Codeberg #121: a row whose account is gone is not merely mislabelled, it is unreachable —
     * no credential syncs it, no action can be routed to it, no prune covers it. It stayed in the
     * unified list forever, in whatever state it was frozen in (bold, starred), above the real
     * message. Scoping the list on (account, folder) pairs hides it; this removes it.
     *
     * ⛔ An empty account list sweeps NOTHING — see [OrphanedAccountCache.orphans]. The
     * decision is that function's, not a `NOT IN` statement's, precisely so this call can never
     * degrade into "delete the whole cache".
     *
     * Orphans are looked for in the UNION of the tables this then deletes from — messages, folders,
     * the search index, cached bodies — not in `emails` alone. An account can very well be left
     * with folders and no message: `refresh()` persists the folder list before it fetches the mail,
     * so a sign-up interrupted between the two (and every failure of the mail fetch) leaves exactly
     * that, which is the likeliest residue there is. Inventorying on `emails` alone would walk past
     * it and leave it in the database for good.
     *
     * They are removed with the same per-account deletes sign-out uses. Deliberately NOT swept:
     *  - `snoozed` — user intent rather than cache, and per-account snoozes of a gone account are
     *    inert (the not-snoozed filter correlates on accountId);
     *  - `local_drafts` and their staged files (#95) — the same rank as `snoozed`, and for a harder
     *    reason: this is text the user typed and the server has not got. This sweep runs at every
     *    start, off an inventory of four CACHE tables that a draft's own table is deliberately
     *    outside of (`LocalDraftEntity`), so an account holding nothing but drafts would be named an
     *    orphan by a list it has no row in. [purgeAccount] is where they go, on the one event that
     *    says the user is done with that account;
     *  - `purge_snapshot`, `mailbox_uidvalidity`, the outbox and the attachment files — none of
     *    them can produce a row in a list, and the attachment cache is shared, so clearing it here
     *    would cost every account a re-download on a housekeeping pass. They are not inventoried
     *    either: an id known only to them would be deleted from four tables it has no row in.
     *    ⭐ And the exclusion is checkable, not merely asserted: every account id `AccountStore`
     *    ever mints comes from `UUID.randomUUID()`, on all five of its creation paths, so an id is
     *    never re-issued. A row left behind here is keyed on an id no account will ever carry
     *    again — it has no possible reader, only bytes.
     */
    suspend fun purgeOrphanedAccounts(knownAccountIds: () -> Collection<String>): List<String> =
        withContext(Dispatchers.IO) {
            // The inventory FIRST: everything written after this snapshot belongs to an account the
            // list read below is guaranteed to contain. Swapping these two lines back sweeps the
            // cache of an account created while this runs.
            val cached = emailDao.countsByAccount().map { it.accountId } +
                mailboxDao.accountIds() + emailFtsDao.accountIds() + emailBodyDao.accountIds()
            val known = knownAccountIds()
            val orphans = OrphanedAccountCache.orphans(known, cached)
            orphans.forEach { accountId ->
                // One runCatching PER table: a failure in one must not leave the rest behind.
                runCatching { emailDao.deleteForAccount(accountId) }
                runCatching { emailFtsDao.clearAccount(accountId) }
                runCatching { emailBodyDao.deleteForAccount(accountId) }
                runCatching { mailboxDao.deleteForAccount(accountId) }
            }
            orphans
        }

    /** Write a downloaded attachment to the cache, then enforce the size/age cap. */
    suspend fun cacheAttachment(name: String?, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = attachmentsDir.apply { mkdirs() }
        val safeName = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, safeName).apply { writeBytes(bytes) }
        enforceAttachmentCap()
        file
    }

    /** LRU eviction: drop files past the age cap, then oldest-first past the size cap. */
    private fun enforceAttachmentCap() {
        // Bound the outgoing staging dir too (#70): orphaned staged copies of already-queued sends
        // past the age cap are dead cache. The cap is far longer than any compose stays open, so an
        // in-progress attachment is never pruned from under the composer.
        val now0 = System.currentTimeMillis()
        outgoingDir.listFiles()?.forEach { if (now0 - it.lastModified() > MAX_AGE_MS) it.delete() }
        val files = attachmentsDir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        val survivors = files.filter { file ->
            val tooOld = now - file.lastModified() > MAX_AGE_MS
            if (tooOld) file.delete()
            !tooOld
        }
        var total = survivors.sumOf { it.safeLength() }
        if (total <= MAX_ATTACHMENT_BYTES) return
        for (file in survivors.sortedBy { it.lastModified() }) {
            if (total <= MAX_ATTACHMENT_BYTES) break
            val size = file.safeLength()
            if (file.delete()) total -= size
        }
    }

    private fun clearAttachments() {
        clearAttachmentTrees(attachmentsDir, outgoingDir)
    }

    private fun File.safeLength(): Long = if (exists()) length() else 0L

    private companion object {
        val DB_FILES = listOf("sterna.db", "sterna.db-wal", "sterna.db-shm")
        const val MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024 // 200 MB
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}

/**
 * Empty the two attachment CACHE trees — the downloaded attachments and compose's outgoing staging
 * dir. Extracted out of `StorageRepository` (which needs a `Context` and eight Room DAOs, so no JVM
 * test can build one) purely so the sweep itself can be executed by a test, exactly as
 * `purgeLocalDraftsOfAccount` is.
 *
 * The outgoing staging dir is re-downloadable/re-stageable cache too (#70): a queued send already
 * holds its own durable copy, so clearing the orphaned staged files loses nothing.
 *
 * ⛔ Neither tree holds a local draft's staged bytes (#95) — those are text the server never
 * received and go on sign-out alone. Both directories are flat: `listFiles()` is null when the tree
 * has never been created, and `delete()` would silently fail on a sub-directory.
 */
internal fun clearAttachmentTrees(attachmentsDir: File, outgoingDir: File) {
    attachmentsDir.listFiles()?.forEach { it.delete() }
    outgoingDir.listFiles()?.forEach { it.delete() }
}
