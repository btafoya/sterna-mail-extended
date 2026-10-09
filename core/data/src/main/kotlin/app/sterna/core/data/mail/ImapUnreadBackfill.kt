package app.sterna.core.data.mail

import app.sterna.core.data.db.CATCH_UP_OFF_WALK
import app.sterna.core.data.db.CatchUpMark
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.RowNumbering
import app.sterna.core.data.db.caughtUp

/**
 * One folder of an IMAP account as the unread catch-up reads it, selected and held for as long as
 * the block it is handed to runs. [unseen] is every unread UID of the folder, inside the synced
 * window or not. [fetch] brings the envelopes of the UIDs it is given as rows, a page at a time;
 * a UID the server no longer answers for brings no row, and that is not a failure.
 */
internal class ImapUnseenFolder(
    val uidValidity: Long,
    val unseen: Set<Long>,
    val fetch: suspend (uids: List<Long>, onPage: suspend (List<EmailEntity>) -> Unit) -> Unit,
)

/**
 * The rows of [numbering] read under another numbering than the one the SELECT stated. A change of
 * UIDVALIDITY purges no row, so each of them sits under the id of a message the server now names
 * unread, and an insert that ignores conflicts would keep it in that message's place. A row with
 * no numbering, or a SELECT that stated none, has nothing to oppose: nothing is named.
 */
internal fun renumberedRows(numbering: List<RowNumbering>, statedUidValidity: Long): List<String> {
    val stated = UidValidity.stated(statedUidValidity) ?: return emptyList()
    return numbering.filter { it.uidValidity != null && it.uidValidity != stated }.map { it.id }
}

/**
 * The UIDs of [unseen] with no row in [cachedIds], newest first, at most [budget] of them.
 * [heldLocally] names a row a gesture made on this phone has just written or is still moving: the
 * server may not have caught up, and fetching the message would put back what the user took away.
 */
internal fun absentUnread(
    unseen: Set<Long>,
    cachedIds: Set<String>,
    accountId: String,
    mailboxId: String,
    budget: Int,
    heldLocally: (String) -> Boolean,
): List<Long> = unseen.sortedDescending()
    .filter { uid -> ImapMailService.emailId(accountId, mailboxId, uid).let { it !in cachedIds && !heldLocally(it) } }
    .take(budget.coerceAtLeast(0))

/** The rows of [cachedUnreadIds] the server does not name in [unseen]: read, or gone from the folder. */
internal fun noLongerUnread(cachedUnreadIds: List<String>, unseen: Set<Long>): List<String> =
    cachedUnreadIds.filter { id -> ImapMailService.uidOf(id)?.let { it !in unseen } ?: false }

/**
 * Among [marks], those of unread rows the server no longer holds unread, the rows the catch-up
 * takes back out of the cache. Only a row it cached itself: one inside the folder's window is the
 * folder read's to rewrite or evict, and removing it here would open a hole in a list the user is
 * reading. A row outside the window is read by nothing else, and would stay unread for good.
 * [heldLocally] spares a row the user has just marked unread, which the server has yet to hear of.
 */
internal fun staleCatchUps(marks: List<CatchUpMark>, heldLocally: (String) -> Boolean): List<String> =
    marks
        .filter { (it.catchUp and CATCH_UP_OFF_WALK) != 0 }
        .filterNot { heldLocally(it.id) }
        .map { it.id }

/**
 * Among [readInCache], the cached rows of unread UIDs the cache holds read, those to mark unread:
 * the flag was cleared on another client. A row of [renumbered] is another message under the same
 * id, and is evicted. [heldLocally] spares a row the user has just read, which the server has yet
 * to hear of. A row inside the folder's window is written like any other: unlike a removal, a flag
 * set to the server's value opens no hole in a list.
 */
internal fun unreadAgain(readInCache: List<String>, renumbered: List<String>, heldLocally: (String) -> Boolean): List<String> =
    (readInCache - renumbered.toSet()).filterNot(heldLocally)

/**
 * The cache as the IMAP unread catch-up needs it, every read and write bound to one account.
 * [unreadIds] and [idsForMailbox] read one folder; [numberingOf], [marksOf] and [readAmong] take
 * any number of ids.
 */
internal class ImapUnreadCache(
    val idsForMailbox: suspend (mailboxId: String) -> List<String>,
    val numberingOf: suspend (ids: List<String>) -> List<RowNumbering>,
    val unreadIds: suspend (mailboxId: String) -> List<String>,
    val marksOf: suspend (ids: List<String>) -> List<CatchUpMark>,
    val readAmong: suspend (ids: List<String>) -> List<String>,
    val stillConfigured: suspend () -> Unit,
    val heldLocally: (id: String) -> Boolean,
    val sparingInFlight: (List<EmailEntity>) -> List<EmailEntity>,
    val insertAllIgnoring: suspend (List<EmailEntity>) -> Unit,
    val evict: suspend (ids: List<String>) -> Unit,
    val markUnread: suspend (ids: List<String>) -> Unit,
)

