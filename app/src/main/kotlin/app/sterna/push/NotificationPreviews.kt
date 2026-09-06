package app.sterna.push

import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.CancellationException

/**
 * WHEN a notification pass may read the opening of a message, HOW MANY it may read and for HOW LONG
 * — and the guarantee that none of it can ever cost a notification. Pure and injectable, none of the
 * chain it sits in being buildable on the JVM. Only IMAP comes through here, a JMAP envelope already
 * carrying `preview`.
 */
object NotificationPreviews {

    /** The wall clock for ONE ACCOUNT'S WHOLE PASS, every watched folder included. Four seconds:
     *  this runs while mail already arrived waits to be announced, and a late notification is worth
     *  less than one with no opening line. */
    const val BUDGET_MS = 4_000

    /** How many messages ONE ACCOUNT'S PASS may read, its folders sharing the count. Six, which is
     *  how many children the group summary lists: past that the reader is looking at the summary. */
    const val MAX_PER_PASS = 6

    /**
     * The six reads and the four seconds of ONE ACCOUNT'S PASS, held in one place so its FOLDERS
     * SHARE THEM: a budget built inside the per-folder diff would give an account watching three
     * extras 24 reads and up to 16 s of blocking IMAP work under one mutex, on one wake-up. The
     * account is the unit of both. The clock starts at the FIRST READ, the folder refresh that
     * precedes the diffs being itself seconds of network. Not suspending and holding no lock: it
     * is consulted between the gathering and the posting, where a suspension point would let a
     * cancellation land between the notifications and the baseline that remembers them.
     */
    class PreviewBudget(private val nowMs: () -> Long) {
        private var deadline: Long? = null
        private var reads: Int = MAX_PER_PASS

        /** The deadline the next read may have, or null when this pass may read no more. Never
         *  zero and never negative: `budgetMs = 0` means block until the OS gives up. */
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
     * The openings this pass may show, keyed by message id, as far as [MAX_PER_PASS] and [BUDGET_MS]
     * allow. Absent means "no preview", and no caller may treat it as a failure.
     *
     * Nothing happens unless the user asked for BODY_PREVIEW — not one round trip. It throws
     * nothing but a cancellation: its caller posts notifications in a loop and only afterwards
     * advances the baseline, so an escaping exception would make the next pass re-announce every
     * message, and a re-announcement MOVES MAIL server-side.
     *
     * And it stops dead at a renumbering: [numbering] is re-read after every fetch, a refused
     * SELECT leaving no other trace, or the next message would pass the check with a UID from the
     * old numbering and put a stranger's mail on the lock screen (#99). It reads nothing at all
     * when the numbering is unreadable or absent to begin with — the one path by which another
     * message's opening could reach a lock screen.
     *
     * [sources] says which ids have readable text; one with none is skipped WITHOUT consuming a read.
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
            // What the ACCOUNT'S pass has left, so a second watched folder cannot start afresh.
            val budgetMs = budget.claim() ?: break
            rescued { fetch(source, budgetMs) }?.let { gathered[id] = it }
            if (rescued { numbering() } != numberedAtStart) break
        }
        return gathered
    }

    /**
     * Post [mail], each with the opening line [gather] found for it — ALL of them gathered BEFORE
     * the first is posted. A fetch inside the caller's loop would put a round trip between two
     * notifications, so an exception lands mid-batch, skips the baseline advanced afterwards, and
     * the next pass re-announces the whole folder, which MOVES MAIL out of the archive server-side;
     * and the budget would restart per message. Nothing is posted and then rewritten either. The
     * rescue around [gather] is deliberately a second one: it protects the baseline, not the preview.
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

    /** [block]'s answer, or null if it failed. A cancellation is not a failure — it means the pass
     *  itself is gone — and goes through, or a cancelled push would carry on notifying. */
    private suspend fun <T> rescued(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        null
    }
}
