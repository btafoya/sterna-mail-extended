package app.sterna.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramRamps
import app.sterna.ui.theme.applyPureBlack

/**
 * WHICH pre-built background the cell asks for — three named states, not a colour.
 * `setBackgroundColor` REPLACES the shape drawable with a flat fill and `setBackgroundTintList` is
 * API 31 against a minSdk of 26, so the background travels as `setBackgroundResource`. The
 * consequence, deliberate: under Material You the text colours follow the wallpaper while the
 * background stays Arctic / Pelagic / black, there being no drawable for a runtime colour.
 */
internal enum class WidgetSurface { Light, Dark, Black }

/** The colours of one widget cell, already resolved from the app's own scheme — everything a
 *  `RemoteViews` can be told without a Compose tree. */
internal data class WidgetPalette(
    val surface: WidgetSurface,
    /** Material `primary` — the counter's big figure and a row's unread dot. */
    val accent: Int,
    /** Material `onSurfaceVariant` — the label, a row's second line, the date. */
    val label: Int,
    /** Material `onSurface` — the sender's name on a row, as `EmailListItem` draws it in the app. */
    val rowPrimary: Int,
)

/** [SternaTheme]'s dark/light decision, mirrored outside Compose, the widget being drawn in the
 * launcher's process. Only the `SYSTEM` arm may consult [systemNight]: `res/values-night`
 *  answered the system for all three arms, so an app forced to light left a dark cell. */
internal fun widgetIsDark(themeMode: ThemeMode, systemNight: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemNight
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The cell's colours, from the scheme the app is ALREADY on. [scheme] arrives chosen, exactly as
 *  [applyPureBlack] takes it: pure black then covers the brand palette and `dynamicDarkColorScheme`
 *  alike, instead of being silently cancelled by Material You. */
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
 * resources already holding the right colours — and the badge tones, which are NEVER null.
 *
 * The asymmetry is the point: a palette may be withheld because the launcher re-resolves the
 * resources at every inflation, for free, with this process dead. A BITMAP has no resource behind
 * it, so tones withheld are a badge NOT DRAWN — and the row that withholds the palette is the
 * default install.
 */
internal data class WidgetColours(val palette: WidgetPalette?, val monogramRamps: List<ToneRamp>)

/** The tones a badge is painted in, from the scheme the app is ALREADY on — pure, and executed in
 * the JVM suite. [applyPureBlack] changes none of the six colours today, `pulledToBlack` pulling
 *  the background and surface roles while a badge comes from the container families; pinned as a
 *  fact rather than as a promise. */
internal fun widgetMonogramRamps(scheme: ColorScheme, dark: Boolean, pureBlack: Boolean): List<ToneRamp> =
    applyPureBlack(scheme, dark, pureBlack).monogramRamps()

/**
 * Does the app's theme AGREE with the system's, so that the cell needs no override at all?
 *
 * The decision the whole pane turns on, and easy to read as a special case worth deleting. The
 * override exists ONLY to say that the app DIVERGES from the system, and the three settings are
 * exactly the three ways it can; with none of them [widgetPalette] returns, by construction, what
 * `res/values` and `res/values-night` already hold, so posting nothing is the identical drawing.
 *
 * And it buys what the palette costs: a palette is posted as literal values FROZEN into a cached
 * `RemoteViews`, the surface drawables have no `-night` twin and nothing listens for a configuration
 * change, so a reader on the DEFAULT settings whose phone switches to dark at dusk would watch both
 * cells stay light until the next message. Answering `true` hands that row back to the launcher —
 * the reason no configuration receiver is needed, and why this may not be "simplified" away.
 */
internal fun widgetFollowsResources(themeMode: ThemeMode, pureBlack: Boolean, dynamicColor: Boolean): Boolean =
    themeMode == ThemeMode.SYSTEM && !pureBlack && !dynamicColor
