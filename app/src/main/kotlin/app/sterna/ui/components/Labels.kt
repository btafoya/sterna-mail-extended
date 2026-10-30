package app.sterna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.db.EmailLabels

/**
 * The JMAP label ids a message carries (#274), as the server wrote them: `work/clients` and
 * `Read Later` are shown whole, neither humanised nor cut to their last level. The rule is the one
 * the cache stores them by, [EmailLabels.of], so what is drawn cannot drift from what was kept. A
 * bare `$label:` keyword is kept by the cache but not drawn: its chip would be an empty box.
 */
internal fun labelsOf(keywords: Map<String, Boolean>): List<String> =
    EmailLabels.of(keywords).filter { it.isNotBlank() }

/** What a list row draws of a message's labels: the first [ROW_LABELS_SHOWN], and how many are left for `+n`. */
internal data class RowLabels(val shown: List<String>, val more: Int)

internal const val ROW_LABELS_SHOWN = 2

internal fun rowLabels(labels: List<String>): RowLabels = labels.take(ROW_LABELS_SHOWN).let { shown ->
    RowLabels(shown = shown, more = labels.size - shown.size)
}

/**
 * A row's labels as one group weighted `1f` against the subject's `2f`. Unweighted children are
 * measured first and keep their width, so the reply marks and the conversation pill are never
 * squeezed; what is left goes two thirds at most to the subject and one third at most to the
 * labels, neither share handed to the other when unused (`fill = false`). Inside the group the
 * label chips split it evenly and ellipse, while `+n` is unweighted so it is never cut. Emits
 * nothing on a message without labels, so the group's start padding leaves no gap.
 */
@Composable
internal fun RowScope.RowLabelChips(labels: List<String>, fill: Color) {
    val row = rowLabels(labels)
    if (row.shown.isEmpty()) return
    Row(
        modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.shown.forEach { label ->
            LabelChip(text = label, fill = fill, modifier = Modifier.weight(1f, fill = false))
        }
        if (row.more > 0) {
            val description = pluralStringResource(R.plurals.a11y_more_labels, row.more, row.more)
            LabelChip(
                text = "+${row.more}",
                fill = fill,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

/** Every label of the open message. Emits nothing when there is none, so [modifier]'s padding leaves no gap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeaderLabelChips(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { label ->
            LabelChip(text = label, fill = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

/** Drawn like the list's "(Draft)" chip, its [fill] handed down for the reason given on [chipFill]. */
@Composable
private fun LabelChip(text: String, fill: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
