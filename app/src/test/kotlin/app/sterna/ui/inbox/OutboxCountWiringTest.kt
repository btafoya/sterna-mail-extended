package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, same instrument and the same disclaimer as
 * [ConversationScopeWiringTest]: it reads source files as text, proves nothing about what appears on
 * screen, and would still pass if the code it points at were broken inside.
 */
class OutboxCountWiringTest {

    // -- the repository: two flows over one table, which must not become one -----------------

    @Test fun `the menu's count is counted straight off the rows, with no clock in the way`() {
        val body = body(MAIL_SENDER, "outboxQueuedCount")
        assertTrue(
            "MailSender.outboxQueuedCount must map $BADGE_ITEMS through OutboxLogic.queuedCount " +
                "— the count with no grace, which is what #70 asked for. Body was:\n$body",
            QUEUED_COUNT.containsMatchIn(body),
        )
        assertTrue(
            "outboxQueuedCount must not go through OutboxLogic.badgeCount or activeCount: that is " +
                "the dot's graced count, and handing it to the menu entry reverts #70 outright — " +
                "the message the reporter queued offline goes back to being announced nowhere for " +
                "thirty seconds. The suite stays green through that edit; this rule is what does " +
                "not. Body was:\n$body",
            "badgeCount" !in body && "activeCount" !in body,
        )
        assertTrue(
            "outboxQueuedCount must consult no clock at all — a wall clock read here is the same " +
                "grace under another name. Body was:\n$body",
            "currentTimeMillis" !in body,
        )
    }

    @Test fun `the dot keeps the graced count`() {
        // No screen reads `outboxActiveCount`. The rule keeps its grace (#82) for any dot that
        // reads it: a dot is in view whether or not the user went looking.
        val body = body(MAIL_SENDER, "outboxActiveCount")
        assertTrue(
            "MailSender.outboxActiveCount must stay on OutboxLogic.badgeCount — the count that " +
                "waits out BADGE_GRACE_MILLIS before it lights the dot (#82). Body was:\n$body",
            "OutboxLogic.badgeCount(" in body && "queuedCount" !in body,
        )
    }

    @Test fun `the list and the count filter the same table through the same predicate`() {
        val body = body(MAIL_SENDER, "outboxFlow")
        assertTrue(
            "MailSender.outboxFlow must be $ALL_ROWS filtered by OutboxLogic.isWaitingInOutbox " +
                "and by NOTHING ELSE. The number the menu announces is meant to be, by " +
                "construction, the number of lines this list will show; a second exclusion here " +
                "(FAILED, say) leaves every count test green while the menu reads 2 and the screen " +
                "it opens lists 1, and dropping the filter altogether puts the row held in the " +
                "composer back on screen under a count that excludes it. Body was:\n$body",
            OUTBOX_FLOW.containsMatchIn(body),
        )
    }

    // -- the ViewModel: the immediate count, and no dot to feed ---------------------------------

    @Test fun `the ViewModel feeds the Outbox entry from the immediate count, and no dot`() {
        val dot = codeLines(INBOX_VIEW_MODEL).filter { Regex("""\b(val|var|fun)\s+$DOT\b""").containsMatchIn(it) }
        assertEquals(
            "InboxViewModel must declare no $DOT: the Outbox lives in the drawer, the overflow " +
                "button carries no outbox dot, and a graced count nobody draws is the " +
                "flow the next edit wires back onto the Outbox entry. Declarations found:\n" +
                dot.joinToString("\n"),
            emptyList<String>(),
            dot,
        )
        val entry = body(INBOX_VIEW_MODEL, MENU_COUNT)
        assertTrue(
            "InboxViewModel.outboxQueuedCount, the drawer entry's count, must come from " +
                "repo.sending.outboxQueuedCount(). On outboxActiveCount() it reverts #70 one layer " +
                "above the repository, where the rules on MailRepository cannot see it. " +
                "Body was:\n$entry",
            "repo.sending.outboxQueuedCount(" in entry && "outboxActiveCount" !in entry,
        )
    }

    // -- the screen: which count each indicator draws ------------------------------------------

    @Test fun `the overflow button carries no outbox dot, and the filters' dot only where it holds them`() {
        val (arguments, content) = overflowButton()
        assertEquals(
            "the overflow button must draw its icon and, only when filter and sort are folded into " +
                "its menu beside a reader, the filters' dot and state, read from menu.active. The " +
                "Outbox is no longer in that menu, so an outbox dot there points at an entry that is " +
                "not behind it; on the phone the filters are shown by the funnel's own dot, so a " +
                "filter dot there repeats it in a place that does not change them. Button was:\n" +
                "$arguments\n$content",
            listOf(OVERFLOW_ARGUMENTS, OVERFLOW_CONTENT),
            listOf(arguments, content),
        )
    }

