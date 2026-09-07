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
 * WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line. The price is that reformatting a pinned call reddens this test — loud, and
 * one line to fix.
 */
class RecentMailWidgetPaletteWiringLintTest {

    /**
     * `setBackgroundResource`, NEVER `setBackgroundColor`.
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
                "val palette = drawing.colours?.palette",
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
     * AND THE DOT IS PINNED HERE, UNCHANGED, in the same list. Its colour is the ACCOUNT's (#112)
     * and it is the only thing on an interleaved list saying which mailbox a line came from; painted
     * from `palette.accent` — one plausible-looking edit — every dot becomes the same colour and the
     * row stops answering the only question it was added to answer, with nothing broken to see.
     * The weight is not in scope here either: read and unread are told apart by `weighted(…)` and
     * by nothing else, and a colour must not start saying it too.
     */
    @Test fun `a served row takes its text colours from the palette, and its dot from the account`() {
        assertEquals(
            "RecentMailWidgetFactory.getViewAt must colour the sender with palette.rowPrimary and " +
                "the subject and date with palette.label, from the palette held beside the rows. " +
                "Dropped, the row keeps the colour res/values-night froze into the layout and a " +
                "reader whose app is light reads white on white. ⛔ And the account dot must keep " +
                "its setColorFilter on row.accountColor: a theme colour there and the dot no " +
                "longer says which mailbox the message came from. ⭐ The badge is the third " +
                "colour on this row and the only one that is a BITMAP: it comes from " +
                "monogramFor(row), i.e. from the ramps read beside the rows, and it is null both " +
                "when the ramps are unknown and when the row carries no monogram at all — under " +
                "the app lock and at the NONE setting, the two states in which a row names " +
                "nobody. ⛔ Its out-of-range branch must clear the bitmap AND hide the view: a " +
                "recycled row would otherwise wear the face of a message that is gone, and there " +
                "is no res/values-night behind a bitmap to make that merely stale.",
            listOf(
                "override fun getViewAt(position: Int): RemoteViews {",
                "val views = RemoteViews(context.packageName, R.layout.widget_recent_row)",
                "val item = rows.getOrNull(position)",
                "if (item == null) {",
                "views.setTextViewText(R.id.widget_recent_row_primary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_secondary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_date, \"\")",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, null)",
                "views.setViewVisibility(R.id.widget_recent_row_monogram, View.INVISIBLE)",
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
                "val badge = monogramFor(row)",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, badge)",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_monogram,",
                "if (badge == null) View.INVISIBLE else View.VISIBLE,",
                ")",
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
        assertEquals(
            "and the badge's own two lines are pinned with it: the tones come from the field " +
                "filled by the SAME read as the rows, and null there means no badge — never a " +
                "ramp chosen here. ⛔ A default palette on this path is the one thing the whole " +
                "pane forbids, and unlike a text colour it cannot be left to res/values-night: " +
                "the badge is a bitmap and there is no night twin behind it.",
            listOf(
                "private fun monogramFor(row: RecentWidgetRow): Bitmap? {",
                "val monogram = row.monogram ?: return null",
                "val ramps = monogramRamps ?: return null",
                "return WidgetMonogram.badge(context, monogram, ramps)",
            ),
            functionBody(SERVICE, "private fun monogramFor("),
        )
    }

