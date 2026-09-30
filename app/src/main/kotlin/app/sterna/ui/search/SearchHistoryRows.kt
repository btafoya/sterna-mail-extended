package app.sterna.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.search.searchHistorySuggestions
import app.sterna.ui.inbox.SearchUi

/**
 * Recent searches, one tappable line each, shared by the inbox's bar and the search screen. A plain
 * column, never items of a lazy list: the inbox shows it above its results, and a text as a lazy
 * key next to `searchResultKey` keys is the duplicate-key crash of #92 waiting for a match.
 */
@Composable
fun SearchHistoryRows(
    entries: List<String>,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        entries.forEach { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRun(text) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                )
            }
        }
    }
}

/**
 * What the inbox's search bar offers from [kept]. Nothing during a selection: a picked search
 * replaces the list under it, and the selection's Delete would then hit rows no longer shown. For a
 * typed text, only while [SearchUi.typing]: once kept as a search, the matches would sit over its
 * results. Not the field's focus: the bar requests it again whenever the inbox comes back from a
 * message, and a result opened in the reading pane never takes it away.
 */
internal fun barSuggestions(kept: List<String>, search: SearchUi, selecting: Boolean): List<String> = when {
    !search.active || selecting -> emptyList()
    search.query.isBlank() || search.typing -> searchHistorySuggestions(kept, search.query)
    else -> emptyList()
}
