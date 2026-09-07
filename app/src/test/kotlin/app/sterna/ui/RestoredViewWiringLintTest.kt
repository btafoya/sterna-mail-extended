package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `SternaApp.kt` as TEXT and compares whole trimmed
 * lines, because nothing here can be built on the JVM: `RootViewModel` is an `AndroidViewModel`
 * over the account store and the Keystore, `MainNavHost` is a composable, and there is no
 * Robolectric in this module. The decisions themselves ARE executed elsewhere —
 * [app.sterna.ui.inbox.SelectionMemoryTest] runs `restoreSelection`, [NotificationAccountSwitchTest]
 * runs `NotificationAccountSwitch.resolve` including its `unifiedView = true` arm. What no
 * executing test can see is which VALUE the app hands that arm, and in what ORDER, which is the
 * whole defect this file guards.
 *
 * Since the drawer learnt to reopen on the view you left (#179), two places still reasoned from
 * "a cold start always lands on a single folder":
 *
 *  - the push arm. `refresh()` calls `PushController.apply(…, userInitiated = true)` before the
 *    NavHost composes the list, so the mirror `InboxViewModel`'s collector keeps
 *    (`PushController.unifiedInboxVisible`) is still at its initial `false` when
 *    `shouldResetBaseline` reads it. A restored unified view then arms as if one folder were on
 *    screen, and every account but the current one re-announces its backlog.
 * "Before" is the claim, NOT "in the same call stack": `apply()` is synchronous on one branch
 *    only (`PushController:342`/`:364`, the worker path, taken when no account needs a direct
 *    connection). The other branch is `PushService.start` = `startForegroundService`, and the flag
 *    is then read later, on the service's own coroutine (`PushService.watch`). Seeding first is
 *    what makes the value right whenever that read happens; it is not a guarantee about threads;
 *  - the notification switch. `listViewModel` is null in the first composition of a cold start, so
 *    `unifiedView` was `false` by construction and a notification opened from "All inboxes" threw
 *    the reader out of it into one account's folder — the very bounce
 *    `NotificationAccountSwitch`'s unified arm exists to prevent.
 *
 * Whole trimmed lines, compared as CONTIGUOUS blocks, never `contains`: a fragment rule is blind
 * to anything appended to a line, and a per-line rule is blind to a statement moved one line down —
 * which is exactly the mutation the first rule below exists to catch.
 */
class RestoredViewWiringLintTest {

    /**
     * THE ORDER IS THE GUARD, which is why this is a block and not two line checks — and the
     * order is ALL it is. The seed must precede the arm because the flag has to be right by the
     * time the arm reads it, and the arm may read it on another thread, later: `apply()` either
     * runs the decision inline (the worker branch) or hands the job to
     * `startForegroundService`, whose service reads the flag on its own coroutine. So "before" is
     * the only property this file can state; "in the same call stack" would be false on the most
     * common path.
     *
     * Seeded AFTER the call, the value is correct for every later arm and wrong for the one that
     * happens on a cold start — the suite stays green, and the defect is exactly as it was.
     */
    @Test fun `the push flag is seeded from the stored view, immediately before the arm`() {
        assertEquals(
            "RootViewModel.refresh() must seed PushController.unifiedInboxVisible from the " +
                "RESTORED view and do it BEFORE arming. The arm reads that flag — inline on the " +
                "worker branch, later and on the service's own coroutine when it goes through " +
                "startForegroundService — while the list's own collector only sets it once the " +
                "NavHost has composed. Seeded after the call, or not at all, a cold start " +
                "reopening on 'All inboxes' arms as if a single folder were on screen (#179).",
            listOf(
                "PushController.unifiedInboxVisible = restoredUnifiedView()",
                "PushController.apply(getApplication(), userInitiated = true)",
            ),
            codeBlock(STERNA_APP, "PushController.unifiedInboxVisible = restoredUnifiedView()", 2),
        )
    }

    /**
     * `switchAccount()` KEEPS ITS OWN `= false`, and it is REDUNDANT — that is the whole point
     * of pinning it.
     *
     * Since the seed exists, this line no longer decides anything: `refresh()` is called on the
     * very next line and assigns the flag unconditionally, before anything reads it, and it assigns
     * `false` anyway — the switch has already moved the current account, so the stored view names
     * the DEPARTING one and `restoreSelection`'s id guard (`SelectionMemory`, `parts[0] !=
     * currentAccountId`) answers null.
     *
     * It stays because removing it can win nothing and can lose the day some path arms without
     * going through `refresh()`, and it says the switch's own reason where the switch happens. It
     * is pinned so that it is not deleted as dead code by someone who noticed what is written
     * above: dropping it must be a decision, not a tidy-up.
     */
    @Test fun `switching account still clears the flag itself, before refreshing`() {
        assertEquals(
            "switchAccount() must keep setting PushController.unifiedInboxVisible = false before " +
                "refresh(). ⚠ It is redundant today — refresh() reseeds the flag on the next line, " +
                "and to false, since after setCurrent() the stored view belongs to the departing " +
                "account. It is kept, and pinned, because it costs nothing, states the switch's " +
                "reason at the switch, and covers any future arm that does not go through " +
                "refresh(). Delete it as a decision, not as dead code.",
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
     * `?:`, NEVER `== true`.
     *
     * The live list keeps the last word wherever there is one; the memory speaks only when there is
     * nobody to answer, i.e. the first composition of a cold start. Written `== true`, the null
     * list answers `false` and a notification tapped from a restored "All inboxes" switches the
     * current account, dropping the reader out of a view she never asked to leave.
     */
    @Test fun `a notification falls back on the restored view when the list is not composed yet`() {
        assertEquals(
            "the notification switch must be told whether the RESTORED view is unified when there " +
                "is no list yet. With `== true` a cold start always answers false, and a tap on a " +
                "notification while 'All inboxes' was the view left behind switches accounts and " +
                "throws the reader into one account's folder (#179).",
            listOf(
                "NotificationAccountSwitch.resolve(",
                "notificationAccountId = target.accountId,",
                "currentAccountId = currentAccountId,",
                "knownAccountIds = accounts.map { it.id },",
                "unifiedView = listViewModel?.state?.value?.unified ?: restoredUnifiedView(),",
                ")?.let(onSwitchAccount)",
            ),
            codeBlock(STERNA_APP, "NotificationAccountSwitch.resolve(", 6),
        )
    }

    /**
     * The answer both rules above spend, and the one line that reads the memory for them.
     *
     * It goes through `restoredSelection(store)`, the shared adapter — not a second read of
     * `store.storedView()` written out here. `SelectionMemoryWiringLintTest` pins that the whole app
     * holds exactly ONE call to `restoreSelection`, which is what keeps this answer and the list's
     * own seeding from ever disagreeing about what "the restored view" is.
     */
    @Test fun `the restored view is read once, through the shared adapter`() {
        assertEquals(
            "RootViewModel must expose the restored view as the unified question, answered by " +
                "restoredSelection(accountStore) — the same adapter InboxViewModel seeds its " +
                "selection from. A second hand-rolled read here is how the push arm and the list " +
                "come to disagree about the view the app just reopened on.",
            listOf("fun restoredUnifiedView(): Boolean = restoredSelection(accountStore) is Sel.Unified"),
            codeLines(STERNA_APP).filter { it.startsWith("fun restoredUnifiedView(") },
        )
        assertEquals(
            "MainNavHost must be handed RootViewModel's own answer. A literal { false } here, or " +
                "the parameter dropped and defaulted, restores the defect whole while every rule " +
                "above goes on agreeing with itself.",
            listOf("restoredUnifiedView = viewModel::restoredUnifiedView,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredUnifiedView = ") },
        )
        assertEquals(
            "MainNavHost's parameter must stay REQUIRED. Given a default — " +
                "`restoredUnifiedView: () -> Boolean = { false },` — the call site above goes on " +
                "passing the right thing and every rule here stays green, while any other caller, " +
                "today's or tomorrow's, silently gets the cold-start answer this branch exists to " +
                "stop trusting. Compared as the whole declaration line.",
            listOf("restoredUnifiedView: () -> Boolean,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredUnifiedView:") },
        )
    }

    /**
     * [count] consecutive CODE lines of [path] from the single one starting with [prefix], trimmed.
     * A prefix matching anything other than exactly one line returns a message instead, so no
     * assertion here can pass by matching nothing.
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
