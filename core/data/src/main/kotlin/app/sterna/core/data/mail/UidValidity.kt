package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.MailboxUidValidityEntity
import app.sterna.core.data.db.PurgeSnapshotDao

/**
 * What an IMAP folder's UIDVALIDITY licenses (RFC 3501 §2.3.1.1, Codeberg #99).
 *
 * A UID identifies a message only INSIDE one numbering. When a server renumbers a folder — a
 * migration, a lost index, a restore from backup — it bumps the folder's UIDVALIDITY and every
 * UID we hold for it stops meaning what it meant: usually it means nothing, but after the
 * shifting kind of renumbering it means ANOTHER MESSAGE. That is the only case that matters
 * here, and it is the one that makes a stale id dangerous rather than merely useless.
 *
 * Pure and separate because the consequence — destroy, or refuse to — cannot be observed in an
 * integration test without a server willing to renumber a folder on demand.
 */
object UidValidity {

    /** How a recorded value compares to what the server now reports. */
    enum class Verdict {
        /** Nothing to compare: the server reported no UIDVALIDITY, or none was ever recorded
         *  as a positive number. Proceed — this is what every call did before the check existed. */
        UNVERIFIABLE,

        /** First time this folder is seen. Record and proceed. */
        FIRST_SIGHT,

        /** Same numbering: every cached UID still means what it meant. */
        SAME,

        /** Renumbered. Nothing keyed by UID may be acted on until the caches are dropped. */
        CHANGED,
    }

    fun verdict(recorded: Long?, observed: Long): Verdict = when {
        observed <= 0L -> Verdict.UNVERIFIABLE
        recorded == null -> Verdict.FIRST_SIGHT
        recorded <= 0L -> Verdict.UNVERIFIABLE
        recorded == observed -> Verdict.SAME
        else -> Verdict.CHANGED
    }

    /**
     * Whether a snapshot recorded with [snapshotUidValidity] may be destroyed at all.
     *
     * `false` for a snapshot that carries no numbering: it was written by a version that did not
     * record one, or was built from the cache with no numbering ever observed for that folder.
     * Either way the ids in it cannot be shown to still mean what they meant, and an unverifiable
     * destroy list destroys NOTHING — the same call `MessageDestroyWorker` already makes for a
     * purge enqueued before snapshots existed.
     *
     * Only asked on IMAP: a JMAP id survives everything a server can do to a mailbox, so there
     * is nothing to verify and nothing to record.
     */
    fun mayDestroy(snapshotUidValidity: Long?): Boolean = snapshotUidValidity != null && snapshotUidValidity > 0L

    /**
     * Whether a destroy may go out at all, given the numbering it was [frozen] under and the one
     * the server has JUST [stated] in the SELECT it is about to expunge under.
     *
     * [mayDestroy] asks half of this question — is there anything frozen to oppose — and that half
     * was all `ImapSession.select` could check, because it refuses only when two POSITIVE numbers
     * disagree. So a server that announced `UIDVALIDITY 42` yesterday and today answers a SELECT
     * with NO `UIDVALIDITY` line refused nothing: the frozen 42 met a 0, the comparison was skipped,
     * and `UID STORE +FLAGS (\Deleted)` + `UID EXPUNGE` went out against yesterday's UIDs. On an
     * account with no Trash the folder that takes that is the INBOX, and nothing fails visibly.
     *
     * [stated] is the argument that carries the new value: anything at or below 0 means the server
     * said nothing about this folder's numbering, and a numbering nobody states cannot confirm
     * anything. Destroy only when both numbers are real and they are the same one; everything else
     * — unstated, unfrozen, or different — refuses. The `stated > 0L` clause is a belt: a positive
     * [frozen] equal to [stated] already forces it. It stays because it says out loud which of the
     * two arguments is the new fact, and because it would still hold if the equality below were
     * ever loosened.
     *
     * ⚠ This is deliberately NOT the rule `core/imap` applies to itself: there a server announcing
     * no numbering is not turned into a refusal (`UidValidityTest`), because ten READ paths select
     * through it and refusing would make a folder unreadable on a non-conforming server. Only the
     * destroy is held to this standard, because only the destroy is irreversible.
     *
     * Free on a conforming server, and free on a server that NEVER states a numbering:
     * [UidValidityStore.record] refuses to write a value at or below 0, `TrashPurge.snapshotUidValidity`
     * refuses to freeze one, and `MessageDestroyWorker` turns 0 into null — so a frozen number above
     * 0 can only exist for a folder whose numbering the server stated at some point. A permanently
     * silent server freezes nothing, is already refused by [imapDestroyPlan], and never reaches here.
     */
    fun mayDestroyUnderStatedNumbering(frozen: Long?, stated: Long): Boolean =
        stated > 0L && frozen != null && frozen > 0L && frozen == stated