/**
 * [ImapUnreadCache] over the shipped DAO. The write is the conflict-ignoring insert and never an
 * upsert: a row the folder's own read wrote since the cache was read wins, with its opening line
 * and without the catch-up's mark. The removal evicts from the display cache alone, the message
 * being still on the server and still searchable. `markUnread` writes `seen` alone: a star
 * set on this phone since the cache was read is not this pass's to overwrite.
 */
internal fun imapUnreadCache(
    emailDao: EmailDao,
    accountId: String,
    unreadCap: Int,
    stillConfigured: suspend () -> Unit,
    heldLocally: (id: String) -> Boolean,
    sparingInFlight: (List<EmailEntity>) -> List<EmailEntity>,
): ImapUnreadCache = ImapUnreadCache(
    idsForMailbox = { mailboxId -> emailDao.idsForMailbox(accountId, mailboxId) },
    numberingOf = { ids -> byIdsChunked(ids) { chunk -> emailDao.numberingOfRows(accountId, chunk) } },
    unreadIds = { mailboxId -> emailDao.unreadKeys(accountId, mailboxId, unreadCap).map { it.id } },
    marksOf = { ids -> byIdsChunked(ids) { chunk -> emailDao.catchUpOf(accountId, chunk) } },
    readAmong = { ids -> byIdsChunked(ids) { chunk -> emailDao.readAmong(accountId, chunk) } },
    stillConfigured = stillConfigured,
    heldLocally = heldLocally,
    sparingInFlight = sparingInFlight,
    insertAllIgnoring = emailDao::insertAllIgnoring,
    evict = { ids -> ids.chunked(MAX_CHANGES).forEach { chunk -> emailDao.evictFromCacheKeepingIndex(accountId, chunk) } },
    markUnread = { ids -> ids.chunked(MAX_CHANGES).forEach { chunk -> emailDao.markUnread(accountId, chunk) } },
)

/**
 * The unread catch-up of one IMAP account: for each folder of the scope, caches the unread mail
 * the folder's synced window left out, takes back out the rows it had cached that the server no
 * longer holds unread, and marks unread the rows the cache holds read that the server does not.
 * Nothing is written to the server.
 *
 * [onFolder] is called once per folder and never around several: it holds the account's one
 * connection, and every read, flag change and move of the account waits for as long as it runs.
 * [budget] bounds the rows one run inserts, all folders together, in the order of the scope; a
 * run that spends it stops there. A failure of [onFolder] climbs out and ends the run, with what
 * was written before it kept.
 */
internal class ImapUnreadBackfill(
    private val accountId: String,
    private val cache: ImapUnreadCache,
    private val onFolder: suspend (mailboxId: String, block: suspend (ImapUnseenFolder) -> Unit) -> Unit,
    private var budget: Int,
    private val now: () -> Long,
) {

    /** Folder ids are numbered per account: only this account's half of [scopes] names its folders. */
    suspend fun run(scopes: List<Pair<String, String>>) {
        val folders = scopes.filter { it.first == accountId }.map { it.second }.distinct()
        for (mailboxId in folders) {
            if (budget <= 0) return
            onFolder(mailboxId) { folder -> catchUp(mailboxId, folder) }
        }
    }

    /** The cache is read whole before the first fetch: what a page inserts must not be judged stale by the same pass. */
    private suspend fun catchUp(mailboxId: String, folder: ImapUnseenFolder) {
        val cachedIds = cache.idsForMailbox(mailboxId).toSet()
        val named = folder.unseen.map { ImapMailService.emailId(accountId, mailboxId, it) }.filter { it in cachedIds }
        val renumbered = renumberedRows(cache.numberingOf(named), folder.uidValidity).filterNot(cache.heldLocally)
        val stale = staleCatchUps(cache.marksOf(noLongerUnread(cache.unreadIds(mailboxId), folder.unseen)), cache.heldLocally)
        val reopened = unreadAgain(cache.readAmong(named), renumbered, cache.heldLocally)
        val absent = absentUnread(folder.unseen, cachedIds - renumbered.toSet(), accountId, mailboxId, budget, cache.heldLocally)
        cache.stillConfigured()
        cache.evict(renumbered)
        cache.evict(stale)
        cache.markUnread(reopened)
        folder.fetch(absent) { page ->
            val fresh = cache.sparingInFlight(page)
            val caught = caughtUp(fresh, staleBefore = now() - NOTIFY_HORIZON_MS)
            cache.stillConfigured()
            cache.insertAllIgnoring(caught)
            budget -= caught.size
        }
    }
}
