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

/** The Android half of the widget's colours: everything the two rules next door cannot touch,
 *  needing an Application, a Configuration or a resource id. They decide; this only FETCHES what
 *  they decide on, which is what makes the decision testable at all. */
internal object WidgetThemeReader {

    /**
     * EVERYTHING one reading of the colours learns — the palette override to post and the badge
     * tones — or NULL, meaning the read never answered.
     *
     * Two kinds of null, and not the same claim: THIS FUNCTION returning null means the read
     * TIMED OUT, nothing known and no badge drawn; [WidgetColours.palette] being null means
     * [widgetFollowsResources], where posting nothing leaves the launcher re-resolving `-night` at
     * every inflation. That second one no longer short-circuits the function — it decides the
     * PALETTE FIELD alone, or it would take the badge tones with it, and a BITMAP has no resource
     * behind it.
     *
     * [withTimeoutOrNull] and never `withTimeout`: three DataStore reads answer late on a cold
     * process, and this runs inside a `goAsync()` window whose `PendingResult` must be finished and
     * at the tail of `FetchAndNotify.run`, where a CancellationException becomes a cancelled mail
     * delivery.
     *
     * All three settings are READ every draw and none may become a constant: the widget used to
     * take its colours from the SYSTEM's night mode, so an app forced to light got a dark cell
     * (#117). The night mode comes from `UI_MODE_NIGHT_MASK`, `AppCompatDelegate` never being
     * called here. `dynamicColor &&` is not decoration: a cell following the wallpaper while the
     * app wears its own palette is the same defect inside out.
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

    /** The palette half of [colours], and nothing else — one projection, no second read. It is what
     * the counter still calls, whose behaviour must not have moved by a hair: a settings read
     *  reinstalled here would answer from a different moment than the rows'. */
    suspend fun palette(app: Application): WidgetPalette? = colours(app)?.palette

    /** How long [colours] may spend before the resources answer instead. Deliberately SMALL — a
     *  quarter of the smallest enclosing budget — so a colour read that never answers cannot make
     *  the MAIL read miss its own, nor push a broadcast towards the ~10 s at which the system kills
     *  the process. Expiring costs nothing. */
    private const val PALETTE_TIMEOUT_MS = 1_000L

    /** WHICH pre-built drawable carries [surface] — three files, and the only place that names
     *  them: a `RemoteViews` can be handed a resource id but not a shape. */
    @DrawableRes
    fun background(surface: WidgetSurface): Int = when (surface) {
        WidgetSurface.Light -> R.drawable.widget_surface_light
        WidgetSurface.Dark -> R.drawable.widget_surface_dark
        WidgetSurface.Black -> R.drawable.widget_surface_black
    }
}
