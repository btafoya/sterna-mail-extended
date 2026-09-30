package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. No JVM test in this repository can run a `@Composable`, so the
 * account switcher in the list's top bar (#215) is pinned as text: these rules prove that the calls
 * are *written* as given here, never that they *work* once composed.
 *
 * What it does not cover, the holes of `CONTRIBUTING.md` that apply here:
 * - A rule about the arguments says nothing about the expression around the call: the
 *   `onSwitchAccount(account.id)` pinned in the menu could sit behind a condition that is never true.
 * - Textual order is not execution order: the preamble is pinned just above the bar's `TopAppBar(`,
 *   which says where `titleSwitchTargets` is declared, not that nothing reassigns what it reads.
 * - Nothing here holds a value at the point the composable collects it: `titleSwitchTargets` is
 *   pinned as written, and `accounts` or `currentAccountId` could still be bound to something else.
 *
 * The single copy of the title rests on the bar being a fixed `TopAppBar`, which composes its title
 * slot once. A collapsing bar composes it once per row, and this switcher would then open its menu
 * on both copies, the hidden one included; the rule on the bar's name is what stands for that.
 *
 * That a tap opens the menu under the title is only ever seen on a device.
 */
class AccountSwitchTitleWiringLintTest {

    @Test fun `the other accounts are listed right above the fixed top bar`() {
        val text = codeText(INBOX_SCREEN)
        assertTrue(
            "InboxScreen.kt must declare, right before the list's TopAppBar(, the accounts the " +
                "title offers, exactly as:\n$EXPECTED_PREAMBLE\nThe list is empty in the unified " +
                "view and in the unread view of every account, where there is no current account " +
                "to switch away from, and it leaves the current account out, in the order of `accounts`.",
            text.contains(EXPECTED_PREAMBLE),
        )
        val bar = callAt(text, barStart(text))
        assertFalse(
            "the list's top bar is fixed: a scroll behaviour would make it a collapsing bar again. " +
                "Call was:\n$bar",
            bar.contains("scrollBehavior"),
        )
    }

    @Test fun `the fixed bar grows with the title's two lines`() {
        val bar = callAt(codeText(INBOX_SCREEN), barStart(codeText(INBOX_SCREEN)))
        val hits = Regex("""\bexpandedHeight\s*=""").findAll(bar).toList()
        assertEquals(
            "the list's top bar must set expandedHeight exactly once, or the account line under the " +
                "folder name is clipped at large font sizes. Call was:\n$bar",
            1,
            hits.size,
        )
        assertTrue(
            "the list's top bar must take its height from inboxBarHeight, fed the two title " +
                "styles' line heights and the current density, which carries the font scale:\n" +
                "$EXPECTED_BAR_HEIGHT\nCall was:\n$bar",
            bar.contains(EXPECTED_BAR_HEIGHT),
        )
    }

    @Test fun `the title slot wraps its content in the switcher`() {
        val title = titleBlock(codeText(INBOX_SCREEN))
        val hits = Regex("""\bAccountSwitchTitle\s*\(""").findAll(title).toList()
        assertEquals("the title slot must call AccountSwitchTitle exactly once. Title block was:\n$title", 1, hits.size)
        assertEquals(
            "AccountSwitchTitle must be handed the accounts listed above the bar and the drawer's " +
                "own switch callback, nothing else. Title block was:\n$title",
            EXPECTED_CALL,
            callAt(title, hits.single().range.first),
        )
        assertFalse(
            "the title slot must not hold its own remember: the menu's state belongs to " +
                "AccountSwitchTitle. Title block was:\n$title",
            Regex("""\bremember\w*\s*[({]""").containsMatchIn(title),
        )
        assertTrue(
            "the chevron after the account name must be drawn under the same list as the tap " +
                "target, decorative (contentDescription = null), so it never promises a menu " +
                "that is not there. Title block was:\n$title",
            title.contains(EXPECTED_CHEVRON),
        )
    }

    @Test fun `the switcher only switches, and only when there is somewhere to go`() {
        val text = codeText(SWITCH_TITLE)
        assertTrue(
            "AccountSwitchTitle must render its content bare, with no tap target, when there is " +
                "no other account to offer. Source was:\n$text",
            text.contains(EXPECTED_GUARD),
        )
        val switches = Regex("""\bonSwitchAccount\s*\(""").findAll(text).toList()
        assertEquals("AccountSwitchTitle must call onSwitchAccount exactly once. Source was:\n$text", 1, switches.size)
        assertEquals(
            "a menu entry switches to ITS account, then closes the menu. Source was:\n$text",
            "onSwitchAccount(account.id)",
            callAt(text, switches.single().range.first),
        )
        assertTrue(
            "the choice must close the menu right after the switch. Source was:\n$text",
            text.contains("onClick = { onSwitchAccount(account.id) open = false }"),
        )
        assertFalse(
            "AccountSwitchTitle must not re-point the inbox itself: InboxScreen already follows " +
                "currentAccountId, a second call would load the account twice.",
            Regex("""\bonAccountChanged\s*\(""").containsMatchIn(text),
        )
        assertFalse(
            "AccountSwitchTitle has no drawer to close: it lives in the top bar.",
            text.contains("drawerState"),
        )
        assertTrue(
            "the tap target must say what it does to a screen reader, with the drawer chevron's " +
                "own label, read as one button, and open the menu. Source was:\n$text",
            text.contains(EXPECTED_TARGET),
        )
        assertEquals("AccountSwitchTitle must have exactly one clickable. Source was:\n$text", 1, Regex("""\.clickable\s*\(""").findAll(text).count())
    }

