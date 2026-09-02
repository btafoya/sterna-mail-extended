package app.sterna.core.data.mail

import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime

/**
 * What a move between two accounts came to (#189). The gesture is a copy confirmed on B, then
 * the original trashed on A — never the other way round, and never a destroy anywhere.
 */
sealed class CrossAccountMove {
    /** The copy is on B, named, and the original is in A's bin. */
    object Moved : CrossAccountMove()

    /** The copy is on B but A's original was NOT touched: a duplicate the user can see, never a
     *  loss. Either B did not name the copy and it could not be found back, or the trash on A
     *  failed after the copy. */
    data class CopiedNotRemoved(val reason: String) : CrossAccountMove()

    /** Nothing was CONFIRMED written: an account gone, a source that could not be read, or a
     *  write B refused — and since a lost answer to an APPEND or an import B did store looks just
     *  the same from here, a copy may exist on B. A's original is untouched: a duplicate at worst,
     *  never a loss. */
    data class Failed(val reason: String) : CrossAccountMove()
}

/**
 * The seven gestures of a move between accounts, injected so that a JVM test EXECUTES the order
 * with fakes ([CrossAccountMoveTest]) — `MailRepository` itself needs Room, a `Context` and a
 * socket. `MailRepository.moveToAccount` builds the real ones and is pinned line by line
 * ([MoveToAccountWiringTest]).
 */
interface CrossAccountMoveSteps {
    /** Throws [AccountGoneException] when the source account has been signed out meanwhile. */
    fun checkSourceConfigured()

    /** Throws [AccountGoneException] when the target account has been signed out meanwhile. */
    fun checkTargetConfigured()

    /** The message's octets as A's server holds them; throws on a source that came back empty. */
    suspend fun readSource(): ByteArray

    /** Write the copy into B's folder. The id B gave the copy, or null when B named nothing
     *  (an IMAP server without UIDPLUS) — that is not a failed write; a refused write throws. */
    suspend fun writeTarget(bytes: ByteArray): String?

    /** Look the unnamed copy up on B (by its `Message-ID`): its id when exactly one message
     *  answers, null when none or several do. */
    suspend fun findTarget(): String?

    /** Tell the next refresh of B that this arrival is our own move, not new mail to announce. */
    fun markTargetLocalMove(targetId: String)

    /** Move A's original to A's bin; throws when that could not be done. ⛔ Never a destroy. */
    suspend fun trashSource()

    /** Reload B's folder so the copy shows at once; best-effort, its failure is not the move's. */
    suspend fun refreshTargetFolder()
}

/**
 * The order of a move between accounts, and the verdict each failure is — pure, executed by
 * [CrossAccountMoveTest].
 *
 * Strictly this sequence, nothing between two steps:
 *  1. both accounts still configured — else [CrossAccountMove.Failed], nothing was written
 *     (an [AccountGoneException] is a verdict HERE, see the body — nowhere later);
 *  2. the source read — else `Failed`, nothing was written;
 *  3. the copy written on B — else `Failed`: B refused, A is untouched;
 *  4. the copy's id, or B's answer to a lookup — none ⇒ [CrossAccountMove.CopiedNotRemoved] and
 *     it STOPS: without a copy that can be named, A's original is the only one whose place is
 *     known, and it stays. A duplicate, never a loss;
 *  5. the copy marked as our own move on B, BEFORE the original goes — a refresh of B that runs
 *     between the two must not announce it;
 *  6. the original trashed on A — else `CopiedNotRemoved`: the copy is there, the original too;
 *  7. B's folder reloaded, best-effort — then [CrossAccountMove.Moved].
 *
 * A cancellation propagates out of every step ([getOrElseUnlessCancelled], the house idiom): a
 * cancelled coroutine is an instruction to stop, not a verdict to report.
 */
