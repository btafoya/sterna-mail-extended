package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.BlindCopies
import app.sterna.core.imap.IMAP_FOLDER_PAGE
import app.sterna.core.imap.IMAP_PREVIEW_FETCH_BYTES
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolder
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.ImapIdleConnection
import app.sterna.core.imap.ImapMailboxStatus
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapMoved
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.ImapTextPart
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.SmtpClient
import app.sterna.core.imap.buildImapSearch
import app.sterna.core.imap.searchFolders
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A folder's mailboxes + what the walk of the target folder saw.
 *
 * ⛔ There are no `messages` here any more. They were handed to the caller page by page while the
 * walk ran ([ImapMailService.loadFolder]'s `onPage`) and are already in the cache by the time this
 * value exists; what is left is [walk] — the UIDs, and whether the folder held still.
 */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val walk: ImapFolderWalk,
)

/**
 * ⛔ Settle the folder's NUMBERING before a single row of it can be seen, then walk it.
 *
 * Two lines and an order, in a function of its own for the reason [fullQueryWriteThrough] is one: an
 * order that exists only as the sequence of two statements in the middle of a long function is an
 * order no test can execute.
 *
 * [settle] is `ImapMailService.reconcileNumbering` — on a folder whose UIDVALIDITY changed, it drops
 * every cache keyed by the old UIDs (bodies, purge snapshots, the notification baseline). [walk]
 * hands its pages to the caller AS THEY LAND, so the first of them is on screen long before the walk
 * returns. Settling afterwards left a window the length of the walk during which a row of the NEW
 * numbering was listed while the body cached for that same id under the OLD one was still there —
 * and opening a message reads the body cache first. That is Codeberg #99's exact symptom, and
 * writing pages as they land is what re-opened it; before that, nothing was visible until the read
 * was over.
 *
 * ⛔ [settle] COMPARES AND RECORDS, it does not refuse: `loadFolder` selects folders it has just
 * listed, and refusing there would fail a whole inbox refresh over one renumbered folder. Running it
 * earlier must not turn it into a gate.
 *
 * ⚠ It now also runs when the walk goes on to fail, which it did not before. That is the safe
 * direction — an invalidation seen and applied — and the caches it drops are re-fillable.
 */
internal suspend fun <R> withNumberingSettled(settle: suspend () -> Unit, walk: suspend () -> R): R {
    settle()
    return walk()
}

/**
 * The cache ids a finished IMAP folder walk MAY be reconciled against — or NULL when the walk cannot
 * vouch for what it read, and there is no set the reconcile can safely be given.
 *
 * ⛔ A function of its own, and a pure one, because it is the whole IMAP half of the red line and a
 * condition buried in the middle of a refresh is a condition nobody can execute in a test. Null is
 * a refusal, never an empty set: `reconcileMailbox` given an empty keep set deletes the folder.
 *
 * Ids, not UIDs: the cache is keyed by `imap:account:folder:uid`, so the mapping needs the account
 * and the folder the walk actually landed on (which is not always the one the caller asked for —
 * `loadFolder` falls back to the inbox). And it is a mapping of the WHOLE walk, every page of it,
 * never the last one.
 *
 * Two refusals, in this order:
 *
 * 1. the folder MOVED under the walk ([ImapFolderWalk.moved]) — a page may have skipped a message
 *    the server still holds, so the UIDs below are missing it and reconciling deletes it;
 * 2. the walk read NOTHING and the folder never said it was empty. "The walk finished" is not the
 *    same statement as "the walk saw the folder", and only the second licenses a delete — the same
 *    rule, and the same wording, as the JMAP twin [reconcilableWindowIds]. Two ways to end up here
 *    without anything failing: a `SELECT` that never stated a count (line absent, or `* NIL EXISTS`)
 *    leaves `exists` at 0, so no page is even asked for; and a folder of a thousand messages whose
 *    `FETCH` responses carry no `UID` is dropped item by item by `ImapSession.messages`. Both come
 *    back empty, and clearing the cache on either loses every offline message of the folder.
 *
 * ⭐ [ImapFolderWalk.folderStatedEmpty] is the licence, and a folder that really is empty carries it:
 * an emptied folder must still be able to clear a stale cache, or it shows its old contents for ever
 * and nothing else on IMAP cleans up.
 *
 * ⚠ What refusal (2) costs, stated because it is a real change of behaviour: a folder whose remaining
 * messages ALL carry `\Deleted` without an expunge — another client emptied the trash and did not
 * purge — reports `* n EXISTS` with n > 0, so it is not "stated empty", and `ImapSession.messages`
 * drops every one of them, so the walk brings back no UID. It is indistinguishable from a walk that
 * read nothing, and it is refused: the cache used to be cleared here, and now keeps its stale rows,
 * which Sterna lists and cannot open. Not permanent — an expunge, or one readable message back in
 * the window, clears them — and it is the survivable direction, so it stands.
 */
internal fun reconcilableIds(load: ImapFolderLoad, accountId: String): Set<String>? {
    if (load.walk.moved) return null
    if (load.walk.uids.isEmpty() && !load.walk.folderStatedEmpty) return null
    return load.walk.uids.mapTo(HashSet()) { ImapMailService.emailId(accountId, load.targetMailboxId, it) }
}

/**
 * Whether the SELECT this page was read through STATED that the folder is empty — `* 0 EXISTS`,
 * parsed, and nothing else ([ImapMailboxStatus.existsObserved]).
 *
 * ⛔ The exact twin of what `ImapClient.walkFolder` writes into [ImapFolderWalk.folderStatedEmpty],
 * on purpose and with the same words: the statement is the same statement, and the push pass needs
 * it for the same reason the reconcile does. "The page came back with nothing in it" is not "the
 * folder holds nothing": a SELECT that never stated a count leaves `exists` at 0, which reads
 * exactly like an empty folder, and a folder whose remaining messages all carry `\Deleted` without
 * an expunge states `* n EXISTS` with n > 0 and still yields no row, because `ImapSession.messages`
 * drops every one of them. Only a count the server STATED, and zero, tells them apart.
 *
 * Pure and top-level for the reason [reconcilableIds] is: this is a decision, and a decision written
 * inline in a network function is one no test can execute.
 */
internal fun pageVouchesForEmpty(status: ImapMailboxStatus): Boolean =
    status.existsObserved && status.exists == 0

/**
 * The ids a watched folder's page MAY be remembered as — or NULL when this page cannot say what the
 * folder holds, and the notification baseline must be left exactly as it was.
 *
 * The [reconcilableIds] pattern, for the same reason and with the same shape: null is a REFUSAL and
 * an empty list is a statement, and the two must never be spelled the same way. `NewMailNotifier
 * .seed` REPLACES a folder's baseline, so remembering an empty page erases it — and the next pass,
 * finding nothing remembered, announces every unread message inside the notifier's age floor: one
 * banner per thread, in the middle of the night, for mail the user read yesterday.
 *
 * ⭐ [ImapWatchedLoad.folderStatedEmpty] is the licence, and a folder that really was emptied carries
 * it: an emptied folder must still be able to clear its baseline, or that baseline is immortal and
 * mail refiled into the folder later can never be announced.
 */
internal fun watchedBaselineIds(load: ImapWatchedLoad): List<String>? {
    if (load.messages.isEmpty() && !load.folderStatedEmpty) return null
    return load.messages.map { it.id }
}

/** One watched folder's fetched page (multi-folder push, issue #16). */
data class ImapWatchedLoad(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val messages: List<EmailEntity>,
    /**
     * Where each message's opening line could be READ from, for the ones that have one — see
     * [PreviewSource] and [previewSourcesOf]. Keyed by the same cache id as [messages], so the
     * notification layer can look one up for a message it holds without parsing an id apart.
     *
     * No default: a branch that forgot to fill it would silently announce mail with no preview
     * for ever, and nothing about the notification would look wrong.
     */
    val previewSources: Map<String, PreviewSource>,
    /**
     * Whether the server STATED that this folder is empty — see [pageVouchesForEmpty], which is the
     * only thing allowed to answer it, and [ImapFolderWalk.folderStatedEmpty], which is the same
     * statement carried for the cache reconcile.
     *
     * No default, for the reason written on [previewSources]: this field is an ANSWER the server
     * gave, and a default is a way of not asking. Either default lies in silence — `true` would let
     * a page that read nothing wipe the baseline, which is the defect itself, and `false` would
     * refuse for ever, so an emptied folder would keep a baseline no pass can ever clear and mail
     * refiled there later would never be announced. Neither shows up as anything but "the
     * notifications are odd", weeks later.
     */
    val folderStatedEmpty: Boolean,
    /**
     * The numbering the SELECT that enumerated [messages] stated, normalised by
     * [UidValidity.stated] — null when the server stated none.
     *
     * Carried because the caller reads the cache back for this page before writing it
     * (`MailRepository.refreshAccountFolders`, which copies the opening lines this pass does not
     * read onto the rows it is about to upsert), and that read has to be bounded by the numbering
     * or it answers with rows of the PREVIOUS one, whose UIDs collide with these after a
     * renumbering: the new message would take the old one's first line and keep it (#99 in #187).
     *
     * ⛔ It comes from the SELECT and NOT from [messages]. Reading it back off the rows would make
     * the guard answerable to the very rows it guards, and an empty page would carry no answer at
     * all. ⛔ No default, for the reason written on [previewSources] and [folderStatedEmpty]: it is
     * an answer the server gave, and a default is a way of not asking — a defaulted null would
     * quietly mean "unknown numbering" everywhere and match only the rows of servers that state
     * none.
     */
    val uidValidity: Long?,
)

/**
 * Where one message's opening line can be read from: its [uid] and the [part] the server's own
 * BODYSTRUCTURE named, carried out of the sync page that already received both.
 *
 * ⛔ It exists so that NOTHING re-asks. The structure travels with the envelope of every page of
 * every folder walk; a notification preview that had to ask again would spend a SELECT and a
 * second FETCH per message, in a background service, before it could name the section it wants.
 * The moment this is rebuilt from the server instead of carried, the whole point is gone.
 */
data class PreviewSource(val uid: Long, val part: ImapTextPart)

/**
 * The [PreviewSource] of each message of [page] that has readable text, keyed by cache id.
 *
 * A message with no `text/…` part is ABSENT rather than present-and-null: it has no opening line,
 * so it must not consume one of the notification pass's few preview fetches (see
 * `NotificationPreviews.gather`) — nor cost a round trip whose answer is already here.
 *
 * Pure, and given the page rather than a session: this is the whole of the conveyance, and it is
 * the step where a refactor would quietly reach back to the server.
 */
