package app.sterna.ui.compose

import app.sterna.core.data.mail.MailSender
import app.sterna.core.data.mail.OriginalMark
import app.sterna.core.data.mail.ReplyMark
import app.sterna.send.composeDraftOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Which original a send marks answered or forwarded (#259), and how that answer travels from the
 * composer to the outbox row. The first half executes the decisions; the second reads source text,
 * the last resort, for the ViewModel, the scheduled worker and the quick-reply receiver, none of which
 * a JVM test can build. Those are pinned as whole code lines, arguments included.
 *
 * Not covered: a reply reopened from a saved draft, local or on the server, carries no original and
 * marks nothing; nor does a scheduled message cancelled back into a draft.
 */
class ReplyMarkWiringTest {

    // --- executed --------------------------------------------------------------------------------

    @Test fun `a reply and a reply to all mark the original answered`() {
        assertEquals(ReplyMark.ANSWERED, composeOpening("reply").mark)
        assertEquals(ReplyMark.ANSWERED, composeOpening("replyAll").mark)
        assertEquals(ReplyMark.ANSWERED, composeOpening(null).mark)
    }

    @Test fun `both forwards mark the original forwarded`() {
        assertEquals(ReplyMark.FORWARDED, composeOpening("forward").mark)
        assertEquals(ReplyMark.FORWARDED, composeOpening("forwardAttachment").mark)
    }

    private fun outboxDraft(originalMark: OriginalMark?) = MailSender.OutboxDraft(
        to = "bob@example.org",
        cc = "",
        bcc = "",
        subject = "Fwd: Lunch",
        body = "see below",
        htmlBody = null,
        fromAccountId = "accA",
        fromEmail = "alex@masto.top",
        attachments = emptyList(),
        inReplyTo = emptyList(),
        references = emptyList(),
        pgpMode = null,
        draftEmailId = null,
        draftUidValidity = null,
        outboxId = 42,
        requestReceipt = false,
        originalMark = originalMark,
    )

    /** Edit from the Outbox, then Send: a new row is queued from the composer, which must still know it. */
    @Test fun `a queued forward reopened for editing still names its original`() {
        val mark = OriginalMark("accB", "imap:accB:INBOX:42", ReplyMark.FORWARDED)
        assertEquals(mark, composeDraftOf(outboxDraft(mark)).originalMark)
        assertNull(composeDraftOf(outboxDraft(null)).originalMark)
    }

    // --- read as source text ---------------------------------------------------------------------

    @Test fun `the composer fixes the original at opening and hands it to every commit`() {
        assertEquals(
            "ComposeViewModel must name the original on exactly these lines, in this order: " +
                "restored from an undone or reopened message, fixed in prepare() from the account " +
                "the composer was opened on (never the From picker's, which may move the send to " +
                "another account), and handed to the outbox row, to the record Undo reopens from, " +
                "to Undo's record of a scheduled send, and to the scheduled row's three columns.",
            listOf(
                "import app.sterna.core.data.mail.OriginalMark",
                "private var originalMark: OriginalMark? = null",
                "originalMark = d.originalMark",
                "originalMark = OriginalMark(accountId ?: credentials.id, replyToId, opening.mark)",
                "originalMark = originalMark,",
                "originalMark = originalMark,",
                "originalMark = originalMark,",
                "originalAccountId = originalMark?.accountId,",
                "originalEmailId = originalMark?.emailId,",
                "originalMark = originalMark?.mark?.name,",
            ),
            linesNaming(VIEW_MODEL_PATH),
        )
    }

    @Test fun `the scheduled row hands its original to the outbox at its hour`() {
        assertEquals(
            listOf(
                "import app.sterna.core.data.mail.originalMarkOf",
                "originalMark = originalMarkOf(row.originalAccountId, row.originalEmailId, row.originalMark),",
            ),
            linesNaming(SCHEDULED_SEND_WORKER_PATH),
        )
    }

    @Test fun `a quick reply from the notification marks the message it answers`() {
        assertEquals(
            listOf(
                "import app.sterna.core.data.mail.OriginalMark",
                "originalMark = OriginalMark(accountId, emailId, ReplyMark.ANSWERED),",
            ),
            linesNaming(NOTIFICATION_ACTION_RECEIVER_PATH),
        )
    }

    /** The trimmed code lines of [path] naming the original's mark or one of its columns, comments cut. */
    private fun linesNaming(path: String): List<String> =
        File(root, path).readLines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .map { it.substringBefore(" // ") }
            .filter { NAMES.containsMatchIn(it) }

    companion object {
        private const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCHEDULED_SEND_WORKER_PATH = "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"
        private const val NOTIFICATION_ACTION_RECEIVER_PATH = "app/src/main/kotlin/app/sterna/push/NotificationActionReceiver.kt"

        private val NAMES = Regex("[oO]riginalMark")

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
