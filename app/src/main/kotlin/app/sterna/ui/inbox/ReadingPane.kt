package app.sterna.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.PaneSplit
import app.sterna.ui.message.MessageScreen

/**
 * The reading pane beside the list (#103): the invitation line while nothing is open, otherwise
 * the reader on [ReadingPaneState.anchor], built EXACTLY as the `message/…` destination builds it
 * from the same five arguments (`SternaApp.kt`): the live paged flow for a list tap, a snapshot
 * of the search results or of the unfolded conversation for the other two, nothing for a lone
 * message.
 *
 * `key(session)` is what makes a tap a new reading: it recreates the reader — the per-page
 * ViewModel stores, the frozen snapshots, the pager on its initial page — as pushing a new route
 * would. A swipe in the pane changes the anchor without changing the session, so the pager the
 * finger is on stays, and only the row painted current in the list moves ([ReadingPaneRule]).
 *
 * Every action that takes the message away (Back, archive, delete, move, and the reader's own
 * unread / snooze / spam, which all call `onBack`) EMPTIES the pane — never "the next message":
 * settling on a page marks it read, and nothing may happen to a message nobody chose to open
 * (`SECURITY.md`). [InboxViewModel.closePane] returns whether there was something to act on, so
 * a second tap on the same action finds the pane already empty and does nothing. The Undo
 * snackbar shows on the list, as it does after the full-screen reader; Undo puts the row back,
 * not the message in the pane.
 *
 * ⛔ No animation around this: no `AnimatedContent`, no `Crossfade`. A fade composites the body
 * WebView through an offscreen layer, which is the door to the native crash of #10.
 */
@Composable
fun ReadingPane(
    state: ReadingPaneState,
    inboxViewModel: InboxViewModel,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val anchor = state.anchor
    if (anchor == null) {
        EmptyReadingPane()
        return
    }
    key(state.session) {
        val listSource = if (anchor.src == "list") inboxViewModel.pagedEmails else null
        val searchResults = if (anchor.src == "search") {
            remember(inboxViewModel) { inboxViewModel.state.value.searchResults }
        } else {
            null
        }
        val threadKey = if (anchor.src == "thread") anchor.thread?.let { ThreadKey.decode(it) } else null
        val threadEntries = if (threadKey != null) {
            remember(inboxViewModel, threadKey) { inboxViewModel.threadEntries(threadKey) }
        } else {
            null
        }
        MessageScreen(
            anchorEmailId = anchor.emailId,
            anchorAccountId = anchor.accountId,
            initialIndex = anchor.index,
            listSource = listSource,
            searchResults = searchResults,
            threadEntries = threadEntries,
            onBack = { inboxViewModel.closePane() },
            onDelete = { email -> if (inboxViewModel.closePane()) inboxViewModel.delete(email) },
            onArchive = { email -> if (inboxViewModel.closePane()) inboxViewModel.archive(email) },
            onMove = { email, targetMailboxId, targetAccountId ->
                if (inboxViewModel.closePane()) inboxViewModel.moveTo(email, targetMailboxId, targetAccountId)
            },
            onReply = onReply,
            onComposeTo = onComposeTo,
            paneMode = true,
            onPageSettled = inboxViewModel::followPage,
        )
    }
}

/**
 * The reading pane while nothing is open in it: one line of invitation, centred (#103).
 */
@Composable
fun EmptyReadingPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.reading_pane_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * The list alone, or the list beside a [detail] pane, on the [split] the host decided from the
 * window width ([app.sterna.ui.paneSplit]). With no split, or nothing to show in the detail, the
 * list is composed exactly as before — a narrow window must not change by a pixel.
 */
@Composable
internal fun ListDetailPanes(
    split: PaneSplit?,
    detail: (@Composable () -> Unit)?,
    list: @Composable () -> Unit,
) {
    if (split == null || detail == null) {
        list()
        return
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(split.listWidthDp.dp).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}
