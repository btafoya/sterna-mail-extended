package app.sterna.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * The sender's name, then their address in grey when the two fit whole in the slot; otherwise the
 * name alone over the whole slot, ellipsed, and none of the address: a shortened address reads as
 * another one. Decided in the measure pass, for the reason `yieldToBody` gives.
 */
@Composable
internal fun SenderWithAddress(name: String, address: String, nameWeight: FontWeight, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = nameWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = address,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (nameText, addressText) = measurables
        val gap = 8.dp.roundToPx()
        val nameWidth = nameText.maxIntrinsicWidth(constraints.maxHeight)
        val addressWidth = addressText.maxIntrinsicWidth(constraints.maxHeight)
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (senderLineShowsAddress(constraints.maxWidth, nameWidth, addressWidth, gap)) {
            val namePlaced = nameText.measure(loose.copy(maxWidth = nameWidth))
            val addressPlaced = addressText.measure(loose.copy(maxWidth = addressWidth))
            val height = maxOf(namePlaced.height, addressPlaced.height)
            layout(constraints.constrainWidth(nameWidth + gap + addressWidth), constraints.constrainHeight(height)) {
                namePlaced.placeRelative(0, (height - namePlaced.height) / 2)
                addressPlaced.placeRelative(nameWidth + gap, (height - addressPlaced.height) / 2)
            }
        } else {
            val namePlaced = nameText.measure(loose)
            layout(constraints.constrainWidth(namePlaced.width), constraints.constrainHeight(namePlaced.height)) {
                namePlaced.placeRelative(0, 0)
            }
        }
    }
}