internal fun previewSourcesOf(
    accountId: String,
    mailboxId: String,
    page: List<ImapMessage>,
): Map<String, PreviewSource> = page.mapNotNull { message ->
    message.textPart?.let { part ->
        ImapMailService.emailId(accountId, mailboxId, message.uid) to PreviewSource(message.uid, part)
    }
}.toMap()

/**
 * ⭐ Whether the MESSAGE LIST wants an opening line at all — the one decision that says whether a
 * folder walk reads bodies, and the reason [ImapMailService] needs no settings of its own.
 *
 * True when the list itself shows a preview ([PreviewLines] other than `NONE`), and ALSO when it
 * shows none but notifications are set to [NotificationContent.BODY_PREVIEW]. The second term is
 * not generosity: the extract a notification carries is gathered on ARRIVAL
 * (`NotificationPreviews.gather`), while a snoozed message is re-announced later from the bare
 * cache row — so a row with no `preview` column loses the opening line the first notification had.
 *
 * Written on the precedent of `NotificationPreviews.gather`'s own first line, which asks the same
 * question of the same setting. ⛔ Pure, and separate, so a test can EXECUTE it over the product of
 * both enumerations instead of restating the rule — a test that recomputes the condition passes
 * just as happily with the condition inverted.
 */
internal fun previewWantedInList(lines: PreviewLines, content: NotificationContent): Boolean =
    lines != PreviewLines.NONE || content == NotificationContent.BODY_PREVIEW

/**
 * The opening line of each message of [page] that has readable text and does not have one cached
 * yet, keyed by the SAME cache id as the rows ([ImapMailService.emailId]) — Codeberg #187.
 *
 * The conveyance, and the whole of it. Pure: it is handed the page and a [fetch], so what goes on
 * the wire is a value a test can pin rather than a call it can only observe happening.
 *
 * Four properties, each of which is a defect if it goes:
 *
 *  - it starts from [previewSourcesOf], which is already "the totality of the conveyance": the
 *    section and its encoding come from the BODYSTRUCTURE the envelope fetch ALREADY carried, and a
 *    message with no `text/…` part is simply absent — no round trip to learn it has nothing to say;
 *  - ⭐ only the ids in [missing] are asked for. That is what makes the cost be paid ONCE per
 *    message: without it every pull-to-refresh and every step of the pager would re-download up to
 *    two hundred openings the cache is already holding;
 *  - ⭐ the UIDs are grouped BY SECTION and [fetch] is called once per group — two to four calls for
 *    a page in practice ([app.sterna.core.imap.firstTextPart] answers "1", "1.1", "2"…). One call
 *    per message would be up to two hundred sequential round trips under the account's single mutex
 *    ([ImapMailService.withSession]), with opening a message, marking one read and emptying the
 *    trash queued behind them;
 *  - each body is filed under the cache id of ITS OWN uid. The grouped answer is keyed by UID for
 *    that reason ([app.sterna.core.imap.ImapSession.fetchSectionPartials]); pairing it by position
 *    would put one correspondent's words under another correspondent's name in the list.
 *
 * ⛔ The decoding is [BodyPreview.fromPart] and nothing else — pure, tested, and the half no socket
 * test can reach. A body that decodes to nothing is an ORDINARY answer and simply does not become
 * a key, exactly as it is not a preview on the notification path.
 */
internal fun previewsForPage(
    accountId: String,
    mailboxId: String,
    page: List<ImapMessage>,
    missing: Set<String>,
    fetch: (section: String, uids: List<Long>) -> Map<Long, String>,
): Map<String, String> {
    val wanted = previewSourcesOf(accountId, mailboxId, page).filterKeys { it in missing }
    val out = LinkedHashMap<String, String>()
    for ((section, sources) in wanted.values.groupBy { it.part.section }) {
        val bodies = fetch(section, sources.map { it.uid })
        for (source in sources) {
            val raw = bodies[source.uid] ?: continue
            BodyPreview.fromPart(raw, source.part)?.let {
                out[ImapMailService.emailId(accountId, mailboxId, source.uid)] = it
            }
        }
    }
    return out
}

/**
 * What one page of a folder walk WRITES into the preview column: the openings just [read], plus the
 * ones the cache was already holding for the rows that were deliberately not re-read.
 *
 * ⛔ The second half is the whole point, and its absence was Codeberg #187. The read is asked only
 * for the ids the cache lacks — that is what makes an opening line cost ONE fetch ever, instead of
 * one per pull-to-refresh over a page of two hundred — but Room's `@Upsert` REPLACES THE WHOLE ROW.
 * So a page written with the fetched openings alone sets `preview = null` on exactly the rows it
 * refused to re-read, and the next walk reads them back: measured in the database as 1 filled row
 * out of 20, then 17, then 1 again, one flip per refresh, with the list's rows growing to 160 px
 * and falling back to 136 px in front of the user. What we write for a row we did not re-read is
 * what the cache already holds.
 *
 * The same refusal [keepingCachedPreviews] makes at the OTHER writer — the push/worker pass, which
 * reads no body at all and would otherwise blank the newest rows of every watched folder. One rule,
 * two writers; both take their values from the single `EmailDao.cachedPreviews` read.
 *
 * ⛔ [read] wins a collision. It cannot happen on the shipped path (the fetch is asked only for what
 * the cache has not got), but the two are maps of the same type and the operand order is invisible
 * to the compiler: the freshly read body is the fresher of the two.
 */
internal fun pageOpenings(cached: Map<String, String>, read: Map<String, String>): Map<String, String> = cached + read

/**
 * The deadline arithmetic behind a time-bounded IMAP call. Pure and separate because every
 * IMAP call in the app goes through the code that uses it, and because what it protects — a
 * retry that must not be allowed to double the wait — is invisible in an integration test.
 *
 * [NO_BUDGET] is the case that must stay exactly as it always was: no deadline, and every
 * socket left blocking.
 */
internal object ImapBudget {

    /** No deadline. Also the socket value meaning "block", which is why they are one constant. */
    const val NO_BUDGET = 0

    /** The instant a call started at [nowMs] with [budgetMs] must be done, or [NO_BUDGET]. */
    fun deadline(budgetMs: Int, nowMs: Long): Long = if (budgetMs > 0) nowMs + budgetMs else NO_BUDGET.toLong()

    /**
     * The budget one call actually runs under: what its CALLER asked for, and failing that whatever
     * [ImapReadBudget] the coroutine carries ([ambientMs]).
     *
     * ⛔ The caller wins whenever it named anything at all, however long — a caller that passed a
     * budget has weighed its own operation, and an ambient value silently shortening it would make
     * a bound tighter than the one written at the call site. The ambient value is a FLOOR under the
     * calls that named nothing, not a ceiling over the ones that did.
     *
     * Both zero is [NO_BUDGET] — the blocking behaviour every existing caller has always had, which
     * is what happens whenever nobody installed an element: the ambient value is then 0.
     */
    fun effective(callerMs: Int, ambientMs: Int): Int = when {
        callerMs > 0 -> callerMs
        ambientMs > 0 -> ambientMs
        else -> NO_BUDGET
    }