    /**
     * Which of [requested] an IMAP destroy may act on when all it has to oppose is [expected] —
     * the numbering the ids were read under, frozen with the user's confirmation.
     *
     * The IMAP twin of `TrashPurge.destroyableIds`, and it answers all-or-nothing because that is
     * what the question is: a UIDVALIDITY is a property of the FOLDER, not of an id, so either
     * every UID in the wave still means what it meant or none of them does. Everything, in the
     * order it came, when there is a numbering to oppose; nothing at all when there is not.
     *
     * "Nothing at all" is the whole point of the second half. A destroy that opposes nothing lets
     * the SELECT fall back on the number the app has recorded RIGHT NOW — which an ordinary
     * refresh has already updated if the folder was renumbered — so the guard compares that
     * number with itself, concludes SAME, and the expunge goes out against UIDs that now name
     * other, live messages. Refusing costs a re-query (the ids come back in `BulkResult.failed`,
     * the caller drops its sync cursors and the messages reappear); destroying costs the mail.
     */
    fun destroyableUnderNumbering(requested: List<String>, expected: Long?): List<String> =
        if (mayDestroy(expected)) requested else emptyList()

    /** How an IMAP destroy of a wave splits: what to expunge, by folder, and what will not be
     *  destroyed at all — the ids [destroyableUnderNumbering] refused, plus those naming no
     *  folder. [refused] belongs in `BulkResult.failed`: their rows were evicted when the
     *  hold-back started, and `failed` is what makes the caller re-query and bring them back. */
    data class ImapDestroyPlan(val byFolder: Map<String, List<String>>, val refused: List<String>)

    /**
     * [destroyableUnderNumbering] and the fan-out in one decision, so the branch that routes a
     * held-back destroy can be RUN by a test instead of restated by one: `MailRepository` needs
     * Room and a `Context`, and a routing nothing executes is a routing a mutation walks through.
     *
     * ⚠ This REVERSES, one layer up, what `core/imap` decides for itself: there, a server that
     * announces no UIDVALIDITY is deliberately not turned into a refusal (`UidValidityTest`, "a
     * server announcing no numbering is not turned into a refusal"), because refusing would break
     * the folder outright on a non-conforming server. Here it IS a refusal, and knowingly: this is
     * the only caller that EXPUNGES, the refusal is silent and costs a re-query (the ids come back
     * in `BulkResult.failed`), while proceeding costs the mail. On such a server no IMAP permanent
     * delete of a selection goes through at all — that is the assumed price of the net.
     */
    fun imapDestroyPlan(requested: List<String>, expected: Long?): ImapDestroyPlan {
        val destroyable = destroyableUnderNumbering(requested, expected)
        val grouped = destroyable.groupBy { ImapMailService.mailboxOf(it) }
        return ImapDestroyPlan(
            byFolder = grouped.filterKeys { it != null }.mapKeys { (folder, _) -> folder!! },
            refused = (requested - destroyable.toSet()) + grouped[null].orEmpty(),
        )
    }

    /**
     * One leg of an Undo: everything ONE move-back needs, and nothing that has to be looked up.
     *
     * [frozenUidValidity] is [currentFolder]'s numbering AS `COPYUID` STATED IT when the forward
     * move landed the mail there — the number [messages]' UIDs belong to, frozen with them.
     */
    data class ImapUndoRoute(
        val currentFolder: String,
        val sourceFolder: String,
        val frozenUidValidity: Long?,
        /** (UID in [currentFolder], emailId), in the order the targets came. */
        val messages: List<Pair<Long, String>>,
    ) {
        val uids: List<Long> get() = messages.map { it.first }
    }

