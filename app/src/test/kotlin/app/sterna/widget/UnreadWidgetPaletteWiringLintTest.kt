package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [RecentMailWidgetWiringLintTest] next door, and it exists for exactly the same reason: there is
 * no Robolectric in this repository, so a `RemoteViews` cannot be built, let alone executed, by
 * this suite. [WidgetThemeTest] EXECUTES the two rules that decide the colours — `widgetIsDark` and
 * `widgetPalette` — and proves them right by handing them their inputs itself. That says nothing at
 * all about whether the shipped cell goes and asks for them.
 *
 * That gap is the whole defect this pane closes: the counter's colours used to live in
 * `res/values` + `res/values-night`, so the cell followed the SYSTEM's day/night mode and ignored
 * the app's own theme setting and its OLED option. Replacing any line below with a constant puts
 * that back, and no other test in this module would notice.
 *
 * ⛔ WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line. The price is that reformatting a pinned call reddens this test — loud, and
 * one line to fix.
 */
class UnreadWidgetPaletteWiringLintTest {

    /**
     * ⛔ `setBackgroundResource`, NEVER `setBackgroundColor`.
     *
     * `RemoteViews.setInt(id, "setBackgroundColor", …)` calls `View.setBackgroundColor(int)`, which
     * REPLACES the shape drawable with a flat fill: the cell comes out SQUARE, a rectangle of solid
     * colour sitting among rounded neighbours, and every corner rule in this package stays green
     * while it happens. That is why the background travels as one of three pre-built drawables and
     * why [WidgetThemeReader.background] is the only thing that names them.
     *
     * The two `setTextColor` lines are the other half: without them the figure and the label keep
     * the colours the layout froze into them at compile time, so a cell drawn on the app's dark
     * surface would carry the light theme's near-black text on it — unreadable, and only ever
     * visible on a home screen.
     */
    @Test fun `the drawn cell is painted from the palette, and keeps its rounded corner`() {
        assertEquals(
            "UnreadWidgetDraw.render must paint the cell from the WidgetPalette it is handed: the " +
                "background as a pre-built DRAWABLE, then the figure's accent and the label's " +
                "colour. ⛔ setBackgroundColor here replaces the shape with a flat fill and the " +
                "cell comes out square on the home screen; a missing setTextColor leaves the " +
                "layout's compile-time colour on a surface it was never meant for. " +
                "⛔ AND THE THREE OF THEM SIT UNDER `if (palette != null)`, all three, never two: " +
                "null is the reader's own answer that the app does not diverge from the system, " +
                "and posting ANY of the three then freezes that side of the cell against a system " +
                "that flips at dusk. A palette made non-null again, or the guard dropped, is that " +
                "regression — see WidgetThemeTest's widgetFollowsResources.",
            listOf(
                "private fun render(",
                "context: Context,",
                "state: UnreadWidgetState,",
                "target: WidgetTapTarget,",
                "palette: WidgetPalette?,",
                "): RemoteViews =",
                "RemoteViews(context.packageName, R.layout.widget_unread).apply {",
                "if (palette != null) {",
                BACKGROUND,
                "setTextColor(R.id.widget_unread_count, palette.accent)",
                "setTextColor(R.id.widget_unread_label, palette.label)",
                "}",
                "setTextViewText(R.id.widget_unread_count, state.count.toString())",
                "setTextViewText(R.id.widget_unread_label, text(context, state.label))",
                "setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, target))",
            ),
            body(DRAW, "private fun render("),
        )
        assertEquals(
            "nothing in UnreadWidgetDraw may call setBackgroundColor. It is one word away from " +
                "the line above and it costs the cell its rounded corner — the shape drawable is " +
                "thrown away and the home screen shows a bare rectangle.",
            emptyList<String>(),
            linesOf(DRAW).filter { "setBackgroundColor" in it },
        )
    }

