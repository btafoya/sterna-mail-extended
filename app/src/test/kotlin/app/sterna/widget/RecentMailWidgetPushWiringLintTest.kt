package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, the same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest], and a separate file from [RecentMailWidgetWiringLintTest] for
 * the same reason the counter has one: this pins the app-scoped collection, and the decision it
 * collects is executed next door by [RecentMailWidgetPushTest].
 */
class RecentMailWidgetPushWiringLintTest {

    /**
     * The whole block, and every line of it carries a defect if it is changed:
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
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Inboxes), accountStore.accountsFlow) {",
                "RecentWidgetKind.Inboxes.source(this@AppContainer)",
                "}.collect {",
                "RecentMailWidgetDraw.forget(RecentWidgetKind.Inboxes)",
                "runCatching { RecentMailWidgetDraw.refresh(appContext, RecentWidgetKind.Inboxes) }",
                ".rethrowIfCancelled()",
                ".onFailure { Log.w(\"Sterna\", \"latest-messages widget not redrawn; the cell keeps its rows\", it) }",
                "}",
                "}",
            ),
            blockContaining(STERNA_APPLICATION, "RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Inboxes)", "appScope.launch {"),
        )
    }

    @Test fun `AppContainer collects the unread list's push, gated on ITS OWN cells`() {
        assertEquals(
            "AppContainer.init must launch a fourth collection for RecentWidgetKind.Unread: over " +
                "that kind's presence and source, forgetting that kind's held drawing and " +
                "refreshing that kind. Dropped, a message read in the app keeps its row on the " +
                "unread list; on the other kind's gate, the list stops following the mail the day " +
                "the other cell goes; forgetting the other kind serves this cell the stale rows.",
            listOf(
                "appScope.launch {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Unread), accountStore.accountsFlow) {",
                "RecentWidgetKind.Unread.source(this@AppContainer)",
                "}.collect {",
                "RecentMailWidgetDraw.forget(RecentWidgetKind.Unread)",
                "runCatching { RecentMailWidgetDraw.refresh(appContext, RecentWidgetKind.Unread) }",
                ".rethrowIfCancelled()",
                ".onFailure { Log.w(\"Sterna\", \"unread-messages widget not redrawn; the cell keeps its rows\", it) }",
                "}",
                "}",
            ),
            blockContaining(STERNA_APPLICATION, "RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Unread)", "appScope.launch {"),
        )
    }

    /**
     * The two widgets keep two collections, and this is the rule that says so out loud.
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
                "UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext, UnreadWidgetKind.Inboxes), accountStore.accountsFlow) {",
                "UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext, UnreadWidgetKind.EveryFolder), accountStore.accountsFlow) {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Inboxes), accountStore.accountsFlow) {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext, RecentWidgetKind.Unread), accountStore.accountsFlow) {",
            ),
            lines.filter { it.startsWith("UnreadWidgetPush.redraws") || it.startsWith("RecentMailWidgetPush.redraws") },
        )
    }

    /**
     * The seed, whole lines, the gate's third writer, and the one nothing else in this
     * repository can reach.
     */
    @Test fun `the seed asks about THIS widget's cells, and reads the answer the right way round`() {
        assertEquals(
            "RecentMailWidgetPresence.placed must seed itself from RecentMailWidgetDraw.placedIds " +
                "through anyWidgetPlaced, unnegated. A negation shuts the gate for everyone who " +
                "has this widget and opens it for everyone who has not; a missing seed leaves a " +
                "cell placed before the last reboot following nothing at all.",
            listOf(
                "fun placed(context: Context, kind: RecentWidgetKind): StateFlow<Boolean> {",
                "if (seeded.add(kind)) {",
                "set(kind, anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context, kind)))",
                "}",
                "return states.getValue(kind)",
            ),
            functionBody(PROVIDER, "fun placed(context: Context, kind: RecentWidgetKind)"),
        )
        assertEquals(
            "and the cells it asks about are THAT kind's provider's: a constant class here seeds " +
                "the unread list's gate from the inboxes' cells.",
            listOf(
                "fun placedIds(context: Context, kind: RecentWidgetKind): IntArray =",
                "AppWidgetManager.getInstance(context)",
                ".getAppWidgetIds(ComponentName(context, kind.provider))",
            ),
            kotlinLines(DRAW).let { lines ->
                val at = lines.indexOfFirst { it.startsWith("fun placedIds(") }
                if (at < 0) emptyList() else lines.subList(at, at + 3)
            },
        )
    }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker], the launch
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
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
    }
}