    /**
     * What is left of [deadline] at [nowMs], as a socket timeout: [NO_BUDGET] when there is no
     * deadline, otherwise a positive number of milliseconds — and an exception once the budget
     * is spent, so a caller cannot connect or read "one last time" past its own deadline.
     *
     * A [SocketTimeoutException] on purpose: it is what an expiring read throws, so the caller
     * that already falls back on one needs no second branch — and it is emphatically NOT a
     * CancellationException, which would be indistinguishable from the user cancelling.
     */
    fun remaining(deadline: Long, nowMs: Long): Int {
        if (deadline == NO_BUDGET.toLong()) return NO_BUDGET
        val left = deadline - nowMs
        if (left <= 0) throw SocketTimeoutException("IMAP budget exhausted")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * ⭐ **A read budget carried by the COROUTINE**, for the calls whose bound cannot be written at the
 * call site (#95).
 *
 * `withSession`'s [budgetMs] is per call, and the calls that need bounding most are the ones that
 * pass none: `appendDraft` is the whole of the deferred draft upload, and it is `NO_BUDGET`, i.e.
 * `soTimeout = 0`, i.e. a link cut mid-`APPEND` leaves the thread in a `read()` that never returns.
 * ⛔ **A coroutine cancellation does not end a blocking read**: the job is cancelled, the thread
 * stays on the socket, it keeps the account's IMAP mutex, and the caller's own `finally` — the one
 * that would have written the failed attempt on the row — is never reached. That is measured
 * behaviour, not a worry: a draft went to `UPLOADING` and stayed there with nothing else able to
 * touch the account.
 *
 * So the bound is installed by the CONTEXT the operation runs in ([MailRepository.uploadLocalDraft])
 * and read at the one place every IMAP call passes, `runWithRetry`. ⛔ It is a FLOOR, never a
 * ceiling: [ImapBudget.effective] lets any caller that named a budget of its own keep it, and with
 * no element installed the ambient value is 0 and every existing caller is bit-for-bit unchanged.
 *
 * ⛔ It does not bound the WAIT for the account's mutex, for `SEND_PROOF_BUDGET_MS`'s reason — but it
 * is what stops this operation from being the thing another caller waits on forever.
 */
internal class ImapReadBudget(val millis: Int) : AbstractCoroutineContextElement(ImapReadBudget) {
    companion object Key : CoroutineContext.Key<ImapReadBudget>
}

/**
 * IMAP read+write path, parallel to the JMAP path in [MailRepository]. Maps IMAP
 * folders/messages onto the same Room entities so the cache, paging, and UI are
 * protocol-agnostic. One connection is pooled per account and reused across calls
 * (IMAP is stateful, so access is serialised); a dropped connection reconnects.
 */
class ImapMailService(
    private val imapClient: ImapClient,
    private val smtpClient: SmtpClient,
    private val tokenRefresher: OAuthTokenRefresher,
    /** Where each folder's UIDVALIDITY is remembered; [UidValidityStore.None] verifies nothing. */
    private val uidValidity: UidValidityStore = UidValidityStore.None,
) {
    /** A pooled, reusable connection for one account. */
    private class Pooled {
        val mutex = Mutex()
        var session: ImapSession? = null
        var config: MailServerConfig? = null
    }

    private val pool = ConcurrentHashMap<String, Pooled>()

    /**
     * Run [block] on the account's pooled IMAP session, opening or reconnecting as
     * needed. Serialised per account (one command at a time); on a connection error
     * the session is dropped and the block retried once with a fresh connection.
     *
     * [budgetMs] is a wall-clock deadline for the whole call, retry included; `0` (the default
     * every existing caller keeps) means the blocking behaviour this has always had — unless the
     * COROUTINE carries an [ImapReadBudget], which then applies to exactly those callers
     * ([ImapBudget.effective]). See [ENUMERATE_BUDGET_MS] for what a budget is and is not worth.
     *
     * ⛔ [retryOnFailure] turns that one retry OFF, **per call and for one caller** ([appendDraft]).
     * A read can be replayed; an `APPEND` cannot. The failure a client sees says nothing about what
     * the server did with bytes it had already received, so replaying it inside the same call is how
     * one save gesture puts two drafts in the Drafts folder — with no attempt recorded in between,
     * so neither the deferred retry nor the `Message-ID` search of #95 ever gets a say, and IMAP
     * `APPEND` hands back no id for the phone to see either copy by. The retry belongs to the
     * deferred loop, which knows how to de-duplicate; the price is 30 s of backoff on a failure that
     * was only transient. ⛔ Not a change of the reconnect policy: every other caller keeps it.
     */
    private suspend fun <T> withSession(
        credentials: AccountCredentials,
        budgetMs: Int = 0,
        retryOnFailure: Boolean = true,
        // Suspending for ONE caller, [loadFolder], whose folder walk writes each page to the
        // database between requests — that is what holds the walk's memory to one page. Every
        // other block here suspends at nothing and behaves exactly as before. ⚠ What a suspending
        // block costs is that the account's single connection is held across those suspensions:
        // nothing slow belongs in one that is not the reason the connection is open.
        block: suspend (ImapSession) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val config = config(credentials.imap ?: error("Account has no IMAP server configured."), credentials)
            val pooled = pool.getOrPut(credentials.id) { Pooled() }
            pooled.mutex.withLock {
                // Reconnect if the account's server settings changed.
                if (pooled.config != null && pooled.config != config) {
                    pooled.session?.let { runCatching { it.close() } }
                    pooled.session = null
                }
                pooled.config = config
                runWithRetry(pooled, config, budgetMs, retryOnFailure, block)
            }
        }

    private suspend fun <T> runWithRetry(
        pooled: Pooled,
        config: MailServerConfig,
        budgetMs: Int,
        retryOnFailure: Boolean,
        block: suspend (ImapSession) -> T,
    ): T {
        // ⭐ ONE deadline, from the caller's budget or — when it named none — the one the coroutine
        // carries ([ImapReadBudget]). It is taken here, so the two `remaining` calls below both
        // derive from it: bounding the connect and leaving the reads at 0 would leave `appendDraft`
        // exactly as unbounded as it was, since its wait is entirely in the tagged response.
        val ambientMs = currentCoroutineContext()[ImapReadBudget]?.millis ?: ImapBudget.NO_BUDGET
        val deadline = ImapBudget.deadline(ImapBudget.effective(budgetMs, ambientMs), System.currentTimeMillis())
        var attempt = 0
        while (true) {
            // What is left of the budget bounds the connect, and what is left AFTER it bounds
            // the reads: time spent connecting is not handed back to them, and a retry that has
            // run the budget out throws here instead of connecting again. Without a budget both
            // stay 0 — "block until the OS gives up", unchanged for every other caller.
            val session = pooled.session
                ?: imapClient.connect(config, ImapBudget.remaining(deadline, System.currentTimeMillis()))
                    .also { pooled.session = it }
            // Outside the try: an exhausted budget is not a session error and must not cost a
            // close + reconnect on the way out.
            val forReads = ImapBudget.remaining(deadline, System.currentTimeMillis())
            try {
                return session.withReadTimeoutSuspending(forReads) { block(session) }
            } catch (cancelled: CancellationException) {
                // The caller gave up (an Undo, a closed screen): the session is mid-command and
                // its stream state unknown, so drop it — but do NOT spend a reconnect + LOGIN
                // retrying work nobody is waiting for, and let the cancellation through.
                runCatching { session.close() }
                pooled.session = null
                throw cancelled
            } catch (renumbered: ImapUidValidityChanged) {
                // Not a transport failure: the connection is healthy and the answer would be the
                // same on a fresh one. Retrying would only ask the server to renumber the folder
                // back. Keep the session and let the refusal through (Codeberg #99).
                throw renumbered
            } catch (t: Throwable) {
                runCatching { session.close() }
                pooled.session = null
                // ⛔ The connection is dropped either way — it is the REPLAY that is refused for a
                // caller whose command is not idempotent. See [withSession]'s [retryOnFailure].
                if (!retryOnFailure || ++attempt >= 2) throw t
            }
        }
    }

    /**
     * THE CHOKEPOINT for anything addressed by UID: SELECT the folder, check that its numbering
     * is still the one our UIDs belong to, then run [block] (Codeberg #99).
     *
     * Every command below that names a UID — a flag, a move, a destroy, a body fetch — is
     * preceded by a `select()` whose status was, until now, read and thrown away. It carries the
     * folder's UIDVALIDITY, and UIDVALIDITY changing is the one event that makes a UID mean a
     * DIFFERENT message rather than no message (RFC 3501 §2.3.1.1). Doing the check here, once,
     * is what stops a caller from forgetting it.
     *
     * [expectedUidValidity] overrides the remembered value for a caller that carries its own —
     * an "Empty trash" snapshot was frozen under a numbering of its own, possibly hours before
     * the destroy worker gets connectivity, and that is the value its ids belong to.
     *
     * A mismatch invalidates what cannot heal itself and propagates: nothing is retried, nothing
     * is swallowed. The remembered value is written AFTER the block succeeds, and the read is
     * done BEFORE the session is taken, because the store is suspending and the session block
     * is not.
     *
     * The discovery reads ([loadFolder], [loadWatchedFolders]) do not come through here: they
     * select folders they have just listed, so refusing there would fail a whole inbox refresh
     * over one renumbered folder. They still COMPARE AND RECORD, through [reconcileNumbering] —
     * without that, browsing a folder would leave its numbering unrecorded, an offline "Empty
     * trash" would then have nothing to stand in for the unreachable server, and the body cache
     * would only be dropped by whatever happened to touch a message afterwards. Refusing and
     * reconciling are different questions; only the first belongs here.
     */
    private suspend fun <T> onMailbox(
        credentials: AccountCredentials,
        mailboxId: String,
        budgetMs: Int = 0,
        expectedUidValidity: Long? = null,
        block: suspend (ImapSession, ImapMailboxStatus) -> T,
    ): T {
        val recorded = uidValidity.recorded(credentials.id, mailboxId)
        var observed = 0L
        val result = try {
            withSession(credentials, budgetMs) { session ->
                val status = session.select(mailboxId, expectedUidValidity ?: recorded)
                observed = status.uidValidity
                block(session, status)
            }
        } catch (renumbered: ImapUidValidityChanged) {
            uidValidity.invalidate(credentials.id, mailboxId, renumbered.observed)
            throw renumbered
        }
        rememberNumbering(credentials.id, mailboxId, recorded, observed)
        return result
    }

    /**
     * Apply what a SELECT just observed, WITHOUT refusing anything: record it, or — when it does
     * not match what was recorded — invalidate the caches keyed by the old numbering first.
     *
     * The comparison is the same one [onMailbox] makes; only the consequence differs. A read that
     * merely lists a folder has nothing to abort, but it is still the first thing to notice the
     * renumbering, and the body cache has to be dropped BEFORE the caller shows a message from it.
     */
    private suspend fun rememberNumbering(accountId: String, mailboxId: String, recorded: Long?, observed: Long) {
        when (UidValidity.verdict(recorded, observed)) {
            UidValidity.Verdict.CHANGED -> uidValidity.invalidate(accountId, mailboxId, observed)
            // The overwhelmingly common answer, and it costs nothing: every folder select of
            // every refresh comes through here, and rewriting the same number would be a
            // database write per folder per sync on devices that can least afford one.
            UidValidity.Verdict.SAME, UidValidity.Verdict.UNVERIFIABLE -> Unit
            UidValidity.Verdict.FIRST_SIGHT -> uidValidity.record(accountId, mailboxId, observed)
        }
    }

    /** [rememberNumbering] for a caller that had nothing in hand beforehand (the discovery reads). */
    private suspend fun reconcileNumbering(accountId: String, mailboxId: String, observed: Long) {
        if (observed <= 0L) return
        rememberNumbering(accountId, mailboxId, uidValidity.recorded(accountId, mailboxId), observed)
    }

    /** The numbering last observed for a folder, for a caller that has to record it elsewhere
     *  (the Empty-trash snapshot, when it falls back to the cached ids). */
    suspend fun recordedUidValidity(accountId: String, mailboxId: String): Long? =
        uidValidity.recorded(accountId, mailboxId)

    /**
     * Called when a folder turns out to have been renumbered, with `(accountId, mailboxId)`.
     *
     * The data layer drops what it owns (bodies, destroy lists) itself; the notification baseline
     * lives in `:app` and cannot be reached from here, so the app layer sets this at startup —
     * the same shape as `MailRepository.onAccountPruned`, and for the same reason. Left unset it
     * does nothing.
     */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = uidValidity.onRenumbered
        set(value) { uidValidity.onRenumbered = value }

    /**
     * Open a dedicated IDLE connection on the account's INBOX for push. Separate from the
     * pooled connection (IDLE blocks); returns a Closeable the caller closes to stop.
     */
    suspend fun openIdle(credentials: AccountCredentials, onChanged: () -> Unit, onClosed: () -> Unit): java.io.Closeable {
        val endpoint = credentials.imap ?: error("Account has no IMAP server configured.")
        // The token is resolved fresh here; if it expires mid-IDLE the connection drops and
        // the caller reopens (re-refreshing) via onClosed.
        return ImapIdleConnection(imapClient, config(endpoint, credentials), "INBOX", onChanged, onClosed)
    }

    /** Raw folder list (paths + the server's REAL hierarchy delimiter from LIST). */
    suspend fun listImapFolders(credentials: AccountCredentials) =
        withSession(credentials) { it.listFolders() }

    /** Close and forget an account's pooled connection (e.g. on sign-out). */
    suspend fun disconnect(accountId: String) {
        val pooled = pool.remove(accountId) ?: return
        pooled.mutex.withLock {
            pooled.session?.let { runCatching { it.close() } }
            pooled.session = null
        }
    }

    /** Send via SMTP, then APPEND a \Seen copy into the Sent folder (if known). */
    suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) {
        val smtp = credentials.smtp ?: error("Account has no SMTP server configured.")
        val token = tokenRefresher.freshAccessToken(credentials)
        smtpClient.send(
            MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.username, credentials.password, token),
            message,
        )
        if (sentMailbox != null) {
            runCatching { withSession(credentials) { it.append(sentMailbox, OutgoingMime.build(message), "\\Seen") } }
        }
    }

    /**
     * APPEND a draft (\Draft flag) into the Drafts folder.
     *
     * ⛔ **`retryOnFailure = false`, and it is the point of the call.** The bytes of one save go out
     * at most once per call: a replay inside the session would append a second copy of the same text
     * before any attempt is recorded, so the deferred loop — the only place that de-duplicates by
     * `Message-ID` (#95) — would never see the first one. ⛔ Not the same for [send]'s Sent-folder
     * APPEND, which is outside this and unchanged.
     *
     * ⚠ **And no budget of its own, deliberately**: the same call serves the interactive save, which
     * keeps its historic blocking read, and the deferred upload, which needs a bound. A parameter
     * here would have to be threaded through `uploadDraft` for one of its two callers; the bound is
     * therefore installed on the coroutine instead ([ImapReadBudget], and
     * [LOCAL_DRAFT_UPLOAD_BUDGET_MS] is the value the upload uses).
     */
    suspend fun appendDraft(credentials: AccountCredentials, draftsMailbox: String, message: OutgoingMessage) =
        withSession(credentials, retryOnFailure = false) {
            it.append(draftsMailbox, OutgoingMime.build(message, BlindCopies.WRITTEN), "\\Draft")
        }

    /**
     * Whether the Drafts folder already holds the draft named [messageId] — the question a deferred
     * upload asks before appending a second copy of text the server has (#95). `APPEND` returns no
     * id to keep, so this header is the only name that draft has on both sides of the network.
     *
     * ⛔ **The question is NOT put to the server as a search key.** `SEARCH HEADER "Message-ID"` is
     * what this used to send, and Stalwart answers it with an empty `* SEARCH` in every shape —
     * naked, bracketed, substring, on a delivered message — while answering `HEADER "Subject"` and
     * `HEADER "To"` perfectly. Measured at the bench, five witnesses, on a folder holding two copies
     * of the very id being asked about. An empty answer reads as "not there", so the guard appended
     * a third copy. So: `UID SEARCH ALL` ([ImapSession.newestUids]), the one key every server
     * implements, the newest [DRAFT_LOOKUP_SCAN] of them, their envelopes, and the comparison made
     * HERE by [localDraftMessageIdMatches].
     *
     * ⛔ **The newest, and a folder deeper than the cap answers "not there".** Our copy is the last
     * thing `APPEND`ed, so it is at that end; and past the cap the honest answer is "I did not
     * look", whose only safe reading here is "append it".
     *
     * ⛔ **Not [search], deliberately.** That one maps entities, applies the attachment filter and
     * turns a folder it could not read into a skipped folder plus a log line — an empty answer that
     * cannot be told apart from "there is nothing there". Here the two must never look alike: an
     * error has to reach the caller, whose only safe reading of "I could not look" is "append it".
     * ⛔ Which is also why nothing below is wrapped in a `runCatching`.
     */
    suspend fun draftIsAlreadyThere(
        credentials: AccountCredentials,
        draftsMailbox: String,
        messageId: String,
    ): Boolean {
        // Block body, not an expression one, so LocalDraftUploadWiringTest can read this body: an
        // expression body has no braces of its own and the lookup would hand back the lambda's.
        return withSession(credentials) { session ->
            session.select(draftsMailbox)
            session.fetchUids(session.newestUids(DRAFT_LOOKUP_SCAN))
                .any { localDraftMessageIdMatches(messageId, it.messageId) }
        }
    }

    /** Connect + authenticate (and list folders) to verify the account's IMAP settings. */
    suspend fun testConnection(credentials: AccountCredentials) {
        withSession(credentials) { it.listFolders() }
    }

    /**
     * List a SPECIFIC account's folders (role + path), without fetching any messages.
     * Used to resolve a role's folder for the message's own account — the global mailbox
     * cache only holds the last-synced account, so it's wrong for a non-current account
     * in the unified inbox.
     *
     * ⛔ A BARE `listFolders()`, with no subscription question and therefore no LSUB, and it must
     * stay that way (#174). Its one caller (`MailRepository.imapRoleFolder`) reads a role and an
     * id off these rows and WRITES NONE of them, so a subscription fetched here is paid for and
     * thrown away — on every archive, every delete to Trash and every junk move, for an account
     * that has a lot of folders, which is the very reporter this feature is for. The rows that a
     * subscription must reach are the ones the cache keeps, and they come from [loadFolder].
     */
    suspend fun listMailboxes(credentials: AccountCredentials): List<MailboxEntity> =
        withSession(credentials) { session ->
            session.listFolders().mapIndexed { index, folder ->
                imapMailboxEntity(credentials.id, folder, index)
            }
        }

    /**
     * Connect, list folders, and WALK the newest [limit] of the target folder, handing each page to
     * [onPage] as it lands.
     *
     * ⛔ [onPage] is not a convenience: it is where the window's messages go. This function keeps
     * none of them — see [ImapFolderLoad] — so a caller that passes an empty lambda is asking for
     * the folder list and nothing else, which is what the folder-list refresh does. There is no
     * default for it, deliberately: "where do these messages go" is a question every caller has to
     * answer out loud.
     *
     * ⛔ [onlySubscribed] is asked of the SERVER, not applied here: it adds the `LSUB` round trip
     * that fills [MailboxEntity.isSubscribed] on the rows below (#174). Nothing is dropped from
     * [ImapFolderLoad.mailboxes] because of it, and the target folder is chosen the same way.
     *
     * ⛔ And [ImapFolderLoad.walk] carries [ImapFolderWalk.moved], which a caller that DELETES on
     * the strength of this walk must honour ([reconcilableIds]).
     *
     * Selects a folder it has just listed, so it cannot be refused on its numbering — but it is
     * usually the FIRST thing to see that numbering, and browsing a folder must be enough to
     * record it: an offline "Empty trash" afterwards has nothing else to go on, and the body
     * cache has to be dropped BEFORE the user can open a message from the list this writes. Hence
     * [reconcileNumbering] ahead of the walk and not after it ([withNumberingSettled], Codeberg
     * #99): the pages become visible while the walk is still running.
     *
     * ⚠ What this costs while it runs, stated because it is not free: the account's ONE connection
     * is held for the whole walk, database writes included, so every other IMAP operation on that
     * account (opening a message, marking read, moving, emptying the trash) queues behind it. That
     * was already true of the single FETCH this replaces, but the writes are new inside the lock.
     * ⚠ And the walk is retried ONCE as a whole on a transport failure (`runWithRetry`), so a
     * DATABASE failure in the middle of it re-runs the entire network read as well. Both are known
     * and out of this change's scope; neither can delete anything.
     *
     * ⛔ [cachedPreviewsFor] has NO DEFAULT, for [onPage]'s reason: "does this walk read opening
     * lines, and for which rows" is a question every caller answers out loud. Null means READ NONE.
     * It is a lambda and not a setting because this class has neither the settings nor the database
     * and must not grow either — the caller is given the page's cache ids AND the numbering they
     * were just read under, and answers with the openings it ALREADY holds for them under that
     * numbering (#187): the missing ones are what gets fetched, the held ones are re-written as
     * they were, because the upsert replaces the whole row. ⛔ The numbering is the second argument
     * and not an afterthought: without it the answer can come from the numbering BEFORE a
     * renumbering, and the row then keeps another message's first line for good (#99). See
     * [pagePreviews] for where in the walk it is used, and why there.
     */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
        onlySubscribed: Boolean = false,
        onPage: suspend (List<EmailEntity>) -> Unit,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): ImapFolderLoad {
        return withSession(credentials) { session ->
            val folders = session.listFolders(onlySubscribed)
            val mailboxes = folders.mapIndexed { index, folder ->
                imapMailboxEntity(credentials.id, folder, index)
            }
            val target = folders.firstOrNull { it.path == requestedMailboxId }
                ?: folders.firstOrNull { it.role == "inbox" }
                ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
                ?: folders.first()

            val status = session.select(target.path)
            val unread = session.unseenCount()
            val walk = withNumberingSettled(
                settle = { reconcileNumbering(credentials.id, target.path, status.uidValidity) },
                walk = {
                    session.walkFolder(status, limit, IMAP_FOLDER_PAGE) { page ->
                        val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)
                        onPage(
                            page.map {
                                val preview = previews[emailId(credentials.id, target.path, it.uid)]
                                it.toEntity(credentials.id, target.path, status.uidValidity, preview)
                            },
                        )
                    }
                },
            )

            ImapFolderLoad(
                mailboxes = mailboxes,
                targetMailboxId = target.path,
                targetName = target.name,
                unread = unread,
                accountName = credentials.username,
                walk = walk,
            )
        }
    }

    /**
     * Fetch the newest [limit] messages of the inbox (unless [includeInbox] is false) plus
     * each watched folder in [extraPaths], on one session (multi-folder push, issue #16).
     * Watched paths no longer on the server come back in the second component so the
     * caller can prune the stale watch flags.
     *
     * Like [loadFolder] it cannot be refused on a folder's numbering — it selects what it has
     * just listed — but it compares and records every folder it touched ([reconcileNumbering]),
     * so a renumbering noticed by a background push pass drops the same caches a foreground one
     * would (Codeberg #99).
     */
    suspend fun loadWatchedFolders(
        credentials: AccountCredentials,
        extraPaths: Set<String>,
        includeInbox: Boolean,
        limit: Int,
    ): Pair<List<ImapWatchedLoad>, Set<String>> {
        val numbering = mutableListOf<Pair<String, Long>>()
        val result = withSession(credentials) { session ->
            val folders = session.listFolders()
            val inbox = folders.firstOrNull { it.role == "inbox" }
                ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
                ?: folders.first()
            val targets = buildList {
                if (includeInbox) add(inbox)
                extraPaths.forEach { path ->
                    val folder = folders.firstOrNull { it.path == path }
                    if (folder != null && folder.path != inbox.path) add(folder)
                }
            }
            val missing = extraPaths.filterTo(mutableSetOf()) { path -> folders.none { it.path == path } }
            val loads = targets.map { folder ->
                val status = session.select(folder.path)
                numbering += folder.path to status.uidValidity
                // The page is kept whole for one line longer than it used to be: [toEntity] drops
                // the BODYSTRUCTURE the server already sent, and the notification preview needs it.
                // Re-asking for it later would be a second round trip per message for an answer
                // that was in this very response.
                val page = session.fetchPage(status.exists, offset = 0, limit = limit)
                ImapWatchedLoad(
                    folder.path,
                    folder.name,
                    folder.role,
                    // ⛔ NO preview READ for the row, deliberately: this is the NOTIFICATION path,
                    // it has `previewSources` below and its own budget (six reads and four seconds
                    // for the whole account pass, `NotificationPreviews`), and caching the six it
                    // reads out of a page of fifty is precisely the half-true list `BodyPreview`
                    // refuses. That reason stands and is the only one this line carries.
                    // ⛔ It DESTROYS none either — and that is not this line's doing, because it
                    // cannot be: `@Upsert` replaces the whole row, so a null written here wipes the
                    // opening line the folder walk cached for these very rows. The caller copies
                    // them back on before writing (`MailRepository.keepingCachedPreviews`, #187).
                    page.map { it.toEntity(credentials.id, folder.path, status.uidValidity, null) },
                    previewSourcesOf(credentials.id, folder.path, page),
                    // The SELECT's answer, not a verdict written here: `status` is already in hand,
                    // so the provenance costs no request of its own — and the rule that reads it is
                    // the one a test can execute.
                    folderStatedEmpty = pageVouchesForEmpty(status),
                    // Same SELECT, same page, one normalisation: what the caller's cache read is
                    // bounded by, so the openings it copies back cannot come from a numbering
                    // these UIDs no longer belong to (#99 in #187).
                    uidValidity = UidValidity.stated(status.uidValidity),
                )
            }
            loads to missing
        }
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        return result
    }

    /**
     * Fetch the page of messages just older than the [offset] newest, for paging.
     *
     * ⛔ [cachedPreviewsFor] has no default, exactly as on [loadFolder], and null means read no
     * opening lines. What it answers for the page is both the bound on the fetch and what the rows
     * it did not fetch are written back with ([pageOpenings]), and it is asked UNDER the numbering
     * this SELECT stated. The read happens inside the [onMailbox] block — the numbering chokepoint
     * — so the bodies are asked for under the SELECT that enumerated the very UIDs being asked for,
     * and the cache is asked under that same statement rather than under whatever the folder was
     * numbered as when those rows were written.
     */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): Pair<List<EmailEntity>, Int> = onMailbox(credentials, mailboxId) { session, status ->
        val page = session.fetchPage(status.exists, offset, limit)
        val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)
        val messages = page.map {
            val preview = previews[emailId(credentials.id, mailboxId, it.uid)]
            it.toEntity(credentials.id, mailboxId, status.uidValidity, preview)
        }
        messages to status.exists
    }

    /** Mark a message seen on the server (UID STORE +FLAGS \Seen). */
    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    /** Set or clear an IMAP flag (e.g. \Seen, \Flagged) on a message. */
    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        onMailbox(credentials, mailboxId) { session, _ -> session.setFlag(uid, flag, set) }

    /**
     * Move a message to [destMailbox]; returns its new UID there (for undo, in [ImapMoved.uids])
     * and [destMailbox]'s own numbering as `COPYUID` stated it.
     *
     * ⭐ That numbering is carried because NOTHING ELSE EVER LEARNS IT: a `UID MOVE` does not
     * SELECT its destination, so on a Trash the user has never opened the app has no recorded
     * number for it — and the Undo of this very move, which is a move OUT of that folder, would
     * then have nothing to oppose and be refused. The server states it in the same breath as the
     * UIDs it hands back, frozen at that instant; see [ImapMoved] (#99).
     *
     * ⭐ Held to exactly the standard [deleteBatch] is held to, and for the user's own reason:
     * "delete" on an account with a Trash IS this call ([MailRepository.delete]), so the most
     * ordinary gesture in the app was the one confirming nothing. [expectedUidValidity] is the
     * numbering [uid] was read under; the move only goes out when the server states that same
     * numbering in the SELECT it is about to move under. A SELECT carrying no `UIDVALIDITY` line
     * at all sails through [ImapSession.select] — it refuses only when two POSITIVE numbers
     * disagree — so without this the `UID MOVE` went out on yesterday's UIDs, and a renumbered
     * folder had another, live message filed away or thrown in the Trash (#99).
     *
     * ⚠ Read [deleteBatch]'s doc for why the block RETURNS a verdict and the refusal is raised
     * after [onMailbox] returns: a throw inside would be replayed by `withSession`, and it must
     * not be mistaken for `ImapUidValidityChanged`, which [onMailbox] answers by INVALIDATING the
     * folder — dropping cached bodies and pending purge snapshots over a server that is merely
     * quiet. Both apply here word for word.
     *
     * ⛔ Not a defaulted "proceed": a call carrying nothing is REFUSED, because carrying nothing
     * lets the SELECT fall back on the number recorded right now — the one an ordinary refresh has
     * already updated if the folder was renumbered — and the guard would then compare that number
     * with itself. Refusing costs a re-query; moving costs the mail.
     */
    suspend fun move(
        credentials: AccountCredentials,
        sourceMailbox: String,
        uid: Long,
        destMailbox: String,
        expectedUidValidity: Long? = null,
    ): ImapMoved {
        val (moved, landed) = onMailbox(credentials, sourceMailbox, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayMove = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            mayMove to if (mayMove) session.move(uid, destMailbox) else ImapMoved.NONE
        }
        if (!moved) throw ImapNumberingUnconfirmed(sourceMailbox, expectedUidValidity)
        return landed
    }

    /**
     * Move many messages from one source folder to [destMailbox] in a single session —
     * one SELECT + one `UID MOVE <set>` (chunked) instead of N round-trips (Codeberg #29).
     * Returns the source-UID → destination-UID mapping (from COPYUID) so Undo can move the
     * whole batch back — empty if the server reported none — and [destMailbox]'s numbering, which
     * the same COPYUID states and which the Undo of this batch is the only thing that can oppose:
     * see [move], and [ImapMoved].
     *
     * [expectedUidValidity] is the numbering [uids] were read under, and it is opposed exactly as
     * [move] opposes it — see that doc, and [deleteBatch]'s, for why. Here it comes from further
     * away: a bulk gesture reads it at the tap and the batch runs after a whole selection has been
     * resolved, so the number belongs to the moment the user acted, never to execution time.
     * ⚠ The refusal comes back to `MailRepository.imapMoveGroup` through its `runCatching`, which
     * catches `Throwable`, so the group lands in `BulkResult.failed` and the caller re-queries.
     */
    suspend fun moveBatch(
        credentials: AccountCredentials,
        sourceMailbox: String,
        uids: List<Long>,
        destMailbox: String,
        expectedUidValidity: Long? = null,
    ): ImapMoved {
        val (moved, landed) = onMailbox(credentials, sourceMailbox, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayMove = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            mayMove to if (mayMove) session.move(uids, destMailbox) else ImapMoved.NONE
        }
        if (!moved) throw ImapNumberingUnconfirmed(sourceMailbox, expectedUidValidity)
        return landed
    }

    /**
     * Permanently delete many messages from one folder in a single session (Codeberg #29).
     *
     * [expectedUidValidity] is the numbering the caller's UIDs were read under — the one frozen
     * with an "Empty trash" snapshot. A folder renumbered since then refuses the whole call
     * ([ImapUidValidityChanged]) instead of destroying whatever now holds those numbers (#99).
     *
     * ⭐ And so does a folder whose numbering the server STOPS STATING. The refusal in
     * [ImapSession.select] compares two positive numbers, so a SELECT carrying no `UIDVALIDITY`
     * line at all sailed through it and the expunge went out on yesterday's UIDs — in the INBOX
     * itself on an account with no Trash. This is the one place where all three facts are in hand
     * at once: the intent is DESTRUCTIVE, the number is the one the user's confirmation FROZE, and
     * the status is what the server JUST said. The read paths cannot be held to this standard
     * (they would make a folder unreadable on a non-conforming server), and a `requireNumbering`
     * flag on `select()` would carry the destructive intent down into `core/imap`, where every
     * future caller could forget it. So the decision, [UidValidity.mayDestroyUnderStatedNumbering],
     * is asked here, once, for the three destroy entry points that reach this call: the trash
     * purge, a selection destroy, and `MailRepository.destroyDraft`.
     *
     * ⚠ The block RETURNS a verdict and the refusal is raised after [onMailbox] returns, rather
     * than thrown inside it. Two things depend on that: a throw inside would be caught by
     * `withSession`'s retry, which would close the session, reconnect, LOGIN and re-SELECT before
     * failing anyway; and it must not be mistaken for `ImapUidValidityChanged`, which
     * [onMailbox] answers by INVALIDATING the folder — dropping the very purge snapshot the user
     * confirmed. Nothing is invalidated and nothing is recorded here: with a stated numbering of 0
     * [UidValidity.verdict] answers `UNVERIFIABLE`, and `rememberNumbering` writes nothing for it.
     */
    suspend fun deleteBatch(
        credentials: AccountCredentials,
        mailboxId: String,
        uids: List<Long>,
        expectedUidValidity: Long? = null,
    ) {
        val destroyed = onMailbox(credentials, mailboxId, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayDestroy = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            if (mayDestroy) session.delete(uids)
            mayDestroy
        }
        if (!destroyed) throw ImapNumberingUnconfirmed(mailboxId, expectedUidValidity)
    }

    /**
     * At most [cap] UIDs currently in [mailboxId] — one SELECT + one `UID SEARCH ALL`, no
     * envelopes, and [cap] ids held whatever the folder's size (to a few tokens per response
     * line, see [ImapSession.allUids]).
     *
     * The folder as the SERVER holds it, not as the cache happens to have synced it, which is
     * what a whole-folder operation needs: an "Empty trash" on a Trash the user never scrolled
     * through must cover the messages below the synced window too (Codeberg #99). Past [cap]
     * the ids kept are the OLDEST — see [ImapSession.allUids], which also says why that is the
     * opposite end from JMAP's.
     *
     * TIME-BOUNDED, unlike every other call here, because the screen has already told the user
     * the Trash is empty by the time this runs: it is allowed [ENUMERATE_BUDGET_MS] (read its
     * doc for what that does and does not bound), then it fails and the caller falls back to
     * the cached ids. Waiting out a black-holed network on this path would leave a success
     * message on screen over an untouched list.
     */
    suspend fun snapshotUids(credentials: AccountCredentials, mailboxId: String, cap: Int): ImapUidSnapshot =
        onMailbox(credentials, mailboxId, budgetMs = ENUMERATE_BUDGET_MS) { session, status ->
            // The numbering comes back WITH the ids: they are only an order for as long as it
            // holds, so the caller freezes the two together (Codeberg #99).
            ImapUidSnapshot(session.allUids(cap), status.uidValidity)
        }

    /** Fetch several messages by UID from one folder in a single session (e.g. to re-cache a restored batch). */
    suspend fun fetchByUids(credentials: AccountCredentials, mailboxId: String, uids: List<Long>): List<EmailEntity> =
        if (uids.isEmpty()) emptyList()
        else onMailbox(credentials, mailboxId) { session, status ->
            // ⛔ No preview: the ids these rows carry hold BRAND-NEW UIDs — this is the re-cache
            // after a `UID MOVE` back (`MailRepository`'s undo), so they collide with nothing and
            // the `@Upsert` overwrites nobody's opening line. Nothing to preserve, nothing to read.
            session.fetchUids(uids).map { it.toEntity(credentials.id, mailboxId, status.uidValidity, null) }
        }

    /** Create a folder if it doesn't exist (e.g. an Archive on first archive). */
    suspend fun createFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.createFolder(path) }

    /** Rename a folder (IMAP RENAME). */
    suspend fun renameFolder(credentials: AccountCredentials, oldPath: String, newPath: String) =
        withSession(credentials) { it.renameFolder(oldPath, newPath) }

    /** Delete a folder (IMAP DELETE). */
    suspend fun deleteFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.deleteFolder(path) }

    /**
     * Server-side structured search across [mailboxIds], newest first (entities not cached by
     * the caller). One SELECT + UID SEARCH per folder on the single pooled connection, in the
     * order given — the caller ranks the folders, so a per-account cap keeps the folders that
     * matter rather than whichever the server listed first.
     *
     * Each hit is turned into an entity under THE FOLDER IT CAME FROM and this account's id: an
     * IMAP UID is meaningless outside its mailbox, and the cache id is `imap:account:folder:uid`.
     * A folder that fails is logged and skipped, never silently merged away.
     *
     * The result carries [ImapSearchHits.complete]: false when any folder's answer was not whole
     * — its attachment scan stopped on its cap with candidates left, OR it could not be searched
     * at all. The list is then what was found, not what exists.
     */
    suspend fun search(
        credentials: AccountCredentials,
        mailboxIds: List<String>,
        criteria: ImapSearchCriteria,
        requireAttachment: Boolean,
        limit: Int,
    ): ImapSearchHits {
        if (mailboxIds.isEmpty() || limit <= 0) return ImapSearchHits(emptyList(), complete = true)
        val command = buildImapSearch(criteria)
        // The walk is blocking and holds this account's only connection; if the caller is gone
        // (a new keystroke cancelled the search), stop between folders instead of making every
        // later IMAP call wait behind it.
        val caller = currentCoroutineContext()[Job]
        // ⭐ What each folder's SELECT stated about its numbering, recorded after the walk — the
        // same shape as [loadWatchedFolders], and for the same reason: the store is suspending and
        // the session block holds the account's one connection.
        //
        // Without this, a folder reached ONLY by a server-side search (a hit in an archive the user
        // has never opened) left its numbering unrecorded, so `MailRepository` had nothing to
        // freeze and archiving or moving that hit was REFUSED on a healthy server (#99).
        // Nothing is refused here and nothing is hardened in `core/imap`: the walk had already
        // asked, and this simply stops throwing the answer away.
        //
        // ⚠ Since schema v25 the DESTROY no longer reads this record at all — it opposes the
        // numbering stamped on the ROW ([EmailEntity.uidValidity]), and a search hit is never
        // written to `emails` (`MailRepository.search` maps its hits straight to `toEmail()`). So a
        // permanent delete of a hit the cache does not hold opposes nothing and destroys nothing,
        // which is the safe end and a change from what this comment used to promise. The move and
        // archive paths still freeze the folder's record, and this is still what they read.
        val numbering = mutableListOf<Pair<String, Long>>()
        val result = withSession(credentials) { session ->
            val folders = session.searchFolders(
                mailboxIds,
                command,
                requireAttachment,
                limit,
                stillWanted = { caller?.isActive != false },
                onFolderError = { mailbox, error ->
                    android.util.Log.w("ImapSearch", "search failed in $mailbox: ${error.message}")
                },
            )
            folders.forEach { hits -> numbering += hits.mailbox to hits.uidValidity }
            ImapSearchHits(
                messages = folders
                    // ⛔ No preview: a search hit is NEVER written to `emails` — the comment on this
                    // function says so and `MailRepository.search` maps its hits straight to
                    // `toEmail()`. A body read here would be paid for on the wire and dropped.
                    .flatMap { hits -> hits.messages.map { it.toEntity(credentials.id, hits.mailbox, hits.uidValidity, null) } }
                    .sortedByDescending { it.sortKey }
                    .take(limit),
                complete = folders.none { it.incomplete },
            )
        }
        // Compare and record, never refuse — [reconcileNumbering] is exactly the "had nothing in
        // hand beforehand" case its doc describes, and it ignores a folder that stated nothing.
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        return result
    }

    /** Fetch and decode an attachment (one MIME [section]) to raw bytes. */
    suspend fun fetchAttachment(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        section: String,
        encoding: String?,
    ): ByteArray = onMailbox(credentials, mailboxId) { session, _ ->
        MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
    }

    /**
     * One message's ENVELOPE, by folder and UID, as a cache row — `null` when the folder does not
     * hold that UID (destroyed since we last looked).
     *
     * ⚠ Its doc used to say "used to restore after an undo"; the undo goes through [fetchByUids]
     * (plural) and this had no production caller at all. Its caller is [fetchById], i.e. opening a
     * message the cache does not hold (#159).
     */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        onMailbox(credentials, mailboxId) { session, status ->
            // ⛔ No preview: this row is not cached either (`MailRepository.fetchById` renders it
            // and stops), and the message is about to be OPENED — its whole body is one request
            // away, so reading an opening line for it would be the same bytes twice.
            session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId, status.uidValidity, null)
        }

    /**
     * The envelope of a message named by CACHE ID ALONE, fetched from the server (#159).
     *
     * What it is for: a hit of a server-side search lives in a folder no sync ever walked, so the
     * `emails` table holds no row for it — and the id already names the folder and the UID
     * ([targetOf]), which is everything the fetch needs. `null` for an id that is not IMAP's, and
     * ⛔ nothing goes on the wire for one: a `local-draft:` id is the only copy of a text, and it
     * has no business becoming a SELECT of a folder named after a UUID.
     *
     * ⛔ **Through [fetchByUid], hence through [onMailbox].** That is where the UIDVALIDITY check
     * that closes #99 lives, and a UID means nothing outside the numbering it belongs to: under a
     * new one, the same number names a DIFFERENT message. A bare `withSession { select(…) }` here
     * would save a lookup and hand the reader somebody else's mail.
     *
     * ⛔ **And the id must belong to [credentials].** The cache read this fetch stands in for was
     * also the account partition — `emailsByIds(credentials.id, …)` finds nothing for another
     * account's id and the open failed there. Nothing else re-states it: `MessageViewModel` opens
     * with the CURRENT account whenever the intent carries no account id (a notification without
     * `EXTRA_OPEN_ACCOUNT_ID`), so on a phone holding two IMAP accounts an unchecked id would send
     * account B's folder and UID down account A's connection and show the wrong mail under the
     * right header. Refused before a single packet.
     */
    suspend fun fetchById(credentials: AccountCredentials, emailId: String): EmailEntity? {
        if (accountOf(emailId) != credentials.id) return null
        val (mailboxId, uid) = targetOf(emailId) ?: return null
        return fetchByUid(credentials, mailboxId, uid)
    }

    /**
     * The cc+bcc addresses of one message, straight off the envelope — `null` when the message is
     * not there (nothing to read) rather than an empty set, and `null` too when an envelope entry
     * is there but no address can be got out of it ([copiedAddressesOrNull]).
     *
     * Read from the SERVER on purpose, never from the cache: the cache row has no cc/bcc column,
     * so on IMAP it answers "no copied parties" both for a draft that has none and for one whose
     * Cc was never loaded. Both callers are deciding whether to destroy a server copy — the draft
     * save, and the send that replaces an edited draft — and those two answers must not look alike.
     *
     * ⭐ [budgetMs] has no default, for [fetchPreview]'s reason (its doc says it in full): the
     * block below is blocking, holds the account's single connection under its mutex, and a
     * `withTimeout` from the caller can bound none of that. The save passes
     * [ImapBudget.NO_BUDGET] explicitly — its historic blocking behaviour; the send passes a real
     * deadline, because it runs this BEFORE its outbox row exists, while the message is only in the
     * composer's RAM. An expired budget throws ([SocketTimeoutException]), which every caller
     * already reads as "could not be established": unknown, keep the original.
     *
     * ⚠ **`NO_BUDGET` here is "the caller named nothing", not "nothing bounds this".** The save's
     * call site is shared with the DEFERRED draft upload, whose coroutine carries an
     * [ImapReadBudget] (#95): passing `NO_BUDGET` from there yields to that ambient bound
     * ([ImapBudget.effective]). Wanted, and harmless in the only direction that matters — an expiry
     * is "unknown", i.e. no destroy.
     */
    suspend fun ccAndBccOf(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        budgetMs: Int,
    ): Set<String>? {
        // Block body, not an expression one, so DraftAddressingWiringTest can read this body: an
        // expression body has no braces of its own and the lookup would hand back the lambda's.
        return onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->
            val message = session.fetchByUid(uid) ?: return@onMailbox null
            copiedAddressesOrNull(message.cc + message.bcc)
        }
    }

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String =
        onMailbox(credentials, mailboxId) { session, _ -> session.fetchSource(uid) }

    /**
     * The opening line of every message of [page] that has one and does not have one CACHED yet —
     * read here, INSIDE the folder read, on the session that has just SELECTed the folder (#187).
     *
     * ⭐ Inside the read on purpose, and it is [fetchPreview]'s reason for going through
     * [onMailbox]: a UID means nothing outside the numbering it was enumerated under. Asking for
     * these bodies anywhere else would name UIDs under a SELECT that is not the one that listed
     * them, a renumbered folder would answer with OTHER MESSAGES' text, and the list would show the
     * opening of somebody else's mail under this sender's name (Codeberg #99). Both callers put it
     * behind that check: the walk's own `select`, and `fetchOlderPage`'s [onMailbox].
     *
     * ⛔ NOTHING ESCAPES. An exception raised here would fail the whole folder read — the read that
     * fills the cache and that a notification pass advances its baseline behind. A failed pass
     * RE-ANNOUNCES the folder on the next round, and a re-announce MOVES MAIL on the server
     * (`unarchiveThreadsOnReply`); see `NotificationPreviews.postGatheredFirst`, which is written
     * at length about exactly that. So: a cancellation goes through, because it means the caller is
     * gone, and every other failure costs the openings of one page and strictly nothing else. Same
     * shape as [fetchPreview], for the same reason.
     *
     * ⚠ "Costs the openings NOT YET READ" is true of the failing NETWORK read and only of it. The
     * cache read is inside the same `try`, so a [cachedPreviewsFor] that throws leaves `cached`
     * empty and the page goes out bare — the upsert then blanks the column for the whole page.
     * That is bounded rather than repaired, deliberately: the same database serves the upsert this
     * walk feeds, so one that cannot answer a SELECT cannot take the write either and there is no
     * page left to blank; and the one failure this read can have with a working database is
     * SQLite's host-variable cap on `IN (:ids)`, which the "ONE PAGE, never several" rule written
     * on that query already holds off.
     *
     * ⛔ [cachedPreviewsFor] null means READ NOTHING — the caller has not asked, or has no settings
     * to ask with. The decision is the caller's because this class has neither the settings nor the
     * database ([MailRepository.listPreviewCache]), and that has to stay true. When it is not null
     * it answers what the cache ALREADY holds for the page UNDER [uidValidity], keyed by row id:
     * the keys bound the fetch, the values are carried through by [pageOpenings].
     *
     * ⛔ [uidValidity] is the numbering the SELECT that enumerated this page stated, through the one
     * shipped normalisation ([UidValidity.stated]) — and it is what makes the cache read SAFE
     * rather than merely cheap. A row id carries no UIDVALIDITY, and a renumbered folder hands back
     * UIDs that collide with the ids still cached: unbounded, the read answers the PREVIOUS
     * message's opening line, [pageOpenings] writes it onto the new row, and nothing asks again
     * (#99 in #187). Passed IN rather than read off the rows, for the reason on
     * [ImapWatchedLoad.uidValidity].
     *
     * ⛔ BOTH CALL SITES PASS [accountId] AND [mailboxId] BY NAME, and they must keep doing so:
     * they are two adjacent `String`s, so swapping the two names in this declaration compiles,
     * type-checks, and mints `imap:INBOX:acct-1:7` for a row cached as `imap:acct-1:INBOX:7` — no
     * lookup ever hits, no row ever shows an opening line, and the whole page is re-downloaded on
     * every walk. Named arguments bind by name, so the swap moves nothing. The source lints of
     * `PreviewFetchWiringTest` read function BODIES and can see neither this signature nor a
     * positional call's meaning; this is what covers it.
     *
     * ⭐ WHAT BOUNDS THIS READ, since it deliberately does NOT use `NotificationPreviews`' budget
     * (six reads and four seconds): the bound is STRUCTURAL, and it has to be. That budget is per
     * ACCOUNT and sized for a group summary of six children; spent over a page of two hundred rows
     * it would fill six of them and leave the rest blank — the half-true list `BodyPreview` refuses,
     * which is the very thing this change exists to avoid. What bounds it instead is the shape of
     * the work: one page at a time (`IMAP_FOLDER_PAGE`, or the pager's own page), one `UID FETCH`
     * per MIME section rather than one per message ([ImapSession.fetchSectionPartials]), each body
     * cut to [IMAP_PREVIEW_FETCH_BYTES] — and [cachedPreviewsFor], which takes out everything
     * already cached, so a folder's openings are paid for ONCE and a pull-to-refresh over the same
     * page puts nothing on the wire.
     *
     * ⛔ AND WHAT IS NOT RE-READ IS CARRIED OVER, NOT LOST ([pageOpenings]). The cost filter decides
     * what goes on the WIRE; it must never decide what goes in the ROW, because `@Upsert` replaces
     * the whole row and would blank the column for every id the filter took out — the flicker of
     * Codeberg #187. Hence the cached values come back out of this function alongside the fetched
     * ones, and a FAILED read answers [cached] rather than nothing: a socket dropped mid-walk costs
     * the openings it had not read yet, never the ones already paid for.
     */
    private suspend fun pagePreviews(
        session: ImapSession,
        accountId: String,
        mailboxId: String,
        page: List<ImapMessage>,
        uidValidity: Long?,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): Map<String, String> {
        if (cachedPreviewsFor == null || page.isEmpty()) return emptyMap()
        val ids = page.map { emailId(accountId, mailboxId, it.uid) }
        var cached = emptyMap<String, String>()
        return try {
            cached = cachedPreviewsFor(ids, uidValidity)
            pageOpenings(
                cached,
                previewsForPage(accountId, mailboxId, page, ids.toSet() - cached.keys) { section, uids ->
                    session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            android.util.Log.w("ImapPreview", "no list previews for $mailboxId: ${failed.message}")
            cached
        }
    }

    /**
     * The opening of one message's text, for a notification that is allowed to show it — or null
     * when there is none to be had. IMAP has no preview of its own: unlike JMAP, which returns one
     * with every envelope, this is a read of the message itself, and the only one the notification
     * path makes.
     *
     * [part] is what the envelope fetch already learned from the server's BODYSTRUCTURE
     * ([ImapMessage.textPart]) — the section to read and how to decode it. Null there means the
     * message has no `text/…` part at all, and this then makes NO CONNECTION: a message that is one
     * PDF has no opening line, and asking the server about it would be a round trip whose answer is
     * known.
     *
     * ⛔ It throws nothing, and that is deliberate: a preview is decoration on a notification whose
     * job is to say that mail arrived. A timeout, a refused SELECT, a folder renumbered under it —
     * every one of them costs the preview and nothing else. A cancellation is the exception, and is
     * re-thrown: it means the caller is gone, and swallowing it here would turn a cancelled push
     * pass into one that quietly continues.
     *
     * ⭐ [budgetMs] IS the timeout, and it has no default on purpose: a caller that forgot it would
     * get [ImapBudget.NO_BUDGET], i.e. `soTimeout = 0`, i.e. block until the OS gives up — minutes,
     * on a server that accepts the connection and then says nothing (a captive portal, a dead VPN).
     *
     * ⛔ And it is the ONLY thing that can bound this call. The block below is BLOCKING and holds
     * the account's single connection under its mutex, with no suspension point inside it: a
     * `withTimeout` wrapped around this from the caller would cancel the job without the thread
     * ever coming back off the socket read, and the mutex would stay taken with it. Every other
     * IMAP operation on that account queues behind this one, so the bound belongs here.
     *
     * ⭐ Through [onMailbox], like every other read that names a UID, so the folder's UIDVALIDITY is
     * checked BEFORE the FETCH goes out. A UID from a renumbered folder names another message, and
     * the notification would show the opening of somebody else's mail (Codeberg #99). No preview is
     * the right answer there, and it is the one the refusal produces.
     *
     * ⛔ The result is returned, NEVER STORED, and that is unchanged by #187: the row this pass caches
 * is written with `preview = null` ([loadWatchedFolders]). The list's opening lines are filled a
 * whole page at a time by [pagePreviews]; caching the six messages THIS path happens to read,
 * out of a page of fifty, is the half-true list `BodyPreview` refuses.
     */
    suspend fun fetchPreview(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        part: ImapTextPart?,
        budgetMs: Int,
    ): String? {
        if (part == null) return null
        return try {
            onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->
                BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            android.util.Log.w("ImapPreview", "no preview for $mailboxId:$uid: ${failed.message}")
            null
        }
    }
    private suspend fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.username,
        password = credentials.password,
        // OAuth accounts authenticate with a fresh bearer token (XOAUTH2); null = password.
        accessToken = tokenRefresher.freshAccessToken(credentials),
    )

    private fun ConnectionSecurity.toMailSecurity(): MailSecurity = when (this) {
        ConnectionSecurity.TLS -> MailSecurity.TLS
        ConnectionSecurity.STARTTLS -> MailSecurity.STARTTLS
        ConnectionSecurity.NONE -> MailSecurity.NONE
    }

    companion object {
        /**
         * Wall-clock budget for a whole-folder enumeration ([allUids]), reconnect included.
         *
         * What it really bounds, stated plainly because the number is not the wait:
         * - the TCP connect: at most this;
         * - EACH read after it: at most what is left once the connect is paid for;
         * - the reconnect-once retry: nothing extra — the second attempt draws on the same
         *   deadline and throws instead of connecting when it is spent.
         *
         * It does NOT bound their sum. A socket timeout applies per read, and the parser reads
         * through a buffer, so a 10 000-UID answer is a handful of refills: a peer that trickles
         * one byte just before each expiry can stretch the whole call to this budget times the
         * number of refills — of the order of a minute or two, not fifteen seconds. Bounding the
         * sum would mean re-arming the deadline inside the parser's read loop; that is the fix
         * if this ever bites in the field. Against the OS default, which lets such a peer run for
         * as long as it likes on a screen that already says "Trash emptied", it is still the
         * difference between minutes and never. A real Trash answers in well under a second.
         */
        const val ENUMERATE_BUDGET_MS = 15_000

        /**
         * How many of the NEWEST drafts [draftIsAlreadyThere] reads envelopes for (#95).
         *
         * One `UID FETCH` of fifty envelopes, on a link that has just failed, to avoid appending a
         * copy of text the server already holds. Fifty because the copy being looked for is the
         * LAST thing appended to the folder: a deferred upload runs minutes after the save it
         * retries, and fifty drafts written in between is already a stretch.
         *
         * ⛔ Deeper than this, the answer is "not found", i.e. APPEND. That is the direction a
         * doubt must fall: a copy too many in Drafts is visible and deletable, a draft abstained
         * over is gone — `uploadLocalDraftOnce` consumes the row either way.
         */
        const val DRAFT_LOOKUP_SCAN = 50

        /** Stable, globally-unique cache id for an IMAP message. */
        fun emailId(accountId: String, mailboxId: String, uid: Long): String = "imap:$accountId:$mailboxId:$uid"

        /** The IMAP UID encoded in a cache id, or null if not an IMAP id. */
        fun uidOf(emailId: String): Long? =
            if (emailId.startsWith("imap:")) emailId.substringAfterLast(':').toLongOrNull() else null

        /** The mailbox path encoded in a cache id (handles ':' in the path), or null. */
        fun mailboxOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts.subList(2, parts.size - 1).joinToString(":") else null
        }

        /**
         * The ACCOUNT encoded in a cache id, or null when the id is not IMAP's.
         *
         * ⭐ The segment [targetOf] throws away, and the one that carries the partition: an id is
         * only meaningful under the account it was built for, and the folder + UID it also carries
         * are perfectly valid coordinates on somebody else's server. [fetchById] compares it before
         * it dials.
         */
        fun accountOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts[1] else null
        }

        /**
         * Folder path and UID to ask the server for, from an id alone; null when the id is not
         * IMAP's (a `local-draft:` id, a JMAP id, a truncated one).
         *
         * ⚠ It says nothing about WHOSE account the id belongs to — see [accountOf]. Every caller
         * that goes to a server with this pair owes that comparison; `MailRepository.imapTarget`'s
         * six call sites already hold an id they read out of their own account's cache.
         *
         * ⭐ The ONE place that derivation is made, for both callers: [fetchById] and
         * `MailRepository.imapTarget`, which built it twice, identically. A rule applied in one
         * copy and not the other is the defect this exists to make impossible — and it is a pure
         * function so that a test can EXECUTE the decision instead of re-deriving it.
         */
        fun targetOf(emailId: String): Pair<String, Long>? {
            val mailbox = mailboxOf(emailId) ?: return null
            val uid = uidOf(emailId) ?: return null
            return mailbox to uid
        }
    }
}