    /**
     * THE CARRIED PALETTE IS NULLABLE, and it is the same `null` [RecentMailWidgetFactory]
     * already knew how to draw: "a row drawn with no `setTextColor` keeps the colour its layout
     * froze in … not a regression".
     *
     * That sentence is the rule for the WHOLE pane now, not a special case of the factory. Null
     * reaches every drawing site — the frame here, the counter one file over, the rows — and it
     * means the same thing at each: post nothing, the resources hold the right answer. It arrives
     * on two paths, a read that timed out and an app that does not diverge from the system
     * ([widgetFollowsResources], executed in [WidgetThemeTest]).
     *
     * Make this field non-null again and the second path cannot exist: every draw would bake a
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
                    "val colours: WidgetColours?)",
            ),
            kotlinLines(DRAW).filter { it.startsWith("internal data class RecentWidgetDrawing") },
        )
        assertEquals(
            "and the factory's own field must stay nullable with it — it is the one site that " +
                "already drew null correctly, and its KDoc is the rule the other two now follow.",
            listOf("private var palette: WidgetPalette? = null"),
            kotlinLines(SERVICE).filter { it.startsWith("private var palette") },
        )
        assertEquals(
            "⭐ and the BADGE TONES ride beside it, nullable HERE for a different reason than the " +
                "palette is: null means the colour read did not answer at all, so no badge is " +
                "drawn — it never means \"the default palette\". WidgetColours.monogramRamps " +
                "itself is not nullable, because a bitmap has no res/values-night to fall back on.",
            listOf("private var monogramRamps: List<ToneRamp>? = null"),
            kotlinLines(SERVICE).filter { it.startsWith("private var monogramRamps") },
        )
    }

    /**
     * THE ROWS AND THEIR COLOURS COME OUT OF ONE READ, and the whole body is pinned because the
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
     * [kotlinx.coroutines.withTimeoutOrNull] and not `withTimeout` stays pinned with it: this
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
                "palette = drawing.colours?.palette",
                "monogramRamps = drawing.colours?.monogramRamps",
            ),
            functionBody(SERVICE, "override fun onDataSetChanged()"),
        )
        assertEquals(
            "and what onDataSetChanged filled must be dropped by onDestroy, all three of them: a " +
                "palette or a set of badge tones left behind on a dead factory is a colour " +
                "outliving the read it came from.",
            listOf(
                "override fun onDestroy() {",
                "rows = emptyList()",
                "palette = null",
                "monogramRamps = null",
            ),
            functionBody(SERVICE, "override fun onDestroy()"),
        )
    }

    /**
     * THE CACHE IS KEYED ON THE COLOUR IT ACTUALLY PAINTED — the one decision of this pane that
     * no executed test can reach, because it is an ARGUMENT and not a rule.
     *
     * [widgetMonogramKey] is pure and is run next door ([WidgetMonogramRulesTest]), so what a key
     * made of a letter, a colour and a side distinguishes is measured. What is handed to it is not:
     * passing `monogram.slot` where `argb` belongs type-checks, reads perfectly well, and gives back
     * a key that is stable across a theme change — which is precisely the defect. The launcher would
     * then be served the previous palette's disc for the life of the process, since
     * `updatePeriodMillis="0"` means nothing ever redraws on a schedule and there is no invalidation
     * anywhere to catch up.
     *
     * And the bound is in BYTES. A cap counted in entries means a different amount of memory on
     * every phone — a badge at xxxhdpi is sixteen times the pixels of an mdpi one — and it is the
     * largest screens that would hold the most.
     */
    @Test fun `the badge is cached under the colour it was painted, and the cache is bounded in bytes`() {
        assertEquals(
            "WidgetMonogram.badge must resolve the colour first and put THAT colour in the key. " +
                "Keyed on the slot instead, the entry survives a theme change it should have " +
                "missed, and the home screen keeps the old palette's discs until the process dies.",
            listOf(
                "fun badge(context: Context, monogram: RecentRowMonogram, ramps: List<ToneRamp>): Bitmap {",
                "val side = context.resources.getDimensionPixelSize(R.dimen.widget_recent_monogram)",
                "val argb = widgetMonogramArgb(monogram, ramps)",
                "val key = widgetMonogramKey(monogram.initial, argb, side)",
                "cache.get(key)?.let { return it }",
                "return draw(monogram.initial, argb, side).also { cache.put(key, it) }",
            ),
            functionBody(MONOGRAM, "fun badge(context: Context"),
        )
        assertEquals(
            "and the cache must stay bounded in BYTES: sizeOf in kilobytes against a kilobyte " +
                "cap. A cap in entries holds sixteen times the memory on an xxxhdpi screen for the " +
                "same number, and nothing on any screen would report it.",
            listOf(
                "private const val CACHE_KB = 512",
                "private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {",
                "override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024",
            ),
            kotlinLines(MONOGRAM).filter {
                it.startsWith("private const val CACHE_KB") ||
                    it.startsWith("private val cache =") ||
                    it.startsWith("override fun sizeOf(")
            },
        )
    }