    @Test fun `the Outbox entry in the drawer is drawn from the immediate count`() {
        val label = outboxDrawerEntry()
        assertTrue(
            "the Outbox NavigationDrawerItem must print $MENU_COUNT, the count with no grace. " +
                "This is the fix for #70 itself: the entry is only seen when the drawer is opened, " +
                "and opening it is looking on purpose, so there is nothing to hold back. On a " +
                "graced count it says nothing for thirty seconds after a message is queued, which " +
                "is the report. Entry was:\n$label",
            MENU_READ.containsMatchIn(label) && !DOT_READ.containsMatchIn(label),
        )
        val menu = callArguments(code(INBOX_SCREEN), "DropdownMenuItem").filter { OUTBOX_LABEL in it }
        assertEquals(
            "no DropdownMenuItem may be labelled $OUTBOX_LABEL: the Outbox is opened from the " +
                "drawer. Found:\n${menu.joinToString("\n")}",
            emptyList<String>(),
            menu,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The overflow button's arguments and its content lambda, found by the `onClick` that opens
     *  the menu: a second such button fails here instead of being skipped. Whitespace collapsed. */
    private fun overflowButton(): Pair<String, String> {
        val text = code(INBOX_SCREEN).replace(Regex("""\s+"""), " ")
        val opens = Regex(Regex.escape(OVERFLOW_OPENS)).findAll(text).map { it.range.first }.toList()
        assertEquals(
            "InboxScreen is expected to open the overflow menu from exactly one '$OVERFLOW_OPENS'. " +
                "Found ${opens.size}.",
            1, opens.size,
        )
        val call = text.lastIndexOf("IconButton(", opens.single())
        val arguments = balanced(text, call, '(', ')')
        val afterArguments = text.indexOf(')', text.indexOf(arguments, call) + arguments.length) + 1
        return arguments to balanced(text, afterArguments, '{', '}')
    }

    /** The drawer entry that opens the Outbox, found by the string it labels itself with, not by
     *  its position among the entries. */
    private fun outboxDrawerEntry(): String {
        val entry = callArguments(code(INBOX_SCREEN), "NavigationDrawerItem")
            .filter { OUTBOX_LABEL in it }
        assertEquals(
            "exactly one NavigationDrawerItem must be labelled $OUTBOX_LABEL: the entry that opens " +
                "the Outbox, whose count is the whole of #70. Found ${entry.size}.",
            1, entry.size,
        )
        return entry.single()
    }

    /**
     * The lines of [file] that are code, with their comments taken off, a line whose first
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** [file]'s code as one string: rules match across it, so a call the formatter wraps over four
     *  lines reads the same as one that fits on a line today. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     * first line indented no deeper than the declaration, once its own brackets have closed.
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** The argument text of every call to [name] in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    companion object {
        /** The graced count (#82), which no longer has a dot to draw on this screen. */
        private const val DOT = "outboxCount"

        /** The Outbox entry's count: immediate, only visible once the drawer was opened (#70). */
        private const val MENU_COUNT = "outboxQueuedCount"

        /** Read as a whole word, `outboxQueuedCount` must not answer for `outboxCount`. */
        private val DOT_READ = Regex("""\b$DOT\b""")
        private val MENU_READ = Regex("""\b$MENU_COUNT\b""")

        private const val BADGE_ITEMS = "outboxDao.observeBadgeItems()"
        private const val ALL_ROWS = "outboxDao.observeAll()"

        /** The count with no clock: the rows of the badge query, counted by the shared predicate. */
        private val QUEUED_COUNT = Regex(
            """outboxDao\.observeBadgeItems\(\s*\)\s*\.map\s*\{\s*OutboxLogic\.queuedCount\(""",
        )

        /**
         * The list, pinned by shape and not by presence: `isWaitingInOutbox` being named somewhere
         */
        private val OUTBOX_FLOW = Regex(
            """outboxDao\.observeAll\(\s*\)\s*\.map\s*\{\s*rows\s*->\s*rows\.filter\s*\{\s*""" +
                """OutboxLogic\.isWaitingInOutbox\(\s*it\.state\s*,?\s*\)\s*}\s*}""",
        )

        private const val OVERFLOW_OPENS = "onClick = { overflowOpen = true }"

        /** The overflow button's arguments, whole: lit and named only where it holds the filters. */
        private const val OVERFLOW_ARGUMENTS =
            "onClick = { overflowOpen = true }, " +
                "modifier = if (listBesideReader && menu.active) Modifier.semantics { stateDescription = filterOnLabel } else Modifier,"

        /** The overflow button's content, whole: the icon, and no badge but the filters' dot. */
        private const val OVERFLOW_CONTENT =
            "val dotColor = LocalContentColor.current " +
                "BadgedBox(badge = { if (listBesideReader && menu.active) Badge(containerColor = dotColor) }) { " +
                "Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more)) }"

        /** The label the Outbox drawer entry is found by. */
        private const val OUTBOX_LABEL = "R.string.inbox_outbox"

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val SENDER_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailSender.kt"

        /** Repo root, walked up from the module's working directory, the rules read both modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val MAIL_SENDER: File by lazy { File(root, SENDER_PATH) }
    }
}