    /**
     * How an Undo of a batch of moves splits into move-backs: by (folder the mail sits in NOW,
     * folder it goes home to, numbering that folder stated when the mail landed in it).
     *
     * ⭐ THE NUMBERING IS PART OF THE KEY, and that is the decision this function exists to make
     * executable. A UIDVALIDITY belongs to a folder AT A TIME, not to a folder: two waves moved
     * into the same Trash either side of a renumbering carry UIDs of two different numberings, and
     * a single `UID MOVE` can only oppose one of them. Merging them would mean either sending the
     * older wave's UIDs out under the newer number — the exact #99 accident, moving whatever now
     * holds those numbers — or refusing the newer wave along with the older. Splitting refuses
     * only the lot that cannot be confirmed. It costs one extra command in a case that needs a
     * renumbering mid-undo-window to happen at all.
     *
     * ⛔ An id [landedAt] knows nothing about is DROPPED, silently and without a route: no COPYUID
     * came back for it, so nothing knows where it went or under what number — the case
     * `ImapSession.move` documents as "the move still happened; only per-message Undo positioning
     * is lost". It was already lost before this function existed; it must not become a refusal
     * with a message, which would be a new noise on a path that was always mute. `restoreAll`
     * reports it exactly as it did: the id is simply not among the restored ones.
     *
     * Pure, and separate from `MailRepository`, for [imapDestroyPlan]'s reason: the repository
     * needs Room and a `Context`, so a routing left inside it is a routing no test can RUN — and
     * a routing nothing runs is a routing a mutation walks through.
     */
    fun imapUndoRoutes(
        targets: List<Pair<String, String>>,
        landedAt: (String) -> ImapLoc?,
    ): List<ImapUndoRoute> {
        val byRoute = LinkedHashMap<Triple<String, String, Long?>, MutableList<Pair<Long, String>>>()
        targets.forEach { (emailId, sourceFolder) ->
            val loc = landedAt(emailId) ?: return@forEach
            byRoute.getOrPut(Triple(loc.mailboxId, sourceFolder, loc.uidValidity)) { mutableListOf() } += loc.uid to emailId
        }
        return byRoute.map { (route, messages) ->
            ImapUndoRoute(route.first, route.second, route.third, messages.toList())
        }
    }

    /**
     * The `LIKE` pattern matching every cached id of one IMAP folder, with SQL's own wildcards
     * escaped: a folder called `a_b` would otherwise match `axb` too and take a neighbour's
     * cached bodies with it. Backslash first, or it would escape the escapes.
     */
    fun bodyCacheIdPrefix(accountId: String, mailboxId: String): String =
        (ImapMailService.emailId(accountId, mailboxId, 0L).dropLast(1))
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
}

/**
 * Where a forward IMAP move put a message, as `MailRepository.lastImapMove` holds it for the
 * Undo — the destination folder, the UID the message got there, and THAT FOLDER'S NUMBERING at
 * that instant, all three as one `COPYUID` stated them (Codeberg #99).
 *
 * ⭐ [uidValidity] is a freeze, never a reading. The Undo is a move OUT of [mailboxId], so
 * [mailboxId]'s numbering is what it must oppose — and the app has no other way to know it: a
 * `UID MOVE` never SELECTs its destination, and nothing else in the flow does either, so the
 * value recorded for a Trash the user has never opened is null and the Undo of an ordinary delete
 * was refused outright. Null here still means "nothing to oppose", which refuses; it is what a
 * server that reports no COPYUID leaves behind.
 *
 * In memory only, exactly like the map that holds it: an Undo does not survive the process.
 */
data class ImapLoc(val mailboxId: String, val uid: Long, val uidValidity: Long?)

