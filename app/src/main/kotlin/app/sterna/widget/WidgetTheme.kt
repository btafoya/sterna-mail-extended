package app.sterna.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramRamps
import app.sterna.ui.theme.applyPureBlack

/**
 * WHICH pre-built background the cell asks for — three named states, not a colour.
 *
 * `RemoteViews.setInt(id, "setBackgroundColor", …)` calls `View.setBackgroundColor(int)`, which
 * REPLACES the shape drawable with a flat fill: the cell would lose the rounded corners that are
 * the only thing telling it apart from a rectangle of wallpaper. `setBackgroundTintList` keeps the
 * shape but is API 31, and `minSdk` is 26. So the background travels as `setBackgroundResource`
 * pointing at a drawable built at compile time — hence a closed set of states, and no free colour.
 *
 * ⚠ The consequence, deliberate: under Material You the three text [WidgetPalette] colours do come
 * from the wallpaper-derived scheme, but the background stays Arctic / Pelagic / black. A Material
 * You background is a computed colour, and there is no drawable for a colour we only learn at
 * runtime. Accepted as the cheapest option — the alternative is a generated drawable per launch.
 */
internal enum class WidgetSurface { Light, Dark, Black }

/**
 * The colours of one widget cell, already resolved from the app's own scheme. Three ints and a
 * background state — everything a `RemoteViews` can be told without a Compose tree.
 */
internal data class WidgetPalette(
    val surface: WidgetSurface,
    /** Material `primary` — the counter's big figure and a row's unread dot. */
    val accent: Int,
    /** Material `onSurfaceVariant` — the label, a row's second line, the date. */
    val label: Int,
    /** Material `onSurface` — the sender's name on a row, as `EmailListItem` draws it in the app. */
    val rowPrimary: Int,
)

/**
 * [SternaTheme]'s dark/light decision (`ui/theme/Theme.kt`), mirrored outside Compose.
 *
 * The widget is drawn by RemoteViews in the launcher's process, where `isSystemInDarkTheme()` does
 * not exist; [systemNight] is read from `Configuration.UI_MODE_NIGHT_MASK` by the caller. Only the
 * `SYSTEM` arm may consult it at all — that is the whole point. `res/values-night` answered the
 * system for all three arms, so a system in dark plus an app forced to light left a dark cell.
 */
