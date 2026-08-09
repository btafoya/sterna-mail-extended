package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST, and worth only what it says. It reads `SternaApp.kt` and
 * `AccountsUnreadableScreen.kt` as TEXT and compares whole trimmed lines. It exists because
 * `RootViewModel` cannot be built here (an `Application`, the account store, the Keystore — no
 * Robolectric in this module) and no composable can be rendered here either. The DECISION is
 * executed next door, by [RootRouteTest]; this file only proves it is the one the app runs, WITH
 * WHICH ARGUMENTS, that each root state reaches its own screen, and that the screen written for the
 * new state hands both labels over and offers nothing to tap.
 *
 * ⛔ What it CANNOT prove, and no test in this repo can: that anything is actually drawn, that the
 * text fits, that the layout survives a large font scale, or that the strings reach the right slots
 * on screen. That is bench work.
 *
 * Neither file is enough alone: the rule can be perfect and called by nobody, and the call can be
 * perfect around a rule that returns the wrong screen.
 *
 * ⛔ Whole trimmed lines, compared as a CONTIGUOUS block, never `contains` and never one line in
 * isolation: a fragment check is blind to anything appended to a line, and a per-line check is
 * blind to a statement moved one line down.
 */
class RootRefreshWiringTest {

    @Test fun `refresh routes through RootRoute, on a freshly read unreadable flag`() {
        assertEquals(
            "RootViewModel.refresh() no longer hands RootRoute.resolve the current account AND a " +
                "fresh accountsUnreadable() read. Either it restates the rule (and RootRouteTest " +
                "then proves nothing about the app), or it routes on a stale flag, or it is back " +
                "to NeedAccount — which invites the user to add an account the store will refuse " +
                "to save.",
            listOf(
                "fun refresh() {",
                "val current = accountStore.currentId()?.takeIf { accountStore.credentials(it) != null }",
                "?: accountStore.accounts().firstOrNull { accountStore.credentials(it.id) != null }?.id",
                "if (current != null && accountStore.currentId() != current) accountStore.setCurrent(current)",
                "PushController.apply(getApplication(), userInitiated = true)",
                "_state.value = RootRoute.resolve(current, accountStore.accountsUnreadable())",
                "}",
            ),
            codeBlock(STERNA_APP, "fun refresh() {", 7),
        )
    }

    /**
     * EVERY BRANCH OF THE ROOT `when`, not only the new one. Pinning the new state alone leaves the
     * cheapest catastrophe in the lot available: re-point `RootState.NeedAccount` at
     * [AccountsUnreadableScreen] and a FRESH INSTALL — no account, nothing unreadable — opens on
     * the alert screen, so nobody can ever create a first account, with the whole suite green.
     * The four branches are one contiguous block, so a state re-pointed at another's screen, or a
     * branch deleted, moves the block.
     */
    @Test fun `each root state shows its own screen, and the unreadable one asks for nothing`() {
        assertEquals(
            "the root `when` changed shape. If NeedAccount now shows the unreadable screen, a " +
                "fresh install can never add its first account; if AccountsUnreadable shows the " +
                "connect flow, the user is asked for credentials the store will refuse to save, " +
                "and the one message she is owed — nothing was deleted — is not on screen.",
            listOf(
                "RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {",
                "CircularProgressIndicator()",
                "}",
                "RootState.NeedAccount -> when (hasSeenWelcome) {",
                "null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }",
                "false -> WelcomeScreen(onDone = viewModel::markWelcomeSeen)",
                "true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)",
                "}",
                "RootState.AccountsUnreadable -> AccountsUnreadableScreen()",
                "is RootState.Authenticated -> MainNavHost(",
            ),
            codeBlock(STERNA_APP, "RootState.Loading ->", 10),
        )
    }

    /**
     * ⚠ THE SHAPE OF THE SCREEN, NOT ITS RENDERING. No composable can be rendered in this module,
     * so what is held here is that the two labels reach [app.sterna.ui.components.EmptyState] —
     * `body` above all, which carries the only statement the user is really owed: nothing was
     * deleted. `TranslationParityTest` proves that sentence EXISTS in nine languages and can say
     * nothing about whether it is on screen; dropping `body =` here would leave it green.
     */
    @Test fun `the screen shows both labels, the reassurance included`() {
        assertEquals(
            "AccountsUnreadableScreen no longer hands EmptyState both strings. Without the body " +
                "the screen states the failure and drops the reassurance — the user is told her " +
                "accounts are unreadable and never told they are still there.",
            listOf(
                "EmptyState(",
                "art = EmptyArt.OFFLINE,",
                "title = stringResource(R.string.accounts_unreadable_title),",
                "body = stringResource(R.string.accounts_unreadable_body),",
                ")",
            ),
            codeBlock(UNREADABLE_SCREEN, "EmptyState(", 5),
        )
    }

    /**
     * ⛔ NOTHING TAPPABLE ON THIS SCREEN, held as an absence.
     *
     * `EmptyState` takes an `action: (@Composable () -> Unit)?`, so a "Start over" button wired to
     * `AccountStore.clear()` is ONE line away — on the one screen a user reaches while her accounts
     * look lost, which is exactly when a reset button gets pressed. The block rule above cannot see
     * a line ADDED after it, so this rule reads the whole file and reports every offending line;
     * being a list of matches, it is not blind to anything appended.
     *
     * A legitimate future action (a link to a help page, say) has to be added here on purpose.
     */
    @Test fun `nothing on this screen can be tapped`() {
        val offenders = codeLines(UNREADABLE_SCREEN).filter { TAPPABLE.containsMatchIn(it) }
        assertEquals(
            "a control appeared on the screen that must offer none. The only action that would " +
                "'unblock' it is destroying the accounts it exists to protect.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * [count] consecutive CODE lines of [path] from the single one starting with [prefix], trimmed.
     * Comments and blank lines are dropped first — a mutation cannot hide in a comment, and pinning
     * prose would make this file fail on every reword. A prefix matching anything other than
     * exactly one line returns a message instead, so an assertion can never pass by matching
     * nothing.
     */
    private fun codeBlock(path: String, prefix: String, count: Int): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }

    /** The file's lines, trimmed, without comments or blanks. */
    private fun codeLines(path: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val UNREADABLE_SCREEN = "app/src/main/kotlin/app/sterna/ui/AccountsUnreadableScreen.kt"

        /** Anything that offers, or performs, an action on the unreadable-accounts screen. */
        val TAPPABLE = Regex("""\baction\s*=|onClick|Button|clickable|TextButton|\.clear\(\)""")
    }
}
