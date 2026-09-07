package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest], and a separate file from [RecentMailWidgetWiringLintTest] for
 * the same reason the counter has one: this pins the app-scoped COLLECTION, and the decision it
 * collects is executed next door by [RecentMailWidgetPushTest].
 *
 * Neither end can be built in this suite: `AppContainer` wants a `Context`, the Keystore and a
 * database, and there is no Robolectric here. So the two things that test cannot see — that the
 * shipped app plugs the push in at all, and WHAT it plugs in — are pinned as whole lines of source.
 *
 * Whole lines, never a `contains`: a substring rule is satisfied by every mutation that LENGTHENS
 * the line. The price is that reformatting the block reddens this test — loud, and one line to fix.
 */
class RecentMailWidgetPushWiringLintTest {

    /**
     * The whole block, and every line of it carries a defect if it is changed:
     *
     *  - the LAUNCH itself: without it, nothing follows a message being read, deleted, moved, or an
     *    account being removed. `RecentMailWidgetDraw.refresh` then has the three callers it had
     *    before this pane (the tail of a delivery pass and the two settings writes), and the row
     *    keeps its unread dot until mail happens to arrive — measured at the bench on 2026-08-18;
     *  - `accountStore.accountsFlow`, the second arm: it is what makes the per-account colour dot
     *    appear when a second account arrives and vanish when it leaves. The dots are drawn only
     *    above `AllInboxesView.existsFor`, and an account that owns none of the 25 latest rows moves
     *    no row at all — so dropped, the inbox read is EQUAL across that bound, the redraw is
     *    swallowed, and with `updatePeriodMillis="0"` the cell keeps stale dots for days. Any other
     *    flow of accounts here (a Room-backed one) would pay a re-subscribed query for all ~27
     *    `saveAccounts` writes; this one is a StateFlow in memory;
     *  - the GATE, and it must be `RecentMailWidgetPresence`: `UnreadWidgetPresence` compiles,
     *    keeps every other test in this repository green, and ties each widget's freshness to
     *    whether the OTHER one is on a home screen. `flowOf(true)` compiles too, and makes every
     *    install on earth observe the mail table for a widget nobody placed;
     *  - `RecentMailWidgetDraw.ROW_LIMIT`: a different bound here observes a different query from
     *    the one the cell draws, so a change below the limit would not redraw and a change above it
     *    would redraw for nothing;
     * - `forget()` BEFORE `refresh()`. Not for the reason the settings writes drop it — there,
     *    the held snapshot was projected under a rule the reader has just tightened. Here the rule
     *    has not moved and the MAIL has: what `forget()` buys is that the row factory can no longer
     *    fall back on a snapshot that is now stale. It falls back whenever its own read times out,
     *    so without this the cell would sometimes repaint exactly the row this redraw was triggered
     *    to replace — the unread dot surviving its own fix, intermittently and on slow reads only;
     *  - `rethrowIfCancelled`: a failed draw is a stale cell and nothing more, but the app scope
     *    being cancelled must not be logged as one more failed draw.
     *
     * And the collected value is DISCARDED on purpose (`collect {`, no parameter): the rows are a
     * trigger. Drawing them from here would bypass `RecentMailWidgetDraw.read`, which is where the
     * notification-content setting and the app lock are consulted.
     */
    @Test fun `AppContainer collects the latest-messages push, gated on ITS OWN widget being placed`() {
        assertEquals(
            "AppContainer.init must launch RecentMailWidgetPush.redraws over " +
                "RecentMailWidgetPresence.placed(appContext) and accountStore.accountsFlow, reading " +
                "observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT), and collect it by " +
                "forgetting the held snapshot and then refreshing. Dropped, the cell stops " +
                "following the mail the user is reading; ungated, every install pays for a widget " +
                "it does not have; without the forget(), a slow read redraws the stale rows; " +
                "without accountsFlow, the colour dots stay frozen when an account with no recent " +
                "mail arrives or leaves.",
            listOf(
                "appScope.launch {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
                "mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT)",
                "}.collect {",
                "RecentMailWidgetDraw.forget()",
                "runCatching { RecentMailWidgetDraw.refresh(appContext) }",
                ".rethrowIfCancelled()",
                ".onFailure { Log.w(\"Sterna\", \"latest-messages widget not redrawn; the cell keeps its rows\", it) }",
                "}",
                "}",
            ),
            blockContaining(STERNA_APPLICATION, "RecentMailWidgetPush.redraws(", "appScope.launch {"),
        )
    }

