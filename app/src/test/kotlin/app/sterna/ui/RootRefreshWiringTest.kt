package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, and worth only what it says.
 */
class RootRefreshWiringTest {

    @Test fun `refresh routes through RootRoute, on a freshly read unreadable flag`() {
        assertEquals(
            "RootViewModel.refresh() no longer hands RootRoute.resolve the current account AND a " +
                "fresh accountsUnreadable() read. Either it restates the rule (and RootRouteTest " +
                "then proves nothing about the app), or it routes on a stale flag, or it is back " +
                "to NeedAccount — which invites the user to add an account the store will refuse " +
                "to save. The push seed on the line above the arm belongs to " +
                "RestoredViewWiringLintTest, which says why it must stay there; it is in this " +
                "block only because the block is contiguous.",
            listOf(
                "fun refresh(coldStart: Boolean = false) {",
                "val current = accountStore.currentId()?.takeIf { accountStore.credentials(it) != null }",
                "?: accountStore.accounts().firstOrNull { accountStore.credentials(it.id) != null }?.id",
                "if (current != null && accountStore.currentId() != current) accountStore.setCurrent(current)",
                "if (coldStart) PushController.unifiedInboxVisible = restoredUnifiedView()",
                "PushController.apply(getApplication(), userInitiated = true)",
                "_state.value = RootRoute.resolve(current, accountStore.accountsUnreadable())",
                "}",
            ),
            codeBlock(STERNA_APP, "fun refresh(", 8),
        )
    }

    /**
     * Every branch of the root `when`, not only the new one.
     */
    @Test fun `each root state shows its own screen, and the unreadable one asks for nothing`() {
        assertEquals(
            "the root `when` changed shape. If NeedAccount now shows the unreadable screen, a " +
                "fresh install can never add its first account; if AccountsUnreadable shows the " +
                "connect flow, the user is asked for credentials the store will refuse to save, " +
                "and the one message she is owed — nothing was deleted — is not on screen. The " +
                "mail host waits for the reading-pane setting (#228): composed on a guessed " +
                "position, a restored reader is converted into the pane and back.",
            listOf(
                "RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {",
                "LoadingRing()",
                "}",
                "RootState.NeedAccount -> when (hasSeenWelcome) {",
                "null -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }",
                "false -> WelcomeScreen(onDone = viewModel::markWelcomeSeen)",
                "true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)",
                "}",
                "RootState.AccountsUnreadable -> AccountsUnreadableScreen()",
                "is RootState.Authenticated -> when (val mode = readingPaneMode) {",
                "null -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }",
                "else -> MainNavHost(",
            ),
            codeBlock(STERNA_APP, "RootState.Loading ->", 12),
        )
    }

    /**
     * The shape of the screen, not its rendering.
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
     * Nothing tappable on this screen, held as an absence.
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
     * [count] consecutive code lines of [path] from the single one starting with [prefix], trimmed.
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
