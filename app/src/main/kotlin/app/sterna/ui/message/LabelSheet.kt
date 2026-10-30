package app.sterna.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.db.EmailLabels
import app.sterna.core.jmap.model.Email
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What a typed label name gives: nothing yet, a refusal, or the id to write. */
internal sealed interface LabelName {
    data object Empty : LabelName
    data object Refused : LabelName
    data class Id(val id: String) : LabelName
}

internal const val LABEL_ID_MAX_LENGTH = 200

/**
 * The id written for a typed name: trimmed, each run of spaces made one `-`, lowercased, so
 * "Read later" and "read  later" are one label. The refusal is RFC 8621's keyword grammar, which
 * the `$label:` prefix shares: printable ASCII only, none of `( ) { ] % * " \`. The 200 cap keeps
 * the whole keyword under the 255 the RFC allows. A `/` is accepted: `setKeyword` escapes it.
 */
internal fun labelIdFor(typed: String): LabelName {
    val id = typed.trim().replace(SPACES, "-").lowercase(Locale.ROOT)
    if (id.isEmpty()) return LabelName.Empty
    if (id.length > LABEL_ID_MAX_LENGTH || id.any { it !in '!'..'~' || it in KEYWORD_SPECIALS }) return LabelName.Refused
    return LabelName.Id(id)
}

private val SPACES = Regex("\\s+")
private const val KEYWORD_SPECIALS = "(){]%*\"\\"

/** The reader's "Labels…" entry: a JMAP account that already uses labels somewhere in its cached mail. */
internal fun showLabelsEntry(isJmap: Boolean, accountLabels: List<String>): Boolean =
    isJmap && accountLabels.isNotEmpty()

/**
 * The sheet's rows: those it already [listed] since it opened, the account's, and the message's own.
 * A label the open message alone carried leaves the account's labels when it is unchecked, and its
 * row must stay to be checked back; one just added is listed before the cache holds it.
 */
internal fun sheetLabels(listed: List<String>, accountLabels: List<String>, messageLabels: List<String>): List<String> =
    EmailLabels.merge(listed + accountLabels + messageLabels)

internal fun Email.withLabel(id: String, held: Boolean): Email {
    val key = EmailLabels.PREFIX + id
    return copy(keywords = if (held) keywords + (key to true) else keywords - key)
}

/**
 * One label write of the reader. Unlike the star's, a refusal is not swallowed: [show] puts the
 * checkbox and the header back to [confirmed], what the cached row holds, and [report] says so.
 * Never to the reverse of [wanted]: the write sends the last tap, so after an uncheck and a recheck
 * queued behind a slow write it sends `true` for a label the server already holds, and its reverse
 * would hide it. The row is written on the server's ack alone, so it is what the server took. No
 * row at all leaves only the reverse to fall back on.
 */
internal suspend fun writeLabelOrRevert(
    wanted: Boolean,
    write: suspend (Boolean) -> Unit,
    confirmed: suspend () -> Boolean?,
    show: (Boolean) -> Unit,
    report: (Throwable) -> Unit,
) {
    try {
        write(wanted)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val held = try {
            confirmed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        show(held ?: !wanted)
        report(t)
    }
}

/** Keyed by (account, message, label id), held by the process for the reason given on [ReaderStarWrites]. */
internal object ReaderLabelWrites : LatestWriteWins<Triple<String, String, String>, Boolean>()

/**
 * The open message's labels as checkboxes, each tap written at once, and a field that adds one.
 * Leaving the sheet, Back included, writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabelSheet(
    labels: List<String>,
    held: List<String>,
    name: String,
    onNameChange: (String) -> Unit,
    onToggle: (id: String, held: Boolean) -> Unit,
    onAdd: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val verdict = labelIdFor(name)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.message_labels_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(labels, key = { it }) { label ->
                val isChecked = label in held
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isChecked,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(label, it) },
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = null)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(R.string.message_label_new)) },
                isError = verdict == LabelName.Refused,
                supportingText = if (verdict == LabelName.Refused) {
                    { Text(stringResource(R.string.message_label_refused)) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { (verdict as? LabelName.Id)?.let { onAdd(it.id) } },
                enabled = verdict is LabelName.Id,
            ) {
                Text(stringResource(R.string.message_label_add))
            }
        }
    }
}