    /**
     * AND THE DISC ITSELF — the one place in this pane where a one-character mutation is
     * invisible to everything else.
     *
     * [widgetMonogramLetterArgb] is pure and is RUN next door ([WidgetMonogramRulesTest]), so the
     * rule "black or white, by WCAG luminance against the disc" is measured. What no executed test
     * can see is what is HANDED to it: `widgetMonogramLetterArgb(0xFF000000.toInt())` type-checks,
     * reads perfectly well, keeps the function called and correct — and paints every initial of
     * every row white. On the pastel tones a light scheme hands out that is 1.40:1, a letter that
     * is technically drawn and practically invisible, which is precisely what
     * [widgetMonogramLetterArgb]'s own KDoc forbids in writing. It survived the whole suite.
     *
     * The other two lines are pinned with it and are the same kind of fact:
     *
     * - the BASELINE comes from the paint's `fontMetrics`. A glyph's ink sits above its baseline,
     *   so `drawText(initial, centre, centre, letter)` — the obvious simplification — leaves every
     *   letter floating high by roughly a third of its height, on a 24 dp disc where that is
     *   obvious and nowhere near a test;
     * - the letter's size is `side * LETTER_SCALE`, the ratio the app's own badge draws at, which
     *   is what makes the home screen's badge read as the same badge as the list's.
     *
     * WHAT THIS CANNOT SEE: the pixels. `Bitmap`, `Canvas` and `Paint` do not run in this suite
     * — merely touching [WidgetMonogram] would run the class initialiser of its `LruCache` — so
     * that the disc comes out round, centred and legible is a bench claim. This rule pins the
     * ARGUMENTS; nothing here executes a single line of drawing.
     */
    @Test fun `the badge's letter is coloured against the disc it sits on, and centred on the font's metrics`() {
        assertEquals(
            "WidgetMonogram.draw must pass the DISC'S OWN colour to widgetMonogramLetterArgb — " +
                "never a constant, and never `argb`'s replacement by one. A literal there keeps " +
                "the contrast rule called and correct while every initial in the widget comes " +
                "out the same colour: white on a light scheme's pastel tones is 1.40:1, drawn " +
                "and unreadable, on the one surface nobody can debug. The baseline must come " +
                "from the paint's fontMetrics (centred on side / 2 alone, every letter floats " +
                "high by about a third of its height) and the size from LETTER_SCALE, the ratio " +
                "the in-app badge draws at.",
            listOf(
                "private fun draw(initial: String, argb: Int, side: Int): Bitmap {",
                "val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)",
                "val canvas = Canvas(bitmap)",
                "val centre = side / 2f",
                "val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }",
                "canvas.drawCircle(centre, centre, centre, disc)",
                "val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {",
                "color = widgetMonogramLetterArgb(argb)",
                "textSize = side * LETTER_SCALE",
                "textAlign = Paint.Align.CENTER",
                "}",
                "val metrics = letter.fontMetrics",
                "canvas.drawText(initial, centre, centre - (metrics.ascent + metrics.descent) / 2f, letter)",
                "return bitmap",
            ),
            functionBody(MONOGRAM, "private fun draw(initial: String, argb: Int, side: Int)"),
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val MONOGRAM = file("app/src/main/kotlin/app/sterna/widget/WidgetMonogram.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")

        const val BACKGROUND =
            "views.setInt(R.id.widget_recent_root, \"setBackgroundResource\", " +
                "WidgetThemeReader.background(palette.surface))"

        const val ACCOUNT_DOT =
            "views.setInt(R.id.widget_recent_row_account, \"setColorFilter\", " +
                "row.accountColor ?: 0)"
    }
}
