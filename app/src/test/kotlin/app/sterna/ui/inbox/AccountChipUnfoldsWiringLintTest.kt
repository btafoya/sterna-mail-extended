package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. No JVM test in this repository can run a `@Composable`, so the
 * drawer's account chip is pinned as text: these rules prove that a call is *written* with the
 * arguments given here, never that it *works* once composed.
 *
 * What it does not cover, the two holes of `CONTRIBUTING.md` that apply here:
 * - Textual order is not execution order. A pinned line says nothing about its position in a
 *   control structure, so a branch added above the pinned one leaves every rule below untouched
 *   and never reached.
 * - Nothing here holds a value at the point the composable collects it. `otherAccounts` could be
 *   bound to an empty list one line above and the guard would still read exactly as pinned, with
 *   the chip inert for everyone.
 *
 * The gesture itself is only ever seen on a device, by hand.
 */
class AccountChipUnfoldsWiringLintTest {

    /**
     * The chip of the account you are looking at, pinned whole: its modifier chain carries the
     * chevron's own toggle, under the chevron's own guard.
     */
    @Test fun `the current account's chip toggles the list, guarded like the chevron`() {
        assertEquals(
            "the drawer's current-account chip must carry Modifier.clickable { accountsExpanded = " +
                "!accountsExpanded } behind if (otherAccounts.isNotEmpty()). Without the toggle the " +
                "tap unfolds nothing and the user is left believing the chip is dead; without the " +
                "guard a single-account user taps a chip that answers with nothing at all, which " +
                "is an affordance that lies. The onClickLabel is what a screen reader announces " +
                "for that tap, and it must be the chevron's own label.",
            listOf(
                "AccountChip(",
                "label = currentLabel,",
                "color = accountColorOf(currentAccount?.color),",
                "photo = currentAccount?.photo,",
                "modifier = Modifier",
                ".graphicsLayer { translationX = accountOffset.value }",
                ".then(",
                "if (otherAccounts.isNotEmpty()) {",
                "Modifier.clickable(",
                "onClickLabel = stringResource(R.string.inbox_switch_account),",
                ") { accountsExpanded = !accountsExpanded }",
                "} else {",
                "Modifier",
                "},",
                "),",
                ")",
            ),
            callBlock(INBOX_SCREEN, "AccountChip("),
        )
    }

    /**
     * The witness of the gesture asked for in #200: neither of the two things the old tap did may
     * come back into the chip, whatever else is added around them.
     */
    @Test fun `the chip neither opens settings nor closes the drawer`() {
        assertEquals(
            "the current-account chip must not call onOpenAccountSettings( or drawerState.close(). " +
                "The first re-opens the settings screen over the drawer, so the list the user " +
                "wanted is never seen; the second shuts the drawer out from under the very list " +
                "the tap just unfolded. Either one restores the defect of #200 on its own.",
            emptyList<String>(),
            callBlock(INBOX_SCREEN, "AccountChip(").filter {
                "onOpenAccountSettings(" in it || "drawerState.close()" in it
            },
        )
    }

    /**
     * Two affordances, one condition. The chevron and the chip must appear and disappear together.
     */
    @Test fun `chip and chevron stand under the same guard`() {
        assertEquals(
            "DrawerContent must hold exactly two if (otherAccounts.isNotEmpty()) guards, one for " +
                "the chip and one for the chevron. Should the two conditions ever diverge, the " +
                "chip and the chevron stop saying the same thing on screen: one offers the list " +
                "while the other has hidden it, and the user is asked to guess which is right.",
            listOf(
                "if (otherAccounts.isNotEmpty()) {",
                "if (otherAccounts.isNotEmpty()) {",
            ),
            functionBody(INBOX_SCREEN, "private fun DrawerContent(")
                .filter { it == "if (otherAccounts.isNotEmpty()) {" },
        )
    }

    /**
     * The invariant the chip's old comment carried, moved to the decision that still holds it: the
     * routing of a shared mailbox's id.
     */
    @Test fun `a shared mailbox id still routes to its own settings screen`() {
        assertEquals(
            "settingsRouteFor must send a shared mailbox to sharedAccount/ and everything else to " +
                "account/. A shared mailbox owns its colour and its notification switches under " +
                "its own id, so routing it to the login's screen silences the wrong mailbox (#31).",
            listOf(
                "internal fun settingsRouteFor(accountId: String, accounts: List<StoredAccount>): String =",
                "if (sharedAccountFor(accountId, accounts) != null) \"sharedAccount/\$accountId\" else \"account/\$accountId\"",
            ),
            codeLines(SHARED_ACCOUNT_SCREEN).filter {
                it.startsWith("internal fun settingsRouteFor(") ||
                    it.startsWith("if (sharedAccountFor(")
            },
        )
    }

    /**
     * The lines of [file], comments dropped whole and trailing `//` cut, blanks removed. Without
     * this every rule above would also match the prose written to explain it.
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring a `//` inside a string literal. */
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

    /**
     * The code lines of the function opening with [signature], its own closing brace excluded.
     */
    private fun functionBody(file: File, signature: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.startsWith(signature) }
        check(start >= 0) { "${file.name} no longer declares `$signature` — was it renamed?" }
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (opened && next == 0) return body
            body += line
            depth = next
            if (depth > 0) opened = true
        }
        error("`$signature` in ${file.name} never closes — the slice would be the rest of the file")
    }

    /**
     * The whole paren-balanced call that opens with [opener], its closing `)` included. A call is
     * balanced on parentheses, not braces: a Compose modifier chain carries lambdas whose braces
     * close inside the arguments, so a brace-balanced slice would stop on the first of them.
     */
    private fun callBlock(file: File, opener: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.startsWith(opener) }
        check(start >= 0) { "${file.name} no longer calls `$opener` — was it renamed?" }
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        const val ANCHOR = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ANCHOR).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_SCREEN: File by lazy { File(ROOT, ANCHOR) }
        val SHARED_ACCOUNT_SCREEN: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/ui/settings/SharedAccountScreen.kt")
        }
    }
}