    /**
     * The two widgets keep TWO collections, and this is the rule that says so out loud.
     *
     * The tempting simplification is one launch collecting one gate and redrawing both cells. It
     * compiles and it is wrong in both directions: a home screen carrying only the counter would
     * start re-reading the inbox rows it never shows, and a home screen carrying only the list
     * would have its gate decided by whether a counter cell exists.
     */
    @Test fun `each widget has its own collection, and neither gate is the other's`() {
        val lines = kotlinLines(STERNA_APPLICATION)
        assertEquals(
            "AppContainer.init must open exactly one push collection per widget, each on its own " +
                "presence, and each with its own account arm. A single shared collection ties one " +
                "widget's freshness to whether the other one is placed; and both need " +
                "accountStore.accountsFlow, because both are drawn differently above and below " +
                "AllInboxesView.existsFor — the dots on one, the label and the tap target on the " +
                "other — and neither read carries the account count.",
            listOf(
                "UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
            ),
            lines.filter { it.startsWith("UnreadWidgetPush.redraws") || it.startsWith("RecentMailWidgetPush.redraws") },
        )
    }

    /**
     * THE SEED, whole lines — the gate's third writer, and the one nothing else in this
     * repository can reach.
     *
     * `onEnabled` and `onDisabled` are pinned next door by [RecentMailWidgetWiringLintTest], and
     * `anyWidgetPlaced` is executed by [UnreadWidgetPresenceTest]. What no JVM test can call is
     * this function: it needs a [android.content.Context] and the widget manager, and there is no
     * Robolectric here. [RecentMailWidgetPresenceTest] only ever calls `set`, and
     * [RecentMailWidgetPushTest] injects a presence flow of its own — so every mutation below
     * compiles and leaves the whole suite green.
     *
     * Three of them, and each is a defect on a home screen nobody changed:
     *
     *  - a `!` on the answer, which is ONE CHARACTER: the gate is then shut for everyone who has a
     *    cell and open for everyone who has not. Both halves of this pane are lost at once — the
     *    unread dot comes back exactly as it was measured at the bench, and every install without
     *    the widget starts observing `emails` and `snoozed` for it;
     *  - the seed dropped altogether: `onEnabled` fires once, on the process that was running when
     *    the cell was placed, and that process is dead by the next boot. A cell that has been on
     *    the home screen for weeks then never opens the gate again;
     *  - `UnreadWidgetDraw.placedIds(context)` in place of this widget's own: the widget manager
     *    would be asked about the COUNTER's cells, and this widget's freshness would follow whether
     *    the other one is placed.
     */
    @Test fun `the seed asks about THIS widget's cells, and reads the answer the right way round`() {
        assertEquals(
            "RecentMailWidgetPresence.placed must seed itself from RecentMailWidgetDraw.placedIds " +
                "through anyWidgetPlaced, unnegated. A negation shuts the gate for everyone who " +
                "has this widget and opens it for everyone who has not; a missing seed leaves a " +
                "cell placed before the last reboot following nothing at all.",
            listOf(
                "fun placed(context: Context): StateFlow<Boolean> {",
                "if (!seeded) {",
                "seeded = true",
                "set(anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context)))",
                "}",
                "return state",
            ),
            functionBody(PROVIDER, "fun placed(context: Context)"),
        )
    }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — the launch
     * and everything under it, so the rule pins the collection AND what it is collected from.
     *
     * Deliberately returns an empty list when [marker] is nowhere in the file: dropping the block is
     * the mutation this exists to catch, and an assertion showing the expected lines against `[]`
     * says more than an exception would.
     *
     * Built on [kotlinLines] and not on `DeclarationSource.blockContaining`, which strips XML
     * comments: run over Kotlin it would count braces inside KDoc prose as code.
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = kotlinLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        val STERNA_APPLICATION = file("app/src/main/kotlin/app/sterna/SternaApplication.kt")

        /** Holds `RecentMailWidgetPresence` as well as the provider itself. */
        val PROVIDER = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetProvider.kt")
    }
}