internal fun widgetIsDark(themeMode: ThemeMode, systemNight: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemNight
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The cell's colours, from the scheme the app is ALREADY on.
 *
 * [scheme] arrives chosen, exactly as [applyPureBlack] takes it and for the same reason its KDoc
 * gives: pure black then covers the brand palette and `dynamicDarkColorScheme` alike, instead of
 * being silently cancelled by Material You.
 */
internal fun widgetPalette(scheme: ColorScheme, dark: Boolean, pureBlack: Boolean): WidgetPalette {
    val resolved = applyPureBlack(scheme, dark, pureBlack)
    return WidgetPalette(
        surface = when {
            dark && pureBlack -> WidgetSurface.Black
            dark -> WidgetSurface.Dark
            else -> WidgetSurface.Light
        },
        accent = resolved.primary.toArgb(),
        label = resolved.onSurfaceVariant.toArgb(),
        rowPrimary = resolved.onSurface.toArgb(),
    )
}

/**
 * What ONE reading of the colours learnt: the override to post — null meaning post nothing, the
 * compiled resources already hold the right colours — and the tones a monogram badge is painted in,
 * which are NEVER null.
 *
 * ⭐ THE ASYMMETRY IS THE POINT, and it is not an oversight to be tidied into one nullable field.
 * A [WidgetPalette] may be withheld because `res/values` and `res/values-night` hold those very
 * colours and the launcher re-resolves them at every inflation, for free, with this process dead
 * ([widgetFollowsResources]). ⛔ A BITMAP HAS NO RESOURCE BEHIND IT: there is no `values-night`
 * badge to fall back on, so tones withheld are a badge NOT DRAWN — and the row that withholds the
 * palette is `SYSTEM` + no pure black + no Material You, i.e. the settings nobody has touched. The
 * default install is precisely the one that would lose its badges.
 */
internal data class WidgetColours(val palette: WidgetPalette?, val monogramRamps: List<ToneRamp>)

/**
 * The tones a badge is painted in, from the scheme the app is ALREADY on — pure, and executed in the
 * JVM suite ([WidgetThemeTest]).
 *
 * [applyPureBlack] for [widgetPalette]'s reason, written out in that function's KDoc: the OLED
 * option must cover the brand palette and `dynamicDarkColorScheme` alike rather than being silently
 * cancelled by Material You. ⚠ Today it changes none of the six colours — `pulledToBlack` pulls the
 * background and surface roles, and a badge is drawn from the three container families — and that is
 * pinned as a fact rather than as a promise.
 */
internal fun widgetMonogramRamps(scheme: ColorScheme, dark: Boolean, pureBlack: Boolean): List<ToneRamp> =
    applyPureBlack(scheme, dark, pureBlack).monogramRamps()

/**
 * Does the app's theme AGREE with the system's, so that the cell needs no override at all?
 *
 * ⭐ THIS IS THE DECISION THE WHOLE PANE TURNS ON, and it is easy to read as a special case worth
 * deleting. It is not. Read it as: the override exists ONLY to say that the app DIVERGES from the
 * system; when it does not diverge, the override has nothing to say and must not be posted.
 *
 * The three settings are exactly the three ways this app can diverge:
 *
 *  - [themeMode] other than `SYSTEM` — the reader forced light or dark against the system;
 *  - [pureBlack] — the OLED surface, which no system mode produces;
 *  - [dynamicColor] — Material You, opt-in here and off by default, which the widget's compiled
 *    resources cannot contain because it is a colour only learnt at runtime.
 *
 * With none of the three, [widgetPalette] returns — BY CONSTRUCTION — what `res/values` and
 * `res/values-night` already hold: `WidgetPaletteTest` pins `values/colors.xml` to the Arctic
 * scheme's `surface`/`primary`/`onSurface`/`onSurfaceVariant` and `values-night/colors.xml` to the
 * Pelagic scheme's, and those four roles are precisely what this widget paints. So on that one row
 * the palette and the resources draw the SAME pixels, and posting nothing is not a shortcut, it is
 * the identical drawing.
 *
 * ⛔ AND IT BUYS THE THING THE PALETTE COSTS. A palette is posted as `setTextColor(int)` and
 * `setBackgroundResource(id)` — literal values FROZEN into the `RemoteViews` the launcher caches.
 * The three `widget_surface_*.xml` have no `-night` twin, nothing listens for
 * `ACTION_CONFIGURATION_CHANGED`, and `updatePeriodMillis="0"` means there is no timer: the only
 * things that redraw are mail arriving and the reader changing a theme setting in the app. So a
 * reader on the DEFAULT settings — `SYSTEM`, no pure black, no Material You — whose phone switches
 * to dark at dusk would watch the app go dark and both home-screen cells stay light until the next
 * message, which on a quiet mailbox is days. Answering `true` here hands that row back to the
 * launcher, which re-resolves `values-night` at every inflation, for free and while the process is
 * dead. It is the reason no configuration-change receiver is needed, and the reason this function
 * may not be "simplified" away.
 *
 * ⚠ It takes no [Configuration] and no [android.app.Application]: it is executed on all twelve
 * combinations by `WidgetThemeTest`, and its answers are pinned there as literals.
 */
internal fun widgetFollowsResources(themeMode: ThemeMode, pureBlack: Boolean, dynamicColor: Boolean): Boolean =
    themeMode == ThemeMode.SYSTEM && !pureBlack && !dynamicColor