/**
 * ⭐ The ONE place a listed IMAP folder becomes a cache row, for BOTH callers ([listMailboxes] and
 * [loadFolder] built it twice, identically, and a rule applied in one copy and not the other is
 * the defect this was extracted to make impossible).
 *
 * The decision it carries: a folder that CLAIMED trash or junk and lost the role to another folder
 * of the account (`ImapFolder.unelectedRole` — a personal `Trash` plus a shared `Shared/…/Trash`)
 * is stored with its role MARKED, `~trash` / `~junk` ([UNELECTED_ROLE_MARK]). That, and only that,
 * puts it in [NOT_SEARCHED_ROLES], so the second trash stays out of search results and out of the
 * FTS crawl — it used to be searched and indexed, and a cache purge could not clear it.
 *
 * ⛔ The mark does not leave `:core:data` AS A ROLE. It is erased at the crossing to the UI model
 * ([MailboxEntity.toMailbox]) and dropped from [folderRoleMap], so every screen sees a role-less
 * folder: raw name, no trash icon, renameable, deletable, never the destination of a delete. ⚠ The
 * FACT of the loss does cross, as the boolean `Mailbox.lostRoleClaim` ([isLostRoleClaim]), read by
 * `ui.inbox.visibleFolders` and by nothing else — without it "show subscribed folders only" (#174)
 * dropped the loser from every listing surface while it was already out of search, which left it
 * reachable from nowhere. That erasure is load-bearing, not tidiness — `ui.inbox.InboxScreen`
 * gates Rename and Delete on `role == null`, and a mark reaching it costs the user the ⋮ menu of
 * her shared trash.
 *
 * ⛔ A lost claim on any OTHER role stays null, exactly as before: nothing anywhere gives a `~sent`
 * a meaning, and marking one would only put an unreadable value in the cache row.
 *
 * A file-level function rather than a private method so a JVM test can EXECUTE the decision:
 * [ImapMailService] needs a session and Android, and a decision no test can run is a decision
 * nobody checks.
 */
