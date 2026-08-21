package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 * [UnreadWidgetPaletteWiringLintTest], one widget over. There is no Robolectric in this repository,
 * so neither the `RemoteViews` of the cell's frame nor the `RemoteViewsFactory` that serves its rows
 * can be built, let alone executed, by this suite. [WidgetThemeTest] RUNS the two rules that decide
 * the colours; that says nothing about whether the shipped cell goes and asks for them, and this
 * widget draws in two places, at two different moments.
 *
 * The defect being closed: the rows and the frame took their colours from `res/values` +
 * `res/values-night`, i.e. from the SYSTEM's day/night mode, so the theme chosen in the app, the
 * pure-black option (#117) and Material You never reached the home screen.
 *
 * ⛔ WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line. The price is that reformatting a pinned call reddens this test — loud, and
 * one line to fix.
 */
class RecentMailWidgetPaletteWiringLintTest {

    /**
     * ⛔ `setBackgroundResource`, NEVER `setBackgroundColor`.
     *
     * `RemoteViews.setInt(id, "setBackgroundColor", …)` calls `View.setBackgroundColor(int)`, which
     * REPLACES the shape drawable with a flat fill: the cell comes out SQUARE, a rectangle of solid
     * colour among rounded neighbours, and every corner rule in this package stays green while it
     * happens. That is why the surface travels as one of three pre-built drawables and why
     * [WidgetThemeReader.background] is the only thing that names them.
     *
     * The notice is the other half. It is the cell's EMPTY VIEW — what the launcher shows before any
     * read has answered, and whenever the list holds no rows — so without its `setTextColor` it
     * keeps the near-black the layout froze into it and lands on the app's dark surface, unreadable,
     * on the one screen nobody can debug.
     */
    @Test fun `the drawn frame is painted from the palette, and keeps its rounded corner`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must paint each cell from the palette it was handed: the " +
                "background as a pre-built DRAWABLE, then the notice's colour. ⛔ " +
                "setBackgroundColor here throws the shape away and the cell sits square among " +
                "rounded neighbours; a missing setTextColor leaves the notice in the layout's " +
                "compile-time colour on a surface it was never meant for — dark on dark. " +
                "⛔ AND BOTH SIT UNDER `if (palette != null)`: a null palette is the reader saying " +
                "the app does not diverge from the system, and posting either line then freezes " +
                "the cell against a system that flips at dusk.",
            listOf(
                "fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing) {",
                "if (ids.isEmpty()) return",
                "val snapshot = drawing.snapshot",
                "val palette = drawing.palette",
                "val manager = AppWidgetManager.getInstance(app)",
                "val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } ?: app.getString(R.string.app_name)",
                "ids.forEach { id ->",
                "val views = RemoteViews(app.packageName, R.layout.widget_recent)",
                "if (palette != null) {",
                BACKGROUND,
                "views.setTextColor(R.id.widget_recent_notice, palette.label)",
                "}",
                "views.setTextViewText(R.id.widget_recent_notice, notice)",
                "@Suppress(\"DEPRECATION\")",
                "views.setRemoteAdapter(R.id.widget_recent_list, adapterIntent(app, id))",
                "views.setPendingIntentTemplate(R.id.widget_recent_list, rowTapTemplate(app))",
                "views.setEmptyView(R.id.widget_recent_list, R.id.widget_recent_notice)",
                "manager.updateAppWidget(id, views)",
                "manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)",
                "}",
            ),
            functionBody(DRAW, "fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing)"),
        )
        assertEquals(
            "nothing that draws this widget may call setBackgroundColor. It is one word away " +
                "from the line above and it costs the cell its rounded corner — the shape drawable " +
                "is thrown away and the home screen shows a bare rectangle of colour.",
            emptyList<String>(),
            (kotlinLines(DRAW) + kotlinLines(SERVICE)).filter { "setBackgroundColor" in it },
        )
    }

    /**
     * The three texts of a row take the palette; the account dot does NOT.
     *
     * The sender takes `rowPrimary` (Material `onSurface`, what `EmailListItem` draws a sender in)
     * and the two quieter lines take `label`: without them a row keeps `@color/widget_row_primary`,
     * resolved through `values-night` from the SYSTEM's mode, so a reader who forced the app to
     * light on a dark phone reads near-white text on the app's light surface.
     *
     * ⛔ AND THE DOT IS PINNED HERE, UNCHANGED, in the same list. Its colour is the ACCOUNT's (#112)
     * and it is the only thing on an interleaved list saying which mailbox a line came from; painted
     * from `palette.accent` — one plausible-looking edit — every dot becomes the same colour and the
     * row stops answering the only question it was added to answer, with nothing broken to see.
     * ⛔ The weight is not in scope here either: read and unread are told apart by `weighted(…)` and
     * by nothing else, and a colour must not start saying it too.
     */
    @Test fun `a served row takes its text colours from the palette, and its dot from the account`() {
        assertEquals(
            "RecentMailWidgetFactory.getViewAt must colour the sender with palette.rowPrimary and " +
                "the subject and date with palette.label, from the palette held beside the rows. " +
                "Dropped, the row keeps the colour res/values-night froze into the layout and a " +
                "reader whose app is light reads white on white. ⛔ And the account dot must keep " +
                "its setColorFilter on row.accountColor: a theme colour there and the dot no " +
                "longer says which mailbox the message came from.",
            listOf(
                "override fun getViewAt(position: Int): RemoteViews {",
                "val views = RemoteViews(context.packageName, R.layout.widget_recent_row)",
                "val item = rows.getOrNull(position)",
                "if (item == null) {",
                "views.setTextViewText(R.id.widget_recent_row_primary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_secondary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_date, \"\")",
                "views.setViewVisibility(R.id.widget_recent_row_account, View.INVISIBLE)",
                "return views",
                "}",
                "val row = item.drawn",
                "val colours = palette",
                "if (colours != null) {",
                "views.setTextColor(R.id.widget_recent_row_primary, colours.rowPrimary)",
                "views.setTextColor(R.id.widget_recent_row_secondary, colours.label)",
                "views.setTextColor(R.id.widget_recent_row_date, colours.label)",
                "}",
                "views.setTextViewText(R.id.widget_recent_row_primary, weighted(row.primary, row.unread))",
                "views.setTextViewText(R.id.widget_recent_row_secondary, weighted(row.secondary.orEmpty(), row.unread))",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_secondary,",
                "if (row.secondary == null) View.GONE else View.VISIBLE,",
                ")",
                "views.setTextViewText(R.id.widget_recent_row_date, weighted(row.date, row.unread))",
                ACCOUNT_DOT,
                "views.setViewVisibility(",
                "R.id.widget_recent_row_account,",
                "if (row.accountColor == null) View.INVISIBLE else View.VISIBLE,",
                ")",
                "views.setOnClickFillInIntent(R.id.widget_recent_row_root, RecentMailWidgetDraw.rowFillIn(item.tap))",
                "return views",
            ),
            functionBody(SERVICE, "override fun getViewAt(position: Int): RemoteViews"),
        )
    }

    /**
     * ⭐ THE CARRIED PALETTE IS NULLABLE, and it is the same `null` [RecentMailWidgetFactory]
     * already knew how to draw: "a row drawn with no `setTextColor` keeps the colour its layout
     * froze in … not a regression".
     *
     * That sentence is the rule for the WHOLE pane now, not a special case of the factory. Null
     * reaches every drawing site — the frame here, the counter one file over, the rows — and it
     * means the same thing at each: post nothing, the resources hold the right answer. It arrives
     * on two paths, a read that timed out and an app that does not diverge from the system
     * ([widgetFollowsResources], executed in [WidgetThemeTest]).
     *
     * ⛔ Make this field non-null again and the second path cannot exist: every draw would bake a
     * literal int into the `RemoteViews`, there is no `-night` twin behind those ints, no trigger
     * listens for `ACTION_CONFIGURATION_CHANGED` and `updatePeriodMillis="0"` means no timer — so
     * the cell would keep the day's colours through the evening switch until mail arrived.
     */
    @Test fun `the palette carried beside the rows is optional, and null means the resources answer`() {
        assertEquals(
            "RecentWidgetDrawing.palette must be nullable. Null is not an absence to be defaulted " +
                "away: it is the reader's answer that nothing needs posting, either because the " +
                "read timed out or because the app follows the system with no pure black and no " +
                "Material You. Non-null again and every draw bakes a literal colour into the " +
                "RemoteViews with no -night resource behind it and no trigger to redraw on a " +
                "configuration change.",
            listOf(
                "internal data class RecentWidgetDrawing(val snapshot: RecentWidgetSnapshot, " +
                    "val palette: WidgetPalette?)",
            ),
            kotlinLines(DRAW).filter { it.startsWith("internal data class RecentWidgetDrawing") },
        )
        assertEquals(
            "and the factory's own field must stay nullable with it — it is the one site that " +
                "already drew null correctly, and its KDoc is the rule the other two now follow.",
            listOf("private var palette: WidgetPalette? = null"),
            kotlinLines(SERVICE).filter { it.startsWith("private var palette") },
        )
    }

    /**
     * ⛔ THE ROWS AND THEIR COLOURS COME OUT OF ONE READ, and the whole body is pinned because the
     * mutation is one line long: `palette = RecentMailWidgetDraw.drawing?.palette` reads perfectly
     * well and is a second read.
     *
     * The frame and the rows are drawn at different moments — the frame when the app asks, the rows
     * when the launcher pulls — so two reads mean two answers. The reader switches the app to light
     * and the cell shows a light frame over rows still in the dark theme's colours until something
     * makes the launcher refetch, which `updatePeriodMillis="0"` guarantees is not soon. It is the
     * same drift the held snapshot exists to prevent for the text, and the same fix: one
     * [RecentWidgetDrawing], both fields off it.
     *
     * ⛔ [kotlinx.coroutines.withTimeoutOrNull] and not `withTimeout` stays pinned with it: this
     * runs on a binder thread the launcher is waiting on, and a `CancellationException` from here
     * escapes a binder callback, which takes the process down.
     */
    @Test fun `the rows and the colours they are drawn in come from the same read`() {
        assertEquals(
            "RecentMailWidgetFactory.onDataSetChanged must take the rows AND the palette off the " +
                "same RecentWidgetDrawing. Read separately, the frame and the rows answer from two " +
                "different moments and the home screen shows a light box over dark lines until " +
                "something makes the launcher refetch — which nothing does on a schedule.",
            listOf(
                "override fun onDataSetChanged() {",
                "val app = context.applicationContext as? Application ?: return",
                "val read = try {",
                "runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) } }",
                "} catch (t: Throwable) {",
                "Log.w(TAG, \"Latest-messages widget not refreshed; the cell keeps what it last showed\", t)",
                "null",
                "}",
                "val drawing = read ?: RecentMailWidgetDraw.drawing ?: return",
                "rows = drawing.snapshot.rows",
                "palette = drawing.palette",
            ),
            functionBody(SERVICE, "override fun onDataSetChanged()"),
        )
        assertEquals(
            "and what onDataSetChanged filled must be dropped by onDestroy, both of them: a " +
                "palette left behind on a dead factory is a colour outliving the read it came from.",
            listOf(
                "override fun onDestroy() {",
                "rows = emptyList()",
                "palette = null",
            ),
            functionBody(SERVICE, "override fun onDestroy()"),
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")

        const val BACKGROUND =
            "views.setInt(R.id.widget_recent_root, \"setBackgroundResource\", " +
                "WidgetThemeReader.background(palette.surface))"

        const val ACCOUNT_DOT =
            "views.setInt(R.id.widget_recent_row_account, \"setColorFilter\", " +
                "row.accountColor ?: 0)"
    }
}
