package app.sterna.widget

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import app.sterna.R
import app.sterna.container
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android half of the widget's colours: everything [widgetPalette] and [widgetIsDark] cannot
 * touch because it needs an [android.app.Application], a [Configuration] or a resource id.
 *
 * The two rules next door decide; this only FETCHES what they decide on. That split is deliberate
 * and it is what makes the decision testable at all — there is no Robolectric in this repository,
 * so anything holding an Android type is unreachable by the suite and can only be pinned as text
 * (`UnreadWidgetPaletteWiringLintTest`).
 */
internal object WidgetThemeReader {

    /**
     * EVERYTHING one reading of the colours learns — the palette override to post, and the tones a
     * monogram badge is painted in — or NULL, meaning the read never answered at all.
     *
     * THERE ARE TWO KINDS OF NULL HERE NOW, and they are not the same claim.
     *
     *  1. THIS FUNCTION returning null: the read TIMED OUT. Nothing whatever is known, so nothing
     *     is posted and no badge is drawn — the cell stays exactly as it was drawn last, which is
     *     the behaviour from before this pane: the compiled resources.
     *  2. [WidgetColours.palette] being null: [widgetFollowsResources] — the app does not diverge
     *     from the system, so the palette and `values` / `values-night` would draw the SAME pixels,
     *     and posting nothing additionally leaves the launcher re-resolving `-night` at every
     *     inflation. The full argument, and why it must not be deleted as a special case, is in
     *     that function's own KDoc; it is what keeps a reader on the DEFAULT settings from watching
     *     the system go dark at dusk while both cells stay light until the next message.
     *
     * AND THAT SECOND ONE NO LONGER SHORT-CIRCUITS THE FUNCTION. It used to be a
     * `return@withTimeoutOrNull null` and it now decides the PALETTE FIELD alone, because it would
     * otherwise take the badge tones with it. `values` / `values-night` answer for TEXT; a BITMAP
     * has no resource behind it, so tones withheld are a badge not drawn — and the row that
     * withholds the palette is `SYSTEM` + no pure black + no Material You, the settings nobody has
     * touched. The default install is exactly the one that would have lost its badges.
     *
     * Every caller draws a null palette by omitting its colour lines, which is what
     * [RecentMailWidgetFactory] has always done for its rows — "a row drawn with no `setTextColor`
     * keeps the colour its layout froze in … not a regression" — and that sentence is the rule for
     * the whole pane rather than one file's special case.
     *
     * [withTimeoutOrNull] AND NEVER `withTimeout`. Three DataStore `first()` calls answer late
     * on a cold process, and this runs on two paths that a `CancellationException` would wreck:
     * inside `UnreadWidgetProvider.onUpdate`'s `goAsync()` window, whose `PendingResult` must be
     * finished or the system kills the process; and at the tail of `FetchAndNotify.run`, whose
     * `runCatching { … }.rethrowIfCancelled()` would turn a slow colour read into a cancelled mail
     * delivery. It is the guard the rest of this package already writes down at every boundary.
     *
     * All three are READ, every draw, and none of them may become a constant. The widget used to
     * take its colours from `res/values` + `res/values-night`, i.e. from the SYSTEM's day/night
     * mode: a reader who forced the app to light on a dark phone got a dark cell, and the pure
     * black option never reached the home screen at all (#117).
     *
     * The night mode comes from [Configuration.UI_MODE_NIGHT_MASK] and NOT from
     * `AppCompatDelegate.getDefaultNightMode()`: `setDefaultNightMode` is never called anywhere in
     * this repository — the app's dark theme is a Compose scheme, not a platform mode — so that
     * call would answer `MODE_NIGHT_FOLLOW_SYSTEM` for ever. And only `ThemeMode.SYSTEM` gets to
     * consult it, which is [widgetIsDark]'s whole job.
     *
     * `dynamicColor &&` is not decoration. Material You is OPT-IN in this app and off by
     * default; a cell that followed the wallpaper while the app wears its own palette is the same
     * defect this whole pane fixes, turned inside out. `dynamicDarkColorScheme` /
     * `dynamicLightColorScheme` are ordinary functions, not composables, so they are readable from
     * here exactly as `SternaTheme` reads them (`ui/theme/Theme.kt`).
     */
    suspend fun colours(app: Application): WidgetColours? = withTimeoutOrNull(PALETTE_TIMEOUT_MS) {
        val settings = app.container.settingsRepository
        val themeMode = settings.themeMode.first()
        val pureBlack = settings.pureBlack.first()
        val dynamicColor = settings.dynamicColor.first()
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = widgetIsDark(themeMode, systemNight = night == Configuration.UI_MODE_NIGHT_YES)
        val scheme = when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)
            dark -> PelagicColorScheme
            else -> ArcticColorScheme
        }
        WidgetColours(
            palette = if (widgetFollowsResources(themeMode, pureBlack, dynamicColor)) null
            else widgetPalette(scheme, dark, pureBlack),
            monogramRamps = widgetMonogramRamps(scheme, dark, pureBlack),
        )
    }

    /**
     * The palette half of [colours], and NOTHING ELSE — one projection, no second read of anything.
     *
     * It is what the counter still calls ([UnreadWidgetDraw]), whose behaviour must not have moved
     * by a hair: same settings, same bound, same two null paths. A settings read reinstalled
     * here would answer from a different moment than the rows', and would spend a second DataStore
     * open inside a `goAsync()` window.
     */
    suspend fun palette(app: Application): WidgetPalette? = colours(app)?.palette

    /**
     * How long [colours] may spend before the resources answer instead.
     *
     * Deliberately SMALL, and much smaller than everything that encloses it: the counter's
     * `UnreadWidgetProvider.DRAW_TIMEOUT_MS` and both widgets' `REFRESH_TIMEOUT_MS` are 5 s, and
     * `RecentMailWidgetFactory.READ_TIMEOUT_MS` — a binder thread the launcher is waiting on — is
     * 4 s. At a quarter of the smallest of them, a colour read that never answers can never be
     * what makes the MAIL read miss its budget, and on the counter's path (where this read falls
     * outside the bound that covers the count and inside the `goAsync()` window) it cannot push
     * the broadcast towards the ~10 s at which the system kills the process.
     *
     * A second is also generous for the work: three `first()` calls on one DataStore file, whose
     * only real cost is the first open and parse on a cold process. And expiring costs nothing —
     * the cell is drawn from `values` / `values-night`, i.e. exactly as it was before this pane,
     * so there is nothing to retry and no state to repair.
     */
    private const val PALETTE_TIMEOUT_MS = 1_000L

    /**
     * WHICH pre-built drawable carries [surface] — three files, one per state, and the only place
     * that names them.
     *
     * They exist because a `RemoteViews` can be handed a resource id but not a shape: the whole
     * reason [WidgetSurface] is a closed set rather than a colour is written out in its own KDoc.
     */
    @DrawableRes
    fun background(surface: WidgetSurface): Int = when (surface) {
        WidgetSurface.Light -> R.drawable.widget_surface_light
        WidgetSurface.Dark -> R.drawable.widget_surface_dark
        WidgetSurface.Black -> R.drawable.widget_surface_black
    }
}