internal fun imapMailboxEntity(accountId: String, folder: ImapFolder, index: Int): MailboxEntity {
    val role = folder.role
        ?: folder.unelectedRole
            ?.takeIf { it in NOT_SEARCHED_BASE_ROLES }
            ?.let { UNELECTED_ROLE_MARK + it }
    return MailboxEntity(
        accountId = accountId,
        id = folder.path,
        name = folder.name,
        role = role,
        // A marked role falls in the `else`, where a role-less folder already fell: the drawer's
        // order does not change for a folder that lost its claim.
        sortOrder = rolePriority(role) * 1000 + index,
        totalEmails = 0,
        unreadEmails = 0,
        // What the listing said about the subscription, copied as it is (#174). `ImapFolder`
        // defaults it to `true`, so a listing read without asking leaves the folder visible.
        isSubscribed = folder.isSubscribed,
    )
}

private fun rolePriority(role: String?): Int = when (role) {
    "inbox" -> 0
    "archive" -> 1
    "sent" -> 2
    "drafts" -> 3
    "junk" -> 4
    "trash" -> 5
    else -> 6
}

/**
 * ⭐ The ONE place an IMAP message becomes a cache row — the row the list, the offline reply and
 * the notification quick reply all read, and the only thing left of a message after process death.
 *
 * A file-level function rather than a private method, and for one reason: the repository path that
 * uses it needs Room and an Android context, so nothing inside [ImapMailService] can be reached
 * from a JVM test. WHICH envelope fields survive into the row is a decision — it was already
 * possible to carry an address all the way to this line and drop it here (`replyToJson` did
 * exactly that until this was extracted) — and a decision no test can execute is a decision
 * nobody checks. It maps values only: no session, no I/O, nothing to mock.
 *
 * It does NOT go through [EmailMapper.toEntity]: that one starts from a JMAP [Email], which this
 * side never has. The two therefore have to agree column by column, and this is the copy that a
 * new column is forgotten in.
 *
 * ⛔ [uidValidity] has NO DEFAULT, and it must not gain one: it is the numbering of the SELECT that
 * enumerated THIS message, and a default is a way of not asking the question. Without it the six
 * call sites below could each forget it silently; with it the compiler holds every one of them, and
 * a seventh read path cannot be added without stating what its rows were read under (#99). Same
 * reason `previewSources` and `folderStatedEmpty` say "No default" where they are built.
 *
 * ⛔ [preview] HAS NO DEFAULT EITHER, and for that same rule (#187). It is the opening line ALREADY
 * READ for this message by the caller — `null` where the caller read none — and a default would let
 * a read path fill every other column and quietly leave this one blank, which is the defect this
 * parameter closes. With no default the compiler holds all six call sites, so each of them has to
 * say out loud whether the rows it is about to cache carry an opening line or not; the four that
 * pass `null` write down why on the line above.
 *
 * ⛔ And nothing is DECIDED here: this line copies the value it was handed. A rule written on it
 * would be a second rule, in the one place no JVM test can execute
 * (`PreviewFetchWiringTest.the cached preview covers the whole page…`).
 */
