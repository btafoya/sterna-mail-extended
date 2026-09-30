package app.sterna.ui.inbox

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/**
 * The list bar's height: Material's own until the folder line and the account line under it no
 * longer fit, then as tall as both lines and [INBOX_BAR_TITLE_MARGIN]. The line heights are sp, and
 * converting them through [density] applies the system font scale, nonlinear curve included.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun inboxBarHeight(titleLineHeight: TextUnit, subtitleLineHeight: TextUnit, density: Density): Dp {
    val title = if (titleLineHeight.isSp) titleLineHeight else TITLE_LINE_HEIGHT_FALLBACK
    val subtitle = if (subtitleLineHeight.isSp) subtitleLineHeight else SUBTITLE_LINE_HEIGHT_FALLBACK
    val lines = with(density) { title.toDp() + subtitle.toDp() }
    return max(TopAppBarDefaults.TopAppBarExpandedHeight, lines + INBOX_BAR_TITLE_MARGIN)
}

/**
 * What the 64 dp bar leaves around its two lines at font scale 1 (64 − 24 − 16), kept as the
 * lines grow so the bar starts growing exactly where they would begin to clip.
 */
val INBOX_BAR_TITLE_MARGIN: Dp = 24.dp

/** `titleMedium`'s line height in Material 3, for a style that gives none in sp. */
val TITLE_LINE_HEIGHT_FALLBACK: TextUnit = 24.sp

/** `labelMedium`'s line height in Material 3, for a style that gives none in sp. */
val SUBTITLE_LINE_HEIGHT_FALLBACK: TextUnit = 16.sp
