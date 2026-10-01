package app.sterna.ui.scheduled

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.data.text.toPlainText
import kotlinx.coroutines.launch

/**
 * One scheduled send, read-only: nothing on it edits the row, and its one action is to cancel it,
 * so the user sees what she cancels. [viewModel] is the Scheduled list's own, so a cancellation
 * still running when this screen is left answers on the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledSendScreen(
    id: Long,
    viewModel: ScheduledSendsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onLandOnDrafts: (accountId: String, message: String) -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val cancelling by viewModel.cancelling.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val accountLabels by viewModel.accountLabels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val row = rows?.firstOrNull { it.id == id }
    // Set by whichever exit fires first, so the row's disappearance under an exit already taken is
    // not announced a second time.
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(outcome) {
        val taken = viewModel.takeOutcome(id) ?: return@LaunchedEffect
        val text = context.getString(scheduledCancelExitMessage(taken))
        when (taken) {
            // Not awaited here: taking the outcome changes this effect's key, which would cancel it.
            ScheduledCancelExit.StayNotCancelled -> scope.launch { snackbarHostState.showSnackbar(text) }
            is ScheduledCancelExit.ToDrafts -> {
                leaving = true
                onLandOnDrafts(taken.accountId, text)
            }
            ScheduledCancelExit.ToListAccountGone, ScheduledCancelExit.NoLongerScheduled -> {
                leaving = true
                viewModel.say(text)
                onClose()
            }
        }
    }

    // `rows` is null until the table is first read: an empty answer is a disappearance, not yet.
    val rowGone = rows != null && row == null
    val closes = !leaving &&
        scheduledSendClosesAsGone(rowGone, cancelling = id in cancelling, answerPending = outcome?.id == id)
    LaunchedEffect(closes) {
        if (!closes) return@LaunchedEffect
        leaving = true
        viewModel.say(context.getString(R.string.scheduled_no_longer_scheduled))
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (row == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val body = remember(row.htmlBody, row.textBody) { toPlainText(richBodyFrom(row.htmlBody) { row.textBody }) }
        val attachments = remember(row.attachmentsJson) { OutboxAttachments.decode(row.attachmentsJson) }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                scheduledSendAtLabel(row.sendAtMillis),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                row.subject.ifBlank { stringResource(R.string.message_no_subject) },
                style = MaterialTheme.typography.titleLarge,
            )
            Field(R.string.compose_from, fromLine(row.fromName, row.fromEmail, accountLabels[row.accountId] ?: row.accountId))
            Field(R.string.compose_to, row.recipients)
            row.cc?.takeIf { it.isNotBlank() }?.let { Field(R.string.compose_cc, it) }
            row.bcc?.takeIf { it.isNotBlank() }?.let { Field(R.string.compose_bcc, it) }
            if (attachments.isNotEmpty()) {
                Text(
                    stringResource(R.string.message_attachments, attachments.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                val fallback = stringResource(R.string.compose_attachment_fallback)
                attachments.forEach { a ->
                    Text(a.name?.takeIf { it.isNotBlank() } ?: fallback, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = { viewModel.cancel(row.id, row.accountId) },
                enabled = row.id !in cancelling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scheduled_cancel_send))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer { Text(body, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String) {
    Text(
        "${stringResource(label)}: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The identity the message goes out under, and the account it is queued on when they differ. */
private fun fromLine(fromName: String?, fromEmail: String?, account: String): String {
    val identity = when {
        fromEmail.isNullOrBlank() -> null
        fromName.isNullOrBlank() -> fromEmail
        else -> "$fromName <$fromEmail>"
    }
    return when {
        identity == null -> account
        fromEmail == account -> identity
        else -> "$identity ($account)"
    }
}
