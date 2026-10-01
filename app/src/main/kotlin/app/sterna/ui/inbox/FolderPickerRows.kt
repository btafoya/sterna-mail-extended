package app.sterna.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R

/** One row of a move-to-folder picker, the Recent section and the whole list alike: a suggested
 *  row reads exactly as the same row further down, [usualForSender] adding only an icon at its end. */
@Composable
internal fun FolderPickerRowItem(row: FolderPickerRow, usualForSender: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (row.path != null) {
                Text(
                    text = row.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines, not one: a single line elides at the end, in dp, after the path was
                    // already trimmed by character count, eating the nearest parent at a large font size.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (usualForSender) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = stringResource(R.string.inbox_move_usual_for_sender),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(16.dp),
            )
        }
    }
}

/** A title over a part of a picker's list, in the style of `SettingsSection`, without its
 *  horizontal margin: the sheet already sets the rows' own. */
@Composable
internal fun FolderPickerSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}
