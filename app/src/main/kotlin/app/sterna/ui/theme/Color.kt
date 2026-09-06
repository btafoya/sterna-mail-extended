package app.sterna.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Sterna Mail's brand palette — the Arctic tern: a calm, cold base. The primary action colour is a
 * desaturated sea teal and not coral, red reading as "destructive"; coral, the tern's beak, is a
 * marginal accent mapped to tertiary and never fills a button or a large surface.
 *
 * Two named schemes follow the tern's migration: [ArcticColorScheme] light, [PelagicColorScheme]
 * dark. Colours are tuned for WCAG AA on the text-bearing roles.
 */

// --- Primary action: desaturated sea teal ---
private val TealDeep = Color(0xFF2F5E59) // light-mode primary, AA with white text
private val TealSoft = Color(0xFF6FB1AB) // dark-mode primary

// --- Marginal accent: the tern's coral beak (tertiary only) ---
private val CoralDeep = Color(0xFFD33E28)
private val CoralBright = Color(0xFFFF6A4D)

// --- Secondary: the sea ---
private val SeaTeal = Color(0xFF2E6E7E)
private val SeaTealLight = Color(0xFF6FC9DC)

val ArcticColorScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6E6E1),
    onPrimaryContainer = Color(0xFF0B201D),
    inversePrimary = Color(0xFF9BCFC9),
    secondary = SeaTeal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFE9F3),
    onSecondaryContainer = Color(0xFF00363F),
    tertiary = CoralDeep,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD2),
    onTertiaryContainer = Color(0xFF3F0400),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF2B313A),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF2B313A),
    surfaceVariant = Color(0xFFE0E4E9),
    onSurfaceVariant = Color(0xFF51606E),
    surfaceTint = TealDeep,
    inverseSurface = Color(0xFF2D3138),
    inverseOnSurface = Color(0xFFEFF1F4),
    outline = Color(0xFF6E7782),
    outlineVariant = Color(0xFFC2C8CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFFD9DCE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F6),
    surfaceContainer = Color(0xFFECEFF2),
    surfaceContainerHigh = Color(0xFFE6EAEE),
    surfaceContainerHighest = Color(0xFFE1E5E9),
)

val PelagicColorScheme = darkColorScheme(
    primary = TealSoft,
    onPrimary = Color(0xFF06302C),
    primaryContainer = Color(0xFF1E4B47),
    onPrimaryContainer = Color(0xFFC6E6E1),
    inversePrimary = TealDeep,
    secondary = SeaTealLight,
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF1E5666),
    onSecondaryContainer = Color(0xFFBFE9F3),
    tertiary = CoralBright,
    onTertiary = Color(0xFF4A0F03),
    tertiaryContainer = Color(0xFF8C2A17),
    onTertiaryContainer = Color(0xFFFFDBD2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1A1F),
    onBackground = Color(0xFFDEE3E7),
    surface = Color(0xFF0E1A1F),
    onSurface = Color(0xFFDEE3E7),
    surfaceVariant = Color(0xFF3C4750),
    onSurfaceVariant = Color(0xFFBCC6CE),
    surfaceTint = TealSoft,
    inverseSurface = Color(0xFFDEE3E7),
    inverseOnSurface = Color(0xFF2B3137),
    outline = Color(0xFF87919B),
    outlineVariant = Color(0xFF3C4750),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF34404A),
    surfaceDim = Color(0xFF0E1A1F),
    surfaceContainerLowest = Color(0xFF091317),
    surfaceContainerLow = Color(0xFF16222B),
    surfaceContainer = Color(0xFF18222B),
    surfaceContainerHigh = Color(0xFF222D36),
    surfaceContainerHighest = Color(0xFF2C3841),
)

/**
 * How far the raised surface roles are pulled toward black in the OLED scheme.
 *
 * Not a fraction of brightness: [lerp] interpolates in Oklab, where black is the origin, so
 * pulling by f scales the emitted light by (1−f)³. At 0.2 a surface emits about half of what it did;
 * at 0.5 it would emit an eighth, which is why a first pass left an `AlertDialog` on a black page
 * with an edge five times weaker than today's.
 *
 * The bound that keeps it honest is executed by the tests: after compression every container must
 * still stand at least as far above the black page as the weakest edge this app already ships.
 */
private const val PureBlackPull = 0.2f

/**
 * The OLED variant of a DARK scheme: the page itself becomes true black — the pixels an OLED panel
 * can switch off — while every raised surface is compressed toward black.
 *
 * [background], surface and surfaceDim are the page, so they go to black outright. The six container
 * roles are not: Material 3 draws no border on an AlertDialog, a DropdownMenu, a bottom sheet or the
 * drawer, so their edge exists only because their fill is lighter than what is behind them, and
 * flattening them would dissolve ~42 call sites at once. Pulling them with a single [PureBlackPull]
 * keeps the tonal ladder intact.
 *
 * Nothing else moves: surfaceVariant, the outlines, the coloured containers, the scrim and the
 * inverse roles carry meaning of their own. Applying this to a light scheme would be nonsense —
 * [applyPureBlack] guards that.
 */
internal fun ColorScheme.pulledToBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = lerp(surfaceContainerLowest, Color.Black, PureBlackPull),
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, PureBlackPull),
    surfaceContainer = lerp(surfaceContainer, Color.Black, PureBlackPull),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, PureBlackPull),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, PureBlackPull),
    surfaceBright = lerp(surfaceBright, Color.Black, PureBlackPull),
)
