package app.sterna.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R

internal const val FORWARDED_FOLDED_LINES = 4

/**
 * The forward's carried original, under the note, read-only. Only its text is shown: what is sent is
 * `forwarded.html` beside it, appended by `bodiesForSend`, which this block never touches.
 */
@Composable
internal fun ForwardedOriginalBlock(
    preview: ForwardedPreview,
    collapsed: Boolean,
) {
    if (preview == ForwardedPreview.None) return
    var full by rememberSaveable { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (collapsed) Modifier.height(FORWARDED_BLOCK_FOLDED_HEIGHT) else Modifier.heightIn(max = FORWARDED_BLOCK_MAX_HEIGHT))
            .clipToBounds()
            .then(if (preview is ForwardedPreview.Ready) Modifier.clickable { full = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(
            stringResource(R.string.compose_forwarded_title),
            style = MaterialTheme.typography.labelLarge,
            color = dim,
        )
        // Folded to the title while the note has the focus: above the keyboard, the note's three
        // lines reserved by `yieldToBody` are all the height there is.
        if (!collapsed) {
            when (preview) {
                ForwardedPreview.Pending -> Text(
                    stringResource(R.string.compose_forwarded_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
                is ForwardedPreview.Ready -> {
                    Text(
                        stringResource(R.string.compose_forwarded_sent_below),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        maxLines = FORWARDED_FOLDED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ForwardedPreview.None -> Unit
            }
        }
    }
    if (full && preview is ForwardedPreview.Ready) {
        AlertDialog(
            onDismissRequest = { full = false },
            title = { Text(stringResource(R.string.compose_forwarded_title)) },
            text = {
                Text(
                    text = preview.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { full = false }) {
                    Text(stringResource(R.string.compose_forwarded_close))
                }
            },
        )
    }
}
