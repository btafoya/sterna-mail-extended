package app.sterna.ui.message

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.ShownReplyMarks
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which surfaces draw the replied and forwarded marks as shown (#259), the decision being executed
 * in `ShownReplyMarksTest`. The first test runs what the open message's header is drawn from; the
 * rest is a source lint, the last resort: neither ViewModel nor a composable is built on the JVM.
 *
 * Holes of the lint, as `CONTRIBUTING.md` numbers them: 1 (a pinned name says nothing about what it
 * is bound to), 3 (a pinned line says nothing about its place in a control structure) and 4 (nothing
 * here holds the value the composable collects). Not covered at all: the members of an unfolded
 * conversation, read from `EmailDao.cachedThreadEmails`, which draw the stored column only.
 */
class ShownReplyMarksWiringTest {

    @Test fun `the header of the open message carries its marks as shown`() {
        val opened = listOf(ThreadMessage(id = "M1", header = Email(id = "M1", accountId = "A")))

        val drawn = opened.withShownReplyMarks(mapOf(EmailKey("A", "M1") to ShownReplyMarks(answered = true, forwarded = false)))

        assertEquals(listOf(true to false), drawn.map { it.header.isAnswered to it.header.isForwarded })
    }

    @Test fun `the open message is drawn from the live marks, not from the reading taken at opening`() {
        assertEquals(
            "MessagePage must collect MessageViewModel.shownReplyMarks and hand ConversationBody the " +
                "messages with those marks on: `messages` alone is read once at opening, so a reply " +
                "sent or cancelled from the composer would not show on the header it came back to.",
            listOf(
                "val shownReplyMarks by viewModel.shownReplyMarks.collectAsStateWithLifecycle()",
                "messages = remember(messages, shownReplyMarks) { messages.withShownReplyMarks(shownReplyMarks) },",
            ),
            codeLines(MESSAGE_SCREEN).filter { "shownReplyMarks" in it },
        )
        assertEquals(
            listOf(
                "val shownReplyMarks: StateFlow<Map<EmailKey, ShownReplyMarks>> = _messages",
                ".map { list -> list.map { it.header.emailKey() } }",
                ".distinctUntilChanged()",
                ".flatMapLatest { keys -> repo.sending.observeShownReplyMarks(keys).catch { emit(emptyMap()) } }",
                ".stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())",
            ),
            codeLines(MESSAGE_VIEW_MODEL).let { lines ->
                val at = lines.indexOfFirst { it.startsWith("val shownReplyMarks:") }
                lines.subList(at, at + 5)
            },
        )
    }

    @Test fun `search results are drawn with their marks as shown`() {
        assertEquals(
            listOf(
                "import app.sterna.core.data.mail.withShownReplyMarks",
                "private val shownSearchState: Flow<SearchUi> = searchState.flatMapLatest { search ->",
                "else repo.sending.observeShownReplyMarks(results.map { it.emailKey() })",
                ".map { shown -> search.copy(results = results.map { it.withShownReplyMarks(shown) }) }",
                "val state: StateFlow<MailUi> = combine(countedState, shownSearchState, settings.sortOrder, filterToggles, connectivity.online) { base, search, sortOrder, toggles, online ->",
            ),
            codeLines(INBOX_VIEW_MODEL).filter { "shownSearchState" in it || "ShownReplyMarks" in it },
        )
    }

    @Test fun `every reader of the shown marks is woken by the outbox`() {
        val lines = codeLines(EMAIL_DAO)
        fun annotationOf(declaration: String): String {
            val at = lines.indexOfFirst { it.startsWith(declaration) }
            check(at > 0) { "EmailDao no longer declares `$declaration`" }
            return lines[at - 1]
        }
        assertEquals(
            "A read of the shown marks that does not observe OutboxEntity is not re-run when a reply " +
                "is queued, cancelled or refused: the mark would show what the outbox held when the " +
                "list was last read.",
            listOf(
                "@RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class, OutboxEntity::class])",
                "@RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class, OutboxEntity::class])",
                "@RawQuery(observedEntities = [EmailEntity::class, OutboxEntity::class])",
            ),
            listOf(
                annotationOf("fun pagingSource("),
                annotationOf("fun conversationPagingSource("),
                annotationOf("fun observeShownReplyMarks("),
            ),
        )
    }

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private companion object {
        val MESSAGE_SCREEN = repoFile("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        val MESSAGE_VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        val INBOX_VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        val EMAIL_DAO = repoFile("core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt")

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
