package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailLabels
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime

/** What a move between two accounts came to (#189): a copy confirmed on B, then the original trashed on A. */
sealed class CrossAccountMove {
    object Moved : CrossAccountMove()

    data class CopiedNotRemoved(val reason: String) : CrossAccountMove()

    /** Nothing was confirmed written, though a copy may exist on B; A's original is untouched. */
    data class Failed(val reason: String) : CrossAccountMove()
}

interface CrossAccountMoveSteps {
    fun checkSourceConfigured()

    fun checkTargetConfigured()

    suspend fun readSource(): ByteArray

    /** Write the copy into B's folder. The id B gave the copy, or null when B named nothing (an
     *  IMAP server without UIDPLUS), that is not a failed write; a refused write throws. */
    suspend fun writeTarget(bytes: ByteArray): String?

    suspend fun findTarget(): String?

    fun markTargetLocalMove(targetId: String)

    /** Move A's original to A's bin; throws when that could not be done. Never a destroy. */
    suspend fun trashSource()

    suspend fun refreshTargetFolder()
}

/** The order of a move, and the verdict each failure is. A copy that cannot be named stops at
 *  [CrossAccountMove.CopiedNotRemoved]; the copy is marked as our own move on B before A's goes. */
suspend fun crossAccountMove(steps: CrossAccountMoveSteps): CrossAccountMove {
    runCatching {
        steps.checkSourceConfigured()
        steps.checkTargetConfigured()
    }.exceptionOrNull()?.let { refused ->
        // AccountGoneException IS a CancellationException. Here nothing has been written yet and
        // the user asked for a verdict on a gesture, so it is the one cancellation that becomes one.
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

/** An allow-list: never `$draft`, B would show a received message as a draft, nor a spam verdict,
 *  B's own. A label that is not an IMAP atom would make the whole APPEND malformed. */
private val CARRIED_FLAGS = listOf(
    "\$seen" to "\\Seen",
    "\$flagged" to "\\Flagged",
    "\$answered" to "\\Answered",
)

private val CARRIED_KEYWORDS = listOf(ReplyMark.FORWARDED.jmapKeyword to ReplyMark.FORWARDED.imapFlag)

data class ImapAppendFlags(val system: List<String>, val keywords: List<String>) {
    fun joined(): String = (system + keywords).joinToString(" ")
}

fun appendReplaysWithoutKeywords(failure: Throwable, flags: ImapAppendFlags): Boolean {
    if (flags.keywords.isEmpty()) return false
    return (failure as? ImapException)?.taggedStatus?.uppercase(java.util.Locale.ROOT) in SERVER_REFUSALS
}

private val SERVER_REFUSALS = setOf("NO", "BAD")

fun imapFlagsOf(keywords: Map<String, Boolean>): ImapAppendFlags = ImapAppendFlags(
    system = CARRIED_FLAGS.filter { keywords[it.first] == true }.map { it.second },
    keywords = CARRIED_KEYWORDS.filter { keywords[it.first] == true }.map { it.second } +
        labelKeywordsOf(keywords).filter(::isImapAtom),
)

fun jmapKeywordsOf(keywords: Map<String, Boolean>): Set<String> =
    ((CARRIED_FLAGS + CARRIED_KEYWORDS).map { it.first }.filter { keywords[it] == true } + labelKeywordsOf(keywords)).toSet()

private fun labelKeywordsOf(keywords: Map<String, Boolean>): List<String> =
    keywords.filter { (key, held) -> held && key.startsWith(EmailLabels.PREFIX) }.keys.sorted()

internal fun isImapAtom(word: String): Boolean =
    word.isNotEmpty() && word.all { it in '\u0021'..'\u007e' && it !in "(){%*\"\\]" }

/** The INTERNALDATE an APPEND on B takes for A's [receivedAt] (RFC 3339), as epoch millis. Never
 *  throws: an unreadable date is "no date", B stamps the moment of the copy, not a failed move. */
fun internalDateOf(receivedAt: String?): Long? =
    receivedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** The name a copy of [email] is looked for by on B when B did not name it: its `Message-ID`, from
 *  the [Email] when it carries exactly one, else from the [source]; null when neither does. */
internal fun messageIdOf(email: Email, source: ByteArray): String? {
    email.messageId.singleOrNull()?.trim()?.removeSurrounding("<", ">")?.takeIf { it.isNotBlank() }?.let { return it }
    return MimeParser.rawHeaders(String(source, Charsets.ISO_8859_1))
        .filter { it.first.equals("Message-ID", ignoreCase = true) }
        .singleOrNull()?.second?.trim()?.removeSurrounding("<", ">")?.takeIf { it.isNotBlank() }
}

/** [email] with its keywords and date from the [cached] row when it has none, so what the copy is
 *  written under does not depend on which object the caller held. */
internal fun withCachedFallback(email: Email, cached: Email?): Email = email.copy(
    keywords = email.keywords.ifEmpty { cached?.keywords.orEmpty() },
    receivedAt = email.receivedAt ?: cached?.receivedAt,
)
