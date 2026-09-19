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
 * - Textual order is not execution order. The state is pinned just above `MediumTopAppBar(`, which
 *   says where it is declared, not that no other code resets it.
 * - Nothing here holds a value at the point the composable collects it: `titleSwitchTargets` is
 *   pinned as written, and `accounts` or `currentAccountId` could still be bound to something else.
 *
 * - Which copy is visible is decided from what `MediumTopAppBar` hands its title slot: `titleLarge`
 *   as `LocalTextStyle` in the collapsed row, `headlineSmall` in the expanded one, and the `0.5f`
 *   fraction at which it hides one row's semantics for the other. Both are read in the Material 3
 *   dependency, pinned nowhere here: a Material 3 upgrade or a theme giving both styles one font
 *   size would leave every rule green and the target on the wrong copy, or on both.
 *
 * That a tap lands on the visible copy only, and opens its menu there, is only ever seen on a device.
 */
class AccountSwitchTitleWiringLintTest {

    @Test fun `the menu state and the other accounts are held above the top bar, not in its title`() {
        val text = codeText(INBOX_SCREEN)
        assertTrue(
            "InboxScreen.kt must declare, right before MediumTopAppBar(, the menu state and the " +
                "list of accounts it offers, exactly as:\n$EXPECTED_PREAMBLE\nMediumTopAppBar " +
                "composes its title slot twice, once per row, so a remember inside the slot or " +
                "inside AccountSwitchTitle would give each copy its own state. The list is empty " +
                "in the unified view, where there is no current account to switch away from, and " +
                "it leaves the current account out, in the order of `accounts`.",
            text.contains(EXPECTED_PREAMBLE),
        )
        val title = titleBlock(text)
        assertFalse(
            "the title slot must not hold its own remember: its two copies would disagree. " +
                "Title block was:\n$title",
            Regex("""\bremember\w*\s*[({]""").containsMatchIn(title),
        )
    }

    @Test fun `the title slot wraps its content in the switcher`() {
        val title = titleBlock(codeText(INBOX_SCREEN))
        val hits = Regex("""\bAccountSwitchTitle\s*\(""").findAll(title).toList()
        assertEquals("the title slot must call AccountSwitchTitle exactly once. Title block was:\n$title", 1, hits.size)
        assertEquals(
            "AccountSwitchTitle must be handed the hoisted state and the drawer's own switch " +
                "callback, nothing computed in the slot. Title block was:\n$title",
            EXPECTED_CALL,
            callAt(title, hits.single().range.first),
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
            text.contains("onClick = { onSwitchAccount(account.id) onOpenInChange(null) }"),
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
        assertFalse(
            "AccountSwitchTitle must not hold its own open state: the title slot is composed " +
                "twice and each copy would open its own menu. Source was:\n$text",
            text.contains("mutableStateOf") || text.contains("rememberSaveable"),
        )
        assertEquals(
            "the only remember in AccountSwitchTitle is the derived visibility of its copy. Source was:\n$text",
            listOf("remember(inTopRow)"),
            Regex("""\bremember\w*\s*[({]""").findAll(text).map { callAt(text, it.range.first) }.toList(),
        )
        assertTrue(
            "the tap target must say what it does to a screen reader, with the drawer chevron's " +
                "own label, and read as one button, on the visible copy only. Source was:\n$text",
            text.contains(EXPECTED_TARGET),
        )
        assertEquals("AccountSwitchTitle must have exactly one clickable. Source was:\n$text", 1, Regex("""\.clickable\s*\(""").findAll(text).count())
    }

    @Test fun `only the copy of the title on screen is a tap target`() {
        val text = codeText(SWITCH_TITLE)
        assertTrue(
            "each copy must tell its row from the style MediumTopAppBar provides it: titleLarge " +
                "in the collapsed row, headlineSmall in the expanded one. Source was:\n$text",
            text.contains(EXPECTED_ROW),
        )
        assertTrue(
            "the collapsed row is shown from a fraction of 0.5 up, the expanded row below it, the " +
                "threshold at which Material 3 swaps the rows' semantics; derivedStateOf so a " +
                "scroll recomposes only when it crosses. Source was:\n$text",
            text.contains(EXPECTED_SHOWN),
        )
        val menus = Regex("""\bDropdownMenu\s*\(""").findAll(text).toList()
        assertEquals("AccountSwitchTitle must draw exactly one DropdownMenu. Source was:\n$text", 1, menus.size)
        assertEquals(
            "the menu opens on the visible copy only, and only on the copy that was tapped. Source was:\n$text",
            EXPECTED_MENU,
            callAt(text, menus.single().range.first),
        )
        val bar = codeText(INBOX_SCREEN).let { screen ->
            callAt(screen, Regex("""\bMediumTopAppBar\s*\(""").find(screen)!!.range.first)
        }
        assertTrue(
            "the fraction handed to AccountSwitchTitle must come from the scroll behaviour that " +
                "drives this very bar. Call was:\n$bar",
            bar.contains("scrollBehavior = scrollBehavior,"),
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /** The `MediumTopAppBar` call up to the brace that balances its first `{`: its `title` slot. */
    private fun titleBlock(text: String): String {
        val hits = Regex("""\bMediumTopAppBar\s*\(""").findAll(text).toList()
        check(hits.size == 1) { "InboxScreen.kt is expected to call MediumTopAppBar exactly once, found ${hits.size}" }
        return balancedFrom(text, hits.single().range.first)
    }

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
            "var accountMenuOpenIn by remember { mutableStateOf<Int?>(null) } " +
                "val titleSwitchTargets = if (!ui.unified) accounts.filter { it.id != currentAccountId } else emptyList() " +
                "MediumTopAppBar("

        private const val EXPECTED_CALL =
            "AccountSwitchTitle( others = titleSwitchTargets, openIn = accountMenuOpenIn, " +
                "onOpenInChange = { accountMenuOpenIn = it }, onSwitchAccount = onSwitchAccount, " +
                "collapsedFraction = { scrollBehavior.state.collapsedFraction }, )"

        private const val EXPECTED_CHEVRON =
            "if (titleSwitchTargets.isNotEmpty()) { Icon( Icons.Filled.ExpandMore, contentDescription = null,"

        private const val EXPECTED_GUARD =
            "if (others.isEmpty()) { content() return }"

        private const val EXPECTED_TARGET =
            "modifier = if (shown) { Modifier " +
                ".clickable(onClickLabel = stringResource(R.string.inbox_switch_account)) { onOpenInChange(here) } " +
                ".semantics(mergeDescendants = true) { role = Role.Button } } else { Modifier },"

        private const val EXPECTED_ROW =
            "val inTopRow = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize"

        private const val EXPECTED_SHOWN =
            "val shown by remember(inTopRow) { derivedStateOf { " +
                "if (inTopRow) collapsedFraction() >= 0.5f else collapsedFraction() < 0.5f } }"

        private const val EXPECTED_MENU =
            "DropdownMenu(expanded = shown && openIn == here, onDismissRequest = { onOpenInChange(null) })"

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
