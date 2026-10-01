package app.sterna.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.search.searchHistorySuggestions
import app.sterna.ui.inbox.SearchUi

/**
 * Recent searches under a "Recent searches" header whose Clear asks first, one tappable line each
 * with a cross that removes it, under the inbox's search bar or in its place. Nothing at all, header
 * included, when [entries] is empty: that is also what the "Remember searches" setting off gives.
 * A plain column, never items of a lazy list: the inbox shows it above its results, and a text as a
 * lazy key next to `searchResultKey` keys is the duplicate-key crash of #92 waiting for a match.
 */
@Composable
fun SearchHistoryRows(
    entries: List<String>,
    onRun: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.search_history_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.settings_clear)) }
        }
        entries.forEach { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRun(text) }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = stringResource(R.string.search_history_recent),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(text) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_history_remove, text),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ClearSearchHistoryDialog(
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** The one confirmation before the whole search history goes, from the settings and from the bar. */
@Composable
fun ClearSearchHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_search_history_clear_dialog_title)) },
        text = { Text(stringResource(R.string.settings_search_history_clear_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * What the inbox's search bar offers from [kept]. Nothing during a selection: a picked search
 * replaces the list under it, and the selection's Delete would then hit rows no longer shown. For a
 * typed text, only while [SearchUi.typing]: once kept as a search, the matches would sit over its
 * results, until a tap on the field asks for them again ([afterFieldTap]). Not the field's focus:
 * the bar requests it again whenever the inbox comes back from a message, and a result opened in
 * the reading pane never takes it away.
 */
internal fun barSuggestions(kept: List<String>, search: SearchUi, selecting: Boolean): List<String> = when {
    !search.active || selecting -> emptyList()
    search.query.isBlank() || search.typing -> searchHistorySuggestions(kept, search.query)
    else -> emptyList()
}

/**
 * [search] after a tap on the bar's field: the suggestions come back over the results, as at the
 * first keystroke. A tap and not the field gaining focus, for the reason [barSuggestions] gives.
 */
internal fun afterFieldTap(search: SearchUi): SearchUi = if (search.active) search.copy(typing = true) else search
