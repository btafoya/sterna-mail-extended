package app.sterna.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R

/** The `$answered` and `$forwarded` keywords as the list row and the reader's header draw them (#259). */
internal enum class ReplyMark(@StringRes val label: Int) {
    ANSWERED(R.string.a11y_answered),
    FORWARDED(R.string.a11y_forwarded),
}

/**
 * The marks to draw, reply before forward. Each flag is read for its own mark only: a message both
 * replied to and forwarded carries the two, and neither stands in for the other.
 */
internal fun replyMarks(answered: Boolean, forwarded: Boolean): List<ReplyMark> = listOfNotNull(
    ReplyMark.ANSWERED.takeIf { answered },
    ReplyMark.FORWARDED.takeIf { forwarded },
)

/** Kept out of [ReplyMark] so the decision runs on the JVM without building a vector. */
internal fun ReplyMark.vector(): ImageVector = when (this) {
    ReplyMark.ANSWERED -> Icons.AutoMirrored.Filled.Reply
    ReplyMark.FORWARDED -> Icons.AutoMirrored.Filled.Forward
}

/**
 * The marks in a row, each named for a screen reader on its own. Emits nothing at all on a message
 * that carries neither, so [modifier]'s padding cannot leave a gap where no mark is drawn.
 */
@Composable
internal fun ReplyMarkIcons(answered: Boolean, forwarded: Boolean, modifier: Modifier = Modifier) {
    val marks = replyMarks(answered = answered, forwarded = forwarded)
    if (marks.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        marks.forEach { mark ->
            Icon(
                mark.vector(),
                contentDescription = stringResource(mark.label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