    /**
     * The three settings are READ, every draw, and the wallpaper scheme stays CONDITIONED on the
     * one that opts into it.
     *
     * Each is one line and each is trivially replaceable by a constant. `val pureBlack = false` is
     * the OLED setting ceasing to exist for the home screen while the settings screen goes on
     * showing it switched on. And dropping the `dynamicColor &&` guard is the defect this pane
     * fixes, turned inside out: the cell would follow Material You on a reader whose app does not,
     * because Material You here is opt-in and OFF by default.
     */
    @Test fun `the cell's colours are read from the app's own settings, never assumed`() {
        assertEquals(
            "WidgetThemeReader.palette must read themeMode, pureBlack and dynamicColor from the " +
                "app's settings, take the system's night mode from the Configuration, and keep " +
                "the wallpaper-derived scheme behind the dynamicColor setting. A constant in any " +
                "of them silently ungoverns the home screen: the reader forces the app to light " +
                "and the cell stays dark, or switches pure black on and the cell stays navy. " +
                "Dropping `dynamicColor &&` is the same defect reversed — the cell would follow " +
                "Material You while the app, where it is opt-in and off by default, does not.",
            listOf(
                SIGNATURE,
                "val settings = app.container.settingsRepository",
                "val themeMode = settings.themeMode.first()",
                "val pureBlack = settings.pureBlack.first()",
                "val dynamicColor = settings.dynamicColor.first()",
                FOLLOWS_RESOURCES,
                "val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK",
                "val dark = widgetIsDark(themeMode, systemNight = night == Configuration.UI_MODE_NIGHT_YES)",
                "val scheme = when {",
                "dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->",
                "if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)",
                "dark -> PelagicColorScheme",
                "else -> ArcticColorScheme",
                "}",
                "widgetPalette(scheme, dark, pureBlack)",
            ),
            body(READER, "suspend fun palette(app: Application)"),
        )
        assertEquals(
            "WidgetThemeReader.background must map each of the three cell states to its OWN " +
                "drawable. Two states pointing at one file is a reader whose pure-black setting, " +
                "or whose dark theme, draws the wrong surface behind the right text.",
            listOf(
                "fun background(surface: WidgetSurface): Int = when (surface) {",
                "WidgetSurface.Light -> R.drawable.widget_surface_light",
                "WidgetSurface.Dark -> R.drawable.widget_surface_dark",
                "WidgetSurface.Black -> R.drawable.widget_surface_black",
            ),
            body(READER, "fun background(surface: WidgetSurface): Int"),
        )
    }

    /**
     * ⭐ THE READ IS BOUNDED, AND IT MAY ANSWER "NOTHING" — the two things this reader buys, and
     * both of them are one line away from being lost to a refactor.
     *
     * 1. BOUNDED, with [kotlinx.coroutines.withTimeoutOrNull] and NEVER `withTimeout`. Three
     *    DataStore `first()` calls that answer late used to be unbounded on two paths that must
     *    not stretch: `UnreadWidgetProvider.onUpdate` bounds its COUNT and then calls `draw`, so
     *    this read fell outside the bound and inside the `goAsync()` window, whose `PendingResult`
     *    never finishing gets the process killed by the system; and `UnreadWidgetDraw.refresh`
     *    runs at the tail of `FetchAndNotify.run`, whose `runCatching { … }.rethrowIfCancelled()`
     *    would turn a `withTimeout`'s `CancellationException` into a cancelled DELIVERY pass.
     * 2. NULLABLE, on TWO paths and for one reason: nothing is known, or nothing needs saying.
     *    The timeout's null is the honest fallback (the behaviour before this pane: the
     *    resources); [widgetFollowsResources]'s null is the stronger claim, that the palette and
     *    the resources would draw the same pixels, so posting nothing costs no colour and keeps
     *    the cell following the system's dusk switch by itself.
     *
     * ⛔ `withTimeout` must appear NOWHERE in this file. It is eight characters from the right
     * call and its failure mode is a dead process or a mail pass cancelled — neither visible here.
     */
    @Test fun `the palette read is bounded, cancels nothing, and is allowed to answer nothing`() {
        val body = body(READER, "suspend fun palette(app: Application)")
        assertEquals(
            "WidgetThemeReader.palette must be BOUNDED by withTimeoutOrNull and must be allowed " +
                "to return null. Unbounded, a slow DataStore read sits inside the goAsync() " +
                "window of UnreadWidgetProvider.onUpdate — the PendingResult is not finished and " +
                "the system kills the process — and inside the tail of a delivery pass. And with " +
                "withTimeout instead, the timeout arrives as a CancellationException that " +
                "FetchAndNotify.run's rethrowIfCancelled would re-throw, cancelling the pass that " +
                "was delivering the mail.",
            listOf(SIGNATURE),
            body.take(1),
        )
        assertEquals(
            "WidgetThemeReader.palette must return null on BOTH paths. The timeout's null is the " +
                "first; widgetFollowsResources' is the second, and it is the one this pane exists " +
                "for — with ThemeMode.SYSTEM, pure black off and Material You off, the app cannot " +
                "diverge from the system, so posting nothing draws the same pixels AND leaves the " +
                "launcher free to re-resolve values/values-night when the system flips at dusk. " +
                "Delete this line and both cells stay light through the evening switch until the " +
                "next message arrives.",
            listOf(FOLLOWS_RESOURCES),
            body.filter { "widgetFollowsResources" in it },
        )
        assertEquals(
            "nothing in WidgetThemeReader may call withTimeout: it reports a slow read as a " +
                "CancellationException, and this code runs inside a goAsync() window and inside " +
                "the delivery pass's own runCatching { … }.rethrowIfCancelled().",
            emptyList<String>(),
            linesOf(READER).filter { "withTimeout(" in it },
        )
        assertEquals(
            "and the BUDGET is the point of the bound, not the keyword. It must stay far below " +
                "every budget that encloses it: UnreadWidgetProvider.DRAW_TIMEOUT_MS and both " +
                "REFRESH_TIMEOUT_MS are 5 s, RecentMailWidgetFactory.READ_TIMEOUT_MS is 4 s on a " +
                "binder thread the launcher waits on, and the system kills a goAsync() receiver " +
                "at about 10 s. Raised to the neighbours' order of magnitude, a colour read that " +
                "never answers is once again what makes the MAIL read miss its budget, or what " +
                "pushes the broadcast past the kill — the defect the bound was added for, with " +
                "the right function still called. Expiring is free: the cell is then drawn from " +
                "values / values-night, exactly as before this pane.",
            listOf("private const val PALETTE_TIMEOUT_MS = 1_000L"),
            linesOf(READER).filter { it.startsWith("private const val PALETTE_TIMEOUT_MS") },
        )
    }