suspend fun crossAccountMove(steps: CrossAccountMoveSteps): CrossAccountMove {
    runCatching {
        steps.checkSourceConfigured()
        steps.checkTargetConfigured()
    }.exceptionOrNull()?.let { refused ->
        // AccountGoneException IS a CancellationException, and its KDoc says why: it unwinds a
        // sync WALK that writes, silently. Here nothing has been written yet and the user asked
        // for a verdict on a gesture, so it is the one cancellation that becomes one — and only
        // at this step. Any other cancellation is an instruction to stop, as everywhere.
        if (refused is CancellationException && refused !is AccountGoneException) throw refused
        return CrossAccountMove.Failed(reasonOf(refused))
    }
    val bytes = runCatching { steps.readSource() }
        .getOrElseUnlessCancelled { return CrossAccountMove.Failed(reasonOf(it)) }
    val written = runCatching { steps.writeTarget(bytes) }
        .getOrElseUnlessCancelled { return CrossAccountMove.Failed(reasonOf(it)) }
    val targetId = written
        ?: runCatching { steps.findTarget() }.getOrElseUnlessCancelled { null }
        ?: return CrossAccountMove.CopiedNotRemoved("The copy on the target account could not be named.")
    steps.markTargetLocalMove(targetId)
    runCatching { steps.trashSource() }
        .getOrElseUnlessCancelled { return CrossAccountMove.CopiedNotRemoved(reasonOf(it)) }
    runCatching { steps.refreshTargetFolder() }.getOrElseUnlessCancelled { }
    return CrossAccountMove.Moved
}

private fun reasonOf(t: Throwable): String = t.message ?: t.javaClass.simpleName

/** The three flags a copy carries across, JMAP keyword → IMAP system flag, in this order. */
private val CARRIED_FLAGS = listOf(
    "\$seen" to "\\Seen",
    "\$flagged" to "\\Flagged",
    "\$answered" to "\\Answered",
)

/**
 * The IMAP flag list an APPEND on B takes for the [keywords] of A's message: `\Seen`, `\Flagged`,
 * `\Answered` — those that are true, in that order, space-separated — and nothing else. Not
 * `$draft`: a draft is not a message that moves between accounts, and a `\Draft` flag on a
 * received message would make B show it as one. No other keyword either: the IMAP side of this
 * app only ever reads the seen and flagged bits back (`EmailEntity.toEmail`), so an IMAP-sourced
 * message never carries `$answered` here — a JMAP-sourced one does, and B keeps it.
 */
fun imapFlagsOf(keywords: Map<String, Boolean>): String =
    CARRIED_FLAGS.filter { keywords[it.first] == true }.joinToString(" ") { it.second }

/** The same three, as the JMAP keywords an `Email/import` on B takes — those that are true. */
fun jmapKeywordsOf(keywords: Map<String, Boolean>): Set<String> =
    CARRIED_FLAGS.map { it.first }.filter { keywords[it] == true }.toSet()

/**
 * The INTERNALDATE an APPEND on B takes for A's [receivedAt] (an RFC 3339 date, as both the
 * JMAP server and the IMAP cache row write it): epoch millis, or null when there is no date or it
 * cannot be read. ⛔ Never an exception: an unreadable date is "no date" — B stamps the moment of
 * the copy — not a failed move.
 */
fun internalDateOf(receivedAt: String?): Long? =
    receivedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/**
 * The name a copy of [email] is looked for by on B when B did not name it: its `Message-ID`,
 * bracketless. From the [Email] when it carries exactly one (a JMAP server's object does), else
 * from the `Message-ID` header of the [source] just read — the IMAP cache row has no such column,
 * and the source is what was copied. Null when neither has exactly one: no name, no lookup, and
 * the move stops at "copied, not removed".
 */
internal fun messageIdOf(email: Email, source: ByteArray): String? {
    email.messageId.singleOrNull()?.trim()?.removeSurrounding("<", ">")?.takeIf { it.isNotBlank() }?.let { return it }
    return MimeParser.rawHeaders(String(source, Charsets.ISO_8859_1))
        .filter { it.first.equals("Message-ID", ignoreCase = true) }
        .singleOrNull()?.second?.trim()?.removeSurrounding("<", ">")?.takeIf { it.isNotBlank() }
}

/**
 * [email] with its keywords and date taken from the [cached] row when the object passed in has
 * none: a caller may hand over a list row or a freshly opened message, and the flags and date the
 * copy on B is written under must not depend on which.
 */
internal fun withCachedFallback(email: Email, cached: Email?): Email = email.copy(
    keywords = email.keywords.ifEmpty { cached?.keywords.orEmpty() },
    receivedAt = email.receivedAt ?: cached?.receivedAt,
)