    @Test fun `the menu opens on its own state, never on the bar's scroll`() {
        val text = codeText(SWITCH_TITLE)
        assertEquals(
            "the only remember in AccountSwitchTitle is its menu's open state, held right after " +
                "the guard so an account with nowhere to switch holds none. Source was:\n$text",
            listOf("remember { mutableStateOf(false) }"),
            Regex("""\bremember\w*\s*[({]""").findAll(text).map { balancedFrom(text, it.range.first) }.toList(),
        )
        assertTrue(
            "the open state is declared right after the guard. Source was:\n$text",
            text.contains("$EXPECTED_GUARD $EXPECTED_STATE"),
        )
        for (leftover in listOf("collapsedFraction", "LocalTextStyle", "currentCompositeKeyHash", "openIn")) {
            assertFalse(
                "AccountSwitchTitle must not read '$leftover': on a fixed bar the title is composed " +
                    "once, and a copy that waits for a collapse fraction or a row style to know " +
                    "it is on screen never becomes a tap target, which leaves the menu dead " +
                    "without an error. Source was:\n$text",
                text.contains(leftover),
            )
        }
        val menus = Regex("""\bDropdownMenu\s*\(""").findAll(text).toList()
        assertEquals("AccountSwitchTitle must draw exactly one DropdownMenu. Source was:\n$text", 1, menus.size)
        assertEquals(
            "the menu is shown on the local state alone, and a dismissal closes it. Source was:\n$text",
            EXPECTED_MENU,
            callAt(text, menus.single().range.first),
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * Where the list's top bar starts: the first `…TopAppBar(` after the one declaration of
     * `titleSwitchTargets`, required to be the fixed `TopAppBar`. Its name is no anchor, the search
     * bar is a `TopAppBar` too.
     */
    private fun barStart(text: String): Int {
        val anchors = Regex("""\bval titleSwitchTargets\b""").findAll(text).toList()
        check(anchors.size == 1) { "InboxScreen.kt is expected to declare titleSwitchTargets exactly once, found ${anchors.size}" }
        val bar = Regex("""\b(\w*TopAppBar)\s*\(""").find(text, anchors.single().range.last)
        check(bar != null) { "no top bar follows titleSwitchTargets in InboxScreen.kt" }
        check(bar.groupValues[1] == "TopAppBar") {
            "the list's top bar must be the fixed TopAppBar, found ${bar.groupValues[1]}: a collapsing " +
                "bar composes its title once per row"
        }
        return bar.range.first
    }

    /** The list's top bar up to the brace that balances its first `{`: its `title` slot. */
    private fun titleBlock(text: String): String = balancedFrom(text, barStart(text))

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** [file] as one line of code: comments taken out, every run of whitespace collapsed to one space. */
    private fun codeText(file: File): String {
        check(file.isFile) { "${file.path} does not exist" }
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        private const val EXPECTED_PREAMBLE =
            "val titleSwitchTargets = if (!ui.unified && !ui.unreadAllAccounts) accounts.filter { it.id != currentAccountId } else emptyList() " +
                "TopAppBar("

        private const val EXPECTED_CALL =
            "AccountSwitchTitle( others = titleSwitchTargets, onSwitchAccount = onSwitchAccount, )"

        private const val EXPECTED_CHEVRON =
            "if (titleSwitchTargets.isNotEmpty()) { Icon( Icons.Filled.ExpandMore, contentDescription = null,"

        private const val EXPECTED_BAR_HEIGHT =
            "expandedHeight = inboxBarHeight( titleLineHeight = MaterialTheme.typography.titleMedium.lineHeight, " +
                "subtitleLineHeight = MaterialTheme.typography.labelMedium.lineHeight, " +
                "density = LocalDensity.current, ),"

        private const val EXPECTED_GUARD =
            "if (others.isEmpty()) { content() return }"

        private const val EXPECTED_STATE =
            "var open by remember { mutableStateOf(false) }"

        private const val EXPECTED_TARGET =
            "modifier = Modifier " +
                ".clickable(onClickLabel = stringResource(R.string.inbox_switch_account)) { open = true } " +
                ".semantics(mergeDescendants = true) { role = Role.Button },"

        private const val EXPECTED_MENU =
            "DropdownMenu(expanded = open, onDismissRequest = { open = false })"

        private const val INBOX_DIR = "app/src/main/kotlin/app/sterna/ui/inbox"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$INBOX_DIR/InboxScreen.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val INBOX_SCREEN: File by lazy { File(root, "$INBOX_DIR/InboxScreen.kt") }
        private val SWITCH_TITLE: File by lazy { File(root, "$INBOX_DIR/AccountSwitchTitle.kt") }
    }
}
