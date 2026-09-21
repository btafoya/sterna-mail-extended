package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads `SternaApp.kt` as text and compares whole trimmed
 */
class RestoredViewWiringLintTest {

    /**
     * The order is the guard, which is why this is a block and not two line checks, and the
     * block is pinned whole.
     */
    @Test fun `the push flag is seeded from the stored view, immediately before the arm`() {
        assertEquals(
            "RootViewModel.refresh() must seed PushController.unifiedInboxVisible from the " +
                "RESTORED view and do it BEFORE arming. The arm reads that flag — inline on the " +
                "worker branch, later and on the service's own coroutine when it goes through " +
                "startForegroundService — while the list's own collector only sets it once the " +
                "NavHost has composed. Seeded after the call, or not at all, a cold start " +
                "reopening on 'All inboxes' arms as if a single folder were on screen (#179). " +
                "The coldStart guard is the rest of the same rule: refresh() also runs on " +
                "switchAccount(), on some twenty settings gestures and on connect, where a " +
                "composed list already owns the flag. Seeded there, the stored 'open on' view " +
                "overrules the list actually on screen, and the baseline reset the arm performs " +
                "then swallows every account's backlog (#185).",
            listOf(
                "if (coldStart) PushController.unifiedInboxVisible = restoredUnifiedView()",
                "PushController.apply(getApplication(), userInitiated = true)",
            ),
            codeBlock(STERNA_APP, "if (coldStart) PushController.unifiedInboxVisible", 2),
        )
    }

    /** Pinned as a block: the argument and the call site it sits in are one rule. */
    @Test fun `only the constructor asks for a cold start`() {
        assertEquals(
            "the constructor is the one call site that may ask for a cold start, because it is " +
                "the one place where no list exists yet. init runs on every RootViewModel " +
                "construction, not once per process: a back gesture finishes the Activity and " +
                "empties the ViewModelStore, and the next launch replays this line in the same " +
                "process. That stays harmless only because InboxViewModel is cleared with it; a " +
                "construction leaving a live list behind would reopen the defect. Left at " +
                "refresh(), the seed never runs at all and a launch on 'All inboxes' arms as if " +
                "a folder were on screen (#179).",
            listOf("init {", "refresh(coldStart = true)", "}"),
            codeBlock(STERNA_APP, "init {", 3),
        )
        assertEquals(
            "every call to refresh in the file, compared whole: a second cold start is spellable " +
                "in more ways than one — refresh(true) positionally, the named argument handed to " +
                "a new caller, a lambda wrapping either — and a text search for 'coldStart = " +
                "true' sees none of them. Any second one reseeds " +
                "PushController.unifiedInboxVisible from the stored 'open on' view while the list " +
                "the reader is looking at already owns it, and the baseline reset the arm then " +
                "performs swallows every account's backlog (#185). A caller added here without a " +
                "thought for the flag lands on this assertion too, which is the point.",
            listOf(
                "refresh(coldStart = true)",
                "refresh()",
                "true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)",
                "onAccountsChanged = viewModel::refresh,",
            ),
            codeLines(STERNA_APP)
                .filter { it.contains("refresh(") || it.contains("::refresh") }
                .filterNot { it.startsWith("fun refresh(") },
        )
    }

    /** `switchAccount()` keeps its own `= false`, and nothing else writes the flag on a switch:
     *  neither the guarded seed nor a collector that may never re-emit. */
    @Test fun `switching account still clears the flag itself, before refreshing`() {
        assertEquals(
            "switchAccount() must keep setting PushController.unifiedInboxVisible = false before " +
                "refresh(). It is the only writer of the flag on a switch: the seed runs on a " +
                "cold start alone, and no list is composed here — InboxViewModel reassigns its " +
                "selection, a StateFlow that stays silent when the arriving account's inbox " +
                "carries the id the departing selection already held, so no collector rewrites " +
                "the flag. Without this line the arm refresh() performs would read whatever the " +
                "departing account's view left there.",
            listOf(
                "fun switchAccount(id: String) {",
                "accountStore.setCurrent(id)",
                "PushController.unifiedInboxVisible = false",
                "refresh()",
            ),
            codeBlock(STERNA_APP, "fun switchAccount(id: String) {", 4),
        )
    }

    /**
     * `?:`, never `== true`.
     */
    @Test fun `a notification falls back on the restored view when the list is not composed yet`() {
        assertEquals(
            "the notification switch must be told whether the RESTORED view is unified when there " +
                "is no list yet. With `== true` a cold start always answers false, and a tap on a " +
                "notification while 'All inboxes' was the view left behind switches accounts and " +
                "throws the reader into one account's folder (#179). A composed list is asked " +
                "standsInUnifiedView(), not state.unified: the 'All inboxes' the open-on setting " +
                "seeds is on screen but is not the reader's, and the notification wins over it (#185).",
            listOf(
                "NotificationAccountSwitch.resolve(",
                "notificationAccountId = target.accountId,",
                "currentAccountId = currentAccountId,",
                "knownAccountIds = accounts.map { it.id },",
                "unifiedView = listViewModel?.standsInUnifiedView() ?: restoredStandsInUnified(),",
                ")?.let(onSwitchAccount)",
            ),
            codeBlock(STERNA_APP, "NotificationAccountSwitch.resolve(", 6),
        )
    }

    /**
     * The answers the rules above spend, both read through the shared adapter `restoredSelection`.
     */
    @Test fun `the restored view is read through the shared adapter only`() {
        assertEquals(
            "RootViewModel must expose the restored view as the unified question, answered by " +
                "restoredSelection(accountStore) — the same adapter InboxViewModel seeds its " +
                "selection from. A second hand-rolled read here is how the push arm and the list " +
                "come to disagree about the view the app just reopened on.",
            listOf("fun restoredUnifiedView(): Boolean = restoredSelection(accountStore) is Sel.Unified"),
            codeLines(STERNA_APP).filter { it.startsWith("fun restoredUnifiedView(") },
        )
        assertEquals(
            "RootViewModel must answer the notification's account half, before the list exists, " +
                "with the rule the list answers it with once it does: unifiedViewByChoice() on the " +
                "same restored view, told whether the setting picked it. Answered by " +
                "restoredUnifiedView(), which the push arm needs setting included, a cold tap " +
                "under 'open on All inboxes' keeps the current account while the folder half, " +
                "once the list exists, moves the list (#185).",
            listOf(
                "fun restoredStandsInUnified(): Boolean = unifiedViewByChoice(restoredSelection(accountStore), " +
                    "openOnOverridesMemory(accountStore.openOnView()))",
            ),
            codeLines(STERNA_APP).filter { it.startsWith("fun restoredStandsInUnified(") },
        )
        assertEquals(
            "MainNavHost must be handed RootViewModel's own answer. A literal { false } here, or " +
                "the parameter dropped and defaulted, restores the defect whole while every rule " +
                "above goes on agreeing with itself.",
            listOf("restoredStandsInUnified = viewModel::restoredStandsInUnified,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredStandsInUnified = ") },
        )
        assertEquals(
            "MainNavHost's parameter must stay REQUIRED. Given a default — " +
                "`restoredStandsInUnified: () -> Boolean = { false },` — the call site above goes on " +
                "passing the right thing and every rule here stays green, while any other caller, " +
                "today's or tomorrow's, silently gets the cold-start answer this branch exists to " +
                "stop trusting. Compared as the whole declaration line.",
            listOf("restoredStandsInUnified: () -> Boolean,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredStandsInUnified:") },
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
    }
}