    /**
     * The draw READS the palette and HANDS it to the render — the join between the two rules above.
     *
     * Both of them can be perfectly correct while nothing calls them: `render` would go on drawing
     * a cell whose colours come from the layout's compile-time resources, which is precisely the
     * state this pane found the widget in. ⛔ And the read belongs HERE, not inside `render`:
     * `render` is not suspending and never read anything, so a settings read pushed down into it
     * would have to block a binder thread the launcher is waiting on.
     */
    @Test fun `the draw reads the palette and hands it to the render`() {
        assertEquals(
            "UnreadWidgetDraw.draw must read the palette from WidgetThemeReader and pass it to " +
                "render. Without that join the two colour rules are dead code and the cell keeps " +
                "the colours res/values-night froze into it — the very defect this pane fixes.",
            listOf(
                "suspend fun draw(app: Application, ids: IntArray, unread: Int) {",
                "if (ids.isEmpty()) return",
                "val palette = WidgetThemeReader.palette(app)",
                "val accounts = app.container.accountStore.accounts()",
                "val views = render(app, UnreadWidgetContent.of(accounts, unread), UnreadWidgetTap.of(accounts), palette)",
                "val manager = AppWidgetManager.getInstance(app)",
                "ids.forEach { manager.updateAppWidget(it, views) }",
            ),
            body(DRAW, "suspend fun draw(app: Application, ids: IntArray, unread: Int)"),
        )
    }

    /**
     * The code lines of the function opening with [signature] in the file at [path], or [ABSENT]
     * when the file is not there at all — a missing file must print as a diff, not as an exception.
     */
    private fun body(path: String, signature: String): List<String> {
        val file = File(DeclarationSource.ROOT, path)
        if (!file.isFile) return listOf(ABSENT)
        return DeclarationSource.functionBody(file, signature)
    }

    private fun linesOf(path: String): List<String> {
        val file = File(DeclarationSource.ROOT, path)
        if (!file.isFile) return listOf(ABSENT)
        return DeclarationSource.kotlinLines(file)
    }

    private companion object {
        const val ABSENT = "(no such file)"

        const val DRAW = "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt"
        const val READER = "app/src/main/kotlin/app/sterna/widget/WidgetThemeReader.kt"

        /**
         * The signature, pinned as one string because THREE things live on it and each is a
         * separate regression: the return type is NULLABLE (null = post nothing, the resources
         * already hold the right answer), the read is BOUNDED, and it is bounded with
         * `withTimeoutOrNull` rather than `withTimeout`.
         */
        const val SIGNATURE =
            "suspend fun palette(app: Application): WidgetPalette? = " +
                "withTimeoutOrNull(PALETTE_TIMEOUT_MS) {"

        /** The second of the two null paths: the app does not diverge from the system. */
        const val FOLLOWS_RESOURCES =
            "if (widgetFollowsResources(themeMode, pureBlack, dynamicColor)) " +
                "return@withTimeoutOrNull null"

        const val BACKGROUND =
            "setInt(R.id.widget_unread_root, \"setBackgroundResource\", " +
                "WidgetThemeReader.background(palette.surface))"
    }
}
