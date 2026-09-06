package app.sterna.push

import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.CancellationException

/**
 * WHEN a notification pass may go and read the opening of a message, HOW MANY it may read, and for
 * HOW LONG — and the guarantee that none of it can ever cost a notification.
 *
 * Pure and injectable (a clock, a numbering reader and a fetch, all handed in) because none of the
 * chain it sits in can be built on the JVM: [NewMailNotifier] needs an Application and a
 * NotificationManager, the repository needs a database and a socket. The DECISIONS are here, and
 * they are executed by `NotificationPreviewGatheringTest` — the wiring that plugs them in is the
 * only part read as source text.
 *
 * Only IMAP ever comes through here. A JMAP envelope already carries `preview`, it is stored, and
 * it is in the notifier's hand offline too — so the setting alone decides whether it is shown, and
 * nothing is fetched. That asymmetry is deliberate and is not smoothed over: on IMAP an opening
 * line depends on the network and can simply be missing.
 */
object NotificationPreviews {

    /**
     * The wall clock for ONE ACCOUNT'S WHOLE PASS — every watched folder of it included, and not
     * per message, not per folder.
     *
     * Four seconds, against the 30 s a JMAP read is given: this runs while mail that has already
     * arrived is waiting to be announced, and a notification that turns up late is worth less than
     * one with no opening line in it. It is enough for a round trip on a session that is already
     * open, which is what a push pass has just used. What has not arrived when it runs out is not
     * waited for — the pass posts with what it has.
     */
    const val BUDGET_MS = 4_000

    /**
     * How many messages ONE ACCOUNT'S PASS may read for a preview — its watched folders sharing
     * the count, not one each.
     *
     * Six, which is how many children the group summary lists ([Notifications.notifyGroupSummary]
     * takes six): past that the reader is looking at the summary, not at the messages, so the seventh
     * preview is paid for on the radio and never read. A burst of fifty arrivals would otherwise be
     * fifty round trips on one radio wake-up, on a battery the user is not spending for this.
     */
    const val MAX_PER_PASS = 6

    /**
     * THE SIX READS AND THE FOUR SECONDS OF ONE ACCOUNT'S PASS, held in one place so its
     * FOLDERS SHARE THEM.
     *
     * A diff pass is per folder ([NewMailNotifier.notifyDiff]), and a delivery pass walks the
     * account's inbox plus every watched folder ([FetchAndNotify.run]). A budget built inside the
     * diff would therefore be built again for each of them: an account watching three extra
     * folders would spend 4 × 6 = 24 reads and up to 4 × 4 s = 16 s of BLOCKING IMAP work, under
     * that account's single mutex, on one radio wake-up — four times what was decided, for someone
     * who may well have asked to spare their battery. One of these is made per account pass and
     * handed down: the second folder inherits what the first one left.
     *
     * The account, and not something wider, because that is exactly the unit of the mutex and of
     * the wake-up: two accounts share neither connection nor lock, so each gets its own.
     *
     * The clock starts at the FIRST READ, not when this is built: the folder refresh that
     * precedes the diffs is itself seconds of network, and a budget armed before it would already
     * be spent by the time the first opening line is asked for.
     *
     * Not suspending, and holding no lock: it is consulted between the gathering and the posting
     * of a notification batch, where a suspension point is what would let a cancellation land
     * between the notifications and the baseline that remembers them.
     */
    class PreviewBudget(private val nowMs: () -> Long) {
        private var deadline: Long? = null
        private var reads: Int = MAX_PER_PASS

        /**
         * The deadline the next read may have, in ms — or null when this account's pass may read
         * no more, either because the six are spent or because the four seconds are.
         *
         * Never zero and never negative: `budgetMs = 0` is `ImapBudget.NO_BUDGET`, i.e.
         * `soTimeout = 0`, i.e. block until the OS gives up. An exhausted budget stops the pass;
         * it is never handed on as "no timeout".
         */
        fun claim(): Int? {
            if (reads <= 0) return null
            val end = deadline ?: (nowMs() + BUDGET_MS).also { deadline = it }
            val left = (end - nowMs()).coerceAtMost(BUDGET_MS.toLong())
            if (left <= 0L) return null
            reads--
            return left.toInt()
        }
    }

