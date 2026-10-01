package app.sterna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sterna.R

internal enum class RowMonogramFace { INITIAL, CHECK }

internal fun rowMonogramFace(selected: Boolean): RowMonogramFace =
    if (selected) RowMonogramFace.CHECK else RowMonogramFace.INITIAL

internal fun rowMonogramLabel(selected: Boolean): Int =
    if (selected) R.string.a11y_deselect else R.string.a11y_select

/** Takes the 40 dp circle to the 48 dp minimum touch target. */
private val MonogramTouchOverflow = 4.dp

/**
 * Measures the content [overflow] larger on each side but reports it at its own size, so the
 * pointer target grows into the neighbours' space without moving them. Not
 * `minimumInteractiveComponentSize()`: that one reports the 48 dp too and would widen the row by 8 dp.
 */
private fun Modifier.touchTargetOverflow(overflow: Dp): Modifier = layout { measurable, constraints ->
    val px = overflow.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout((placeable.width - 2 * px).coerceAtLeast(0), (placeable.height - 2 * px).coerceAtLeast(0)) {
        placeable.place(-px, -px)
    }
}

/** Primary, not a monogram tone: the check has to read on the selected row's `secondaryContainer`. */
internal fun checkedMonogramColors(scheme: ColorScheme): ToneRamp = ToneRamp(scheme.primary, scheme.onPrimary)

/**
 * The head of a message row: the sender's [Monogram], or a check in a circle of the same size once
 * the row is selected. [onClick] null leaves it inert, as a row outside the list has no selection.
 */
@Composable
fun RowMonogram(
    seed: String,
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val tapLabel = stringResource(rowMonogramLabel(selected))
    // The circle is clipped at 48 dp, not 40: a clip also bounds hit testing, so clipping the
    // inner circle would cut the target back to 40 dp. The semantics replace the initial's letter.
    val tap = onClick?.let {
        Modifier
            .touchTargetOverflow(MonogramTouchOverflow)
            .clip(CircleShape)
            .clickable(onClick = it)
            .clearAndSetSemantics {
                contentDescription = tapLabel
                role = Role.Button
            }
            .padding(MonogramTouchOverflow)
    } ?: Modifier
    when (rowMonogramFace(selected)) {
        RowMonogramFace.INITIAL -> Monogram(seed = seed, label = label, modifier = tap)
        RowMonogramFace.CHECK -> {
            val colors = checkedMonogramColors(MaterialTheme.colorScheme)
            Box(
                modifier = tap
                    .size(MonogramSize)
                    .clip(CircleShape)
                    .background(colors.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onContainer)
            }
        }
    }
}