/**
 * The server did not confirm [mailbox]'s numbering, so a destroy frozen under [expected] was
 * refused before a single command naming a UID went out (Codeberg #99).
 *
 * Distinct from `ImapUidValidityChanged` ON PURPOSE, and it is not a subclass of it: that one
 * means "the folder WAS renumbered", is caught by `ImapMailService.onMailbox` and invalidates the
 * folder — dropping its cached bodies AND every pending purge snapshot it holds. Doing that here
 * would let a momentarily quiet server throw away an "Empty trash" the user had confirmed, which
 * is a destructive write nobody asked for. This one means "the server said nothing, so nothing is
 * confirmed": it destroys nothing, drops nothing, and asks to be tried again later.
 *
 * Not an `ImapException` either, for the same reason `ImapUidValidityChanged` is not: the
 * reconnect-and-retry of `ImapMailService.withSession` must not absorb it. It is raised AFTER the
 * session block returns, so that retry is never even entered.
 */
class ImapNumberingUnconfirmed(
    val mailbox: String,
    val expected: Long?,
) : Exception("The server stated no UIDVALIDITY for $mailbox; a destroy frozen under $expected was refused")

/**
 * Where the per-folder UIDVALIDITY lives, seen from [ImapMailService] — which has a connection
 * and no database.
 *
 * [None] is the do-nothing implementation: it never remembers anything, so [recorded] always
 * answers null and every SELECT goes out unverified, exactly as before the check existed. It is
 * the default so that a test (or any caller building the service by hand) is not forced to bring
 * a database along.
 */
interface UidValidityStore {

    /** What was last observed for this folder, or null if it was never observed. */
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    /** Remember [uidValidity] as this folder's numbering. */
    suspend fun record(accountId: String, mailboxId: String, uidValidity: Long)

    /**
     * The folder was renumbered: drop what is keyed by UID and cannot heal itself, record the new
     * numbering (so the next call is not refused for the same reason), and say so.
     */
    suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long)

    /**
     * Called with `(accountId, mailboxId)` when a folder is invalidated, for the one consumer
     * outside this layer: the notification baseline, which lives in `:app`. Cleared, it makes
     * the next pass SEED that folder silently instead of announcing its whole content as new —
     * a renumbering changes every id in it, so a diff against the old baseline would notify the
     * user about mail they have already read.
     *
     * A callback rather than a flow, deliberately: `MailRepository.onAccountPruned` already does
     * exactly this for the same reason, there is no long-lived collector in the app to subscribe
     * one, and a flow nobody collects is unfinished work that reads as finished.
     */
    var onRenumbered: ((String, String) -> Unit)?

    object None : UidValidityStore {
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = null
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override var onRenumbered: ((String, String) -> Unit)? = null
    }
}

/**
 * The database-backed store, and the one place that says what a renumbering INVALIDATES.
 *
 * What it drops, and only this:
 * - the cached bodies of that folder, because nothing else ever prunes them by folder and a body
 *   read back under a recycled UID renders the wrong message under the right header;
 * - every pending destroy list of that folder, because a frozen list of ids that no longer mean
 *   anything is not an order to be carried out — dropping it is "destroy nothing", which is the
 *   whole point.
 *
 * What it deliberately does NOT do: re-read the folder to catch up. That is bug #99 itself. Flags
 * and moves are left alone too — the list cache is replaced from the server on the next refresh,
 * so those heal on their own.
 *
 * The notification baseline lives in `:app` and this layer does not reach into it: it is told,
 * through [onRenumbered], and does its own clearing.
 */
class MailboxUidValidityStore(
    private val dao: MailboxUidValidityDao,
    private val bodies: EmailBodyDao,
    private val purgeSnapshots: PurgeSnapshotDao,
) : UidValidityStore {

    override var onRenumbered: ((String, String) -> Unit)? = null

    override suspend fun recorded(accountId: String, mailboxId: String): Long? =
        dao.recorded(accountId, mailboxId)

    override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
        if (uidValidity <= 0L) return // the server reported none: nothing to remember
        dao.record(MailboxUidValidityEntity(accountId, mailboxId, uidValidity))
    }

    override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
        bodies.deleteForIdPrefix(accountId, UidValidity.bodyCacheIdPrefix(accountId, mailboxId))
        purgeSnapshots.deleteForMailbox(accountId, mailboxId)
        record(accountId, mailboxId, uidValidity)
        // Best effort, and last: the app-layer hook must never cost the invalidation itself.
        onRenumbered?.let { hook -> runCatching { hook(accountId, mailboxId) } }
    }
}