    /**
     * The openings this pass may show, keyed by message id — for the [ids] it was given, in order,
     * as far as [MAX_PER_PASS] and [BUDGET_MS] allow. Absent means "no preview": every caller has
     * to degrade to the subject alone, and none of them may treat it as a failure.
     *
     * NOTHING HAPPENS unless the user asked for [NotificationContent.BODY_PREVIEW]. Not one
     * round trip, not one byte, not one read of the numbering: somebody who chose "no details" — or
     * who is on a battery saver — must not pay a round trip per message for a line they have said
     * they do not want to see.
     *
     * IT THROWS NOTHING but a cancellation, and that is the whole point of it. Its caller posts
     * notifications in a loop and only afterwards advances the folder's baseline: an exception
     * escaping from here would skip that baseline, the next pass would re-announce every message of
     * the folder, and a re-announcement MOVES MAIL server-side through `unarchiveThreadsOnReply`.
     * A failed preview costs the preview.
     *
     * AND IT STOPS DEAD at a renumbering. [numbering] is re-read after every fetch, because a
     * refused SELECT leaves no other trace: `fetchPreview` swallows it, and the invalidation
     * RECORDS the new numbering on its way out — so the next message of the same pass would pass
     * the check with a UID from the old numbering and put the opening of a stranger's mail on the
     * lock screen (Codeberg #99).
     *
     * AND IT READS NOTHING AT ALL when the numbering is unreadable or absent TO BEGIN WITH.
     * "Different from what it was" cannot be asked when there is no what-it-was: every later read
     * would compare equal, and the guard above would read as armed while being inert. Absent is
     * not a rarity either — `recorded` is null for a folder whose server states no UIDVALIDITY at
     * the SELECT, and that is precisely the folder `onMailbox` cannot refuse either, having nothing
     * to check against. Both guards are down together, so this is the one path by which another
     * message's opening could reach a lock screen. No preview is the cheap side of that choice.
     *
     * [ids] are the messages about to be announced, in the order they will be; [sources] says which
     * of them have readable text at all — one with none is skipped WITHOUT consuming a read, since
     * it costs no round trip. [budget] is the six reads and four seconds of the whole account pass
     * ([PreviewBudget]), shared with the pass's other folders and injected so it can be tested
     * without waiting four seconds.
     */
    suspend fun gather(
        content: NotificationContent,
        ids: List<String>,
        sources: Map<String, PreviewSource>,
        budget: PreviewBudget,
        numbering: suspend () -> Long?,
        fetch: suspend (PreviewSource, Int) -> String?,
    ): Map<String, String> {
        if (content != NotificationContent.BODY_PREVIEW) return emptyMap()
        val targets = ids.mapNotNull { id -> sources[id]?.let { id to it } }
        if (targets.isEmpty()) return emptyMap()
        val numberedAtStart = rescued { numbering() } ?: return emptyMap()
        val gathered = LinkedHashMap<String, String>()
        for ((id, source) in targets) {
            // What the ACCOUNT'S pass has left, folders and clock together: the six and the four
            // seconds are claimed from one place, so a second watched folder cannot start afresh.
            val budgetMs = budget.claim() ?: break
            rescued { fetch(source, budgetMs) }?.let { gathered[id] = it }
            if (rescued { numbering() } != numberedAtStart) break
        }
        return gathered
    }

    /**
     * Post [mail], each with the opening line [gather] found for it — ALL of them gathered
     * BEFORE the first one is posted.
     *
     * THE ORDER IS THE POINT, and it is the whole reason this function exists rather than a
     * fetch inside the caller's loop:
     *
     *  - a fetch inside the loop puts a network round trip between two notifications, so an
     *    exception from it lands in the middle of a half-posted batch, skips the baseline the
     *    caller advances afterwards, and the next pass re-announces the whole folder — which,
     *    through `unarchiveThreadsOnReply`, MOVES MAIL back out of the archive server-side. That is
     *    the one way this feature can lose data;
     *  - and the budget would restart per message, so the four seconds would be four seconds EACH.
     *
     * Nothing is posted and then rewritten, either: a notification amended a second later
     * flickers, and mail announced late is no longer news. The opening line is waited for before
     * the notification, or it is not shown at all.
     *
     * The rescue around [gather] is deliberately a second one — [gather] already swallows its own
     * failures — because what it protects is not the preview but the baseline behind it.
     */
    suspend fun <T> postGatheredFirst(
        mail: List<T>,
        keyOf: (T) -> String,
        gather: suspend () -> Map<String, String>,
        post: (T, String?) -> Unit,
    ) {
        val gathered = rescued { gather() }.orEmpty()
        mail.forEach { post(it, gathered[keyOf(it)]) }
    }

    /**
     * [block]'s answer, or null if it failed. A cancellation is not a failure — it means the pass
     * itself is gone — and goes through, or a cancelled push would quietly carry on notifying.
     */
    private suspend fun <T> rescued(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        null
    }
}