internal fun ImapMessage.toEntity(
    accountId: String,
    mailboxId: String,
    uidValidity: Long?,
    preview: String?,
): EmailEntity {
    val id = ImapMailService.emailId(accountId, mailboxId, uid)
    return EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        threadId = null,
        subject = subject,
        preview = preview,
        receivedAt = if (dateMillis > 0) Instant.ofEpochMilli(dateMillis).toString() else null,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        flagged = flagged,
        hasAttachment = hasAttachment,
        sortKey = dateMillis,
        // Envelope recipients, persisted since schema v17 so Sent/Drafts rows can show
        // "To: …" from the cold cache (Codeberg #59/#63) — like the JMAP path in EmailMapper.
        recipientsJson = EmailRecipients.encode(
            to.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // Envelope Reply-To, same codec and same column shape as the recipients above (schema
        // v20). Without this line the address stops at [ImapMessage] and the row keeps the
        // pre-v20 answer — "reply to the From" — for every reader that only has the row: the
        // list, an offline reply, and the notification quick reply.
        replyToJson = EmailRecipients.encode(
            replyTo.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // Envelope Cc, same codec and same column shape as the two above (schema v21). Without
        // this line the copied parties stop at [ImapMessage] and every reader that only has the
        // row — a reopened draft, a reply-all, the offline and quick replies — writes to the To:
        // alone and drops them out of the conversation.
        ccJson = EmailRecipients.encode(
            cc.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // Envelope Bcc, in ITS OWN column. ⛔ Never folded into ccJson above: the whole point of
        // the second column is that a blind copy stays blind. A DRAFT this app appended does carry
        // a Bcc: header and surfaces here — that header is the only place a Cci survives a save,
        // since the draft is reopened from the server's copy (OutgoingMime.build's [BlindCopies],
        // WRITTEN by [appendDraft] and by nothing else). A message this app SENT never carries one:
        // the submitted bytes are built with the default, so what a correspondent receives names
        // no blind recipient.
        bccJson = EmailRecipients.encode(
            bcc.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // The numbering the SELECT that enumerated this message stated (schema v25, #99). Ids and
        // number come out of the SAME server response, which is what makes them impossible to
        // pair wrongly — the folder-wide record can be realigned by a background pass while these
        // ids still belong to the old numbering, and that is the accident this closes.
        //
        // ⭐ NORMALISED HERE for the row, and never at the six call sites: `ImapMailboxStatus
        // .uidValidity` is a `Long` in which 0 means "the server stated nothing", and 0 is not a
        // numbering. It becomes null — "nothing to oppose", which destroys nothing — exactly as
        // `UidValidityStore.record` refuses to write it and `MessageDestroyWorker` reads it back.
        //
        // ⭐ The rule now has a NAME and is CALLED, not spelled out again ([UidValidity.stated]).
        // Still ONE rule: what changed is that a second kind of caller needs it — the reads that
        // must not answer with rows of another numbering (`EmailDao.cachedPreviews`, #99 in #187) —
        // and a row written under one spelling and looked up under another simply never matches,
        // silently. One rule, two kinds of caller, one function.
        uidValidity = UidValidity.stated(uidValidity),
    )
}

/**
 * What an IMAP search found, and whether that is all there was: [complete] is false when a
 * folder's local attachment filter hit its scan cap before running out of candidates.
 */
data class ImapSearchHits(val messages: List<EmailEntity>, val complete: Boolean)

/**
 * A folder's UIDs and the numbering they belong to, read in the same SELECT.
 *
 * The two travel together on purpose: a list of UIDs without its UIDVALIDITY is a list of
 * numbers that may already mean something else (Codeberg #99). [uidValidity] is 0 when the
 * server reported none.
 */
data class ImapUidSnapshot(val uids: List<Long>, val uidValidity: Long)

/**
 * The IMAP-expressible part of a [SearchQuery]. `hasAttachment` is deliberately absent: IMAP
 * `SEARCH` has no key for it, so it is passed separately and applied locally. `flagged` is NOT
 * in that situation — `FLAGGED` is a standard search key — so it travels here, with the criteria
 * the server resolves itself.
 */
internal fun SearchQuery.toImapCriteria() = ImapSearchCriteria(
    text = text,
    from = from,
    recipient = recipient,
    subject = subject,
    flagged = flagged,
    afterMillis = afterMillis,
    beforeMillis = beforeMillis,
)

/**
 * Whether the IMAP walk has to fetch candidates and filter them HERE instead of letting the
 * server answer — the decision that costs an envelope FETCH per candidate, a scan cap and a
 * possibly truncated count.
 *
 * A named function rather than an expression at the call site so it can be pinned by a test:
 * this is the single line that decides whether a criterion is cheap or expensive, and widening
 * it by accident (`hasAttachment || flagged`) would be a silent, gratuitous performance
 * regression on every starred-mail search. `flagged` must NEVER appear here: `FLAGGED` is a
 * standard `SEARCH` key, so the server answers it without a single message being fetched.
 */
internal fun SearchQuery.requiresLocalScan(): Boolean = hasAttachment
