package app.sterna.ui.compose

import android.Manifest
import android.graphics.Rect
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LocalContentColor
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.jmap.model.Email
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.clear as clearFormatting
import app.sterna.core.data.text.linkAt
import app.sterna.core.data.text.remapAfterEdit
import app.sterna.core.data.text.stylesAt
import app.sterna.core.data.text.toggle as toggleFormatting
import app.sterna.core.data.text.toggleBlock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.container
import app.sterna.contacts.AndroidContacts
import app.sterna.util.isValidEmail
import app.sterna.ui.FORCE_ONBOARDING_PREVIEW
import app.sterna.ui.THUMBNAIL_DP
import app.sterna.ui.attachmentKey
import app.sterna.ui.attachmentLook
import app.sterna.ui.attachmentMeta
import app.sterna.ui.formatSize
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.PickInstantDialogs
import app.sterna.ui.components.PickInstantStep
import app.sterna.ui.components.drawTern
import app.sterna.contacts.ContactSuggestion

// ExperimentalLayoutApi: the leave dialog's FlowRow, which wraps its three answers instead of
// truncating them where the labels are long (German, Russian), same as the settings screens'.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Send the draft this composer is editing to the Trash the way the list does it (#127), through the
     * through the inbox's own ViewModel, so it is the same move and the same Undo.
     */
    onDeleteDraft: (take: () -> Email?) -> Unit,
    replyTo: String? = null,
    mode: String? = null,
    accountId: String? = null,
    restore: Boolean = false,
    to: String? = null,
    cc: String? = null,
    bcc: String? = null,
    subject: String? = null,
    /** The key a mailto: link's or a share's body is parked under, never the body (`composeLinkRoute`). */
    bodyToken: String? = null,
    draftId: String? = null,
    /**
     * The queued row this composer was opened on by Edit from the Outbox (#70), from the route, the twin
     * of [draftId]. Read only by a composer rebuilt after a process death, whose in-memory hand-over
     * is gone: it takes the row back by this id once the startup sweep has parked it.
     */
    outboxId: Long? = null,
    editAsNewId: String? = null,
    viewModel: ComposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val replyQuote by viewModel.replyQuote.collectAsStateWithLifecycle()
    val forwardedPreview by viewModel.forwardedPreview.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val thumbnails by viewModel.attachmentThumbnails.collectAsStateWithLifecycle()
    val attachmentsTotal by viewModel.attachmentsTotal.collectAsStateWithLifecycle()
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val smimeAvailable by viewModel.smimeAvailable.collectAsStateWithLifecycle()
    val pgpMode by viewModel.pgpMode.collectAsStateWithLifecycle()
    val recipientKeys by viewModel.recipientKeys.collectAsStateWithLifecycle()
    val pgpKeylessRecipients by viewModel.pgpKeylessRecipients.collectAsStateWithLifecycle()
    val quotesDecrypted by viewModel.quotesDecrypted.collectAsStateWithLifecycle()
    val quoteRefusal = decryptedQuoteRefusal(quotesDecrypted, pgpMode)
    val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()
    val editingOutbox by viewModel.editingOutbox.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    // The lease on a draft the phone is keeping and the server has not got (#95), non-null only while
    // this composer really holds the row. Not `draftId` and not the route it belongs to: on a row the
    // upload worker consumed between the list being drawn and the tap, both still say "local draft"
    // while this screen holds nothing.
    val editingLocalDraftId by viewModel.editingLocalDraftId.collectAsStateWithLifecycle()
    // Whether deleting that row would leave the server copy it masks behind (#95), true only when
    // this phone never read that copy whole. The confirmation has to say so before the finger lands:
    // the row goes, the mask lifts, and the pre-edit draft comes back at the top of Drafts.
    val deleteKeepsServerCopy by viewModel.deleteKeepsServerCopy.collectAsStateWithLifecycle()
    // The trash icon's confirmation (#127), unconditional unlike the leave dialog: this one is about
    // removing the draft itself. A flag lives where what it acts on lives, so it is the ViewModel's:
    // a bare `remember` fell back to false on every rotation and took the dialog off screen without a
    // word, while `rememberSaveable` outlives the process, which the draft it names does not.
    val pendingDraftDelete by viewModel.pendingDraftDelete.collectAsStateWithLifecycle()
    val lastScheduleMinutes by viewModel.lastScheduleMinutes.collectAsStateWithLifecycle()
    // The leave dialog (#35, #127) and the two chained pre-send guards. All three are the
    // ViewModel's for the reason above: a rotation restarts the composition and a bare `remember`
    // fell back to false, so the question left the screen as if it had been answered. Not
    // `rememberSaveable` either; their real lifetime is the composer's navigation entry.
    val showDiscard by viewModel.pendingDiscard.collectAsStateWithLifecycle()
    val showForgotAttachment by viewModel.pendingForgotAttachment.collectAsStateWithLifecycle()
    val showManyRecipients by viewModel.pendingManyRecipients.collectAsStateWithLifecycle()
    val quoteInClear by viewModel.pendingQuoteInClear.collectAsStateWithLifecycle()
    // The "pick date and time" pair (#161): the calendar, then the clock, and the day chosen between
    // the two. All three are the ViewModel's for the reason above, and the day especially: a
    // rotation between the two dialogs would lose it and bring the clock back over nothing.
    val pendingScheduleDay by viewModel.pendingScheduleDay.collectAsStateWithLifecycle()
    val pendingScheduleTime by viewModel.pendingScheduleTime.collectAsStateWithLifecycle()
    val scheduleDay by viewModel.scheduleDay.collectAsStateWithLifecycle()
    // Whether a draft saved on the server survives leaving, what the leave dialog may not announce as
    // destroyed (#35). Not `draftId != null`: an undone send reopens with restore=true and no draftId
    // while its draft is still in Drafts.
    val savedDraftBehind by viewModel.savedDraftBehind.collectAsStateWithLifecycle()
    val attachmentsTouched by viewModel.attachmentsTouched.collectAsStateWithLifecycle()
    // Reopening a saved draft: whether its text is still being fetched, and what to say if it never
    // arrived. Two flows and not one state, because ComposeState is a single slot. The decision itself
    // is [draftReopenView], out in ComposeText so a JVM test can execute it, and it is taken further
    // down once `applied` exists.
    val draftLoading by viewModel.draftLoading.collectAsStateWithLifecycle()
    val draftLoadFailed by viewModel.draftLoadFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.attachAll(uris)
    }
    val takePhoto = rememberTakePhoto(viewModel)
    // OpenKeychain's dialog round-trip: consume the pause, then launch, nothing between (`composeStateAfterPgpLaunch`).
    val pgpLauncher = rememberPgpInteractionLauncher { data -> viewModel.retryPgpSend(data) }
    LaunchedEffect(state) {
        (state as? ComposeState.PgpInteraction)?.let {
            viewModel.consumePgpInteraction(it)
            pgpLauncher(it.pendingIntent)
        }
    }
    // Plain-language feedback when the user cycles the lock toggle, so the icon states are not a
    // mystery. Only manual toggles announce; an automatic opportunistic switch stays silent (#35).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.pgpToggleAnnounce.collect { mode ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = context.getString(
                    when (mode) {
                        PgpMode.OFF -> R.string.compose_pgp_snack_off
                        PgpMode.SIGN -> R.string.compose_pgp_snack_sign
                        PgpMode.ENCRYPT -> R.string.compose_pgp_snack_encrypt
                        PgpMode.ENCRYPT_UNSIGNED -> R.string.compose_pgp_snack_encrypt_unsigned
                        PgpMode.SMIME_SIGN -> R.string.compose_smime_snack_sign
                        PgpMode.SMIME_ENCRYPT -> R.string.compose_smime_snack_encrypt
                    },
                ),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Outcomes worth reporting after the screen closes: a toast, because compose navigates away the
    // moment the save succeeds.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { res -> Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }

    // Whether the body on screen already carries the reply's quoted original.
    // Declared here because the effect below reads it and Kotlin does not read ahead.
    var quoteLanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val body = viewModel.claimLinkBody(bodyToken)
        viewModel.prepare(
            replyTo, mode, accountId, restore, to, cc, bcc, subject, body, draftId,
            quoteAlreadyOnScreen = quoteLanded,
            outboxId = outboxId,
            editAsNewId = editAsNewId,
        )
        // Attach any files shared into the app (ACTION_SEND), a one-shot handoff we read and clear,
        // so it only lands on this compose screen (#45).
        val app = context.applicationContext as android.app.Application
        app.container.pendingShareUris.takeIf { it.isNotEmpty() }?.let { shared ->
            app.container.pendingShareUris = emptyList()
            viewModel.attachAll(shared)
        }
    }

    // A picked file is staged in cacheDir, which is not ours to keep: Clear cache empties it and
    // Android evicts it under storage pressure. Neither closes this screen, so the chip went on being
    // drawn over nothing and the save was what found out. Swept ON_START, the composer coming back to
    // the front is the only moment it can be noticed, and a LaunchedEffect(Unit) would have run once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.dropVanishedAttachments()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Contacts permission priming (offered once, on first compose) ---
    val contactsPrimed by viewModel.contactsPrimed.collectAsStateWithLifecycle()
    val contactSuggestionsOn by viewModel.contactSuggestionsEnabled.collectAsStateWithLifecycle()
    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setContactSuggestions(granted) }
    var showContactsPriming by remember { mutableStateOf(false) }
    var primingHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(contactsPrimed, contactSuggestionsOn) {
        if (primingHandled) return@LaunchedEffect
        // Real gate: suggestions off and permission not granted and not yet primed. The preview flag
        // forces the sheet regardless.
        val gateOpen = !contactSuggestionsOn && !AndroidContacts.hasPermission(context) && !contactsPrimed
        if (FORCE_ONBOARDING_PREVIEW || gateOpen) {
            showContactsPriming = true
            primingHandled = true
        }
    }
    if (showContactsPriming) {
        // Both "Not now" and a swipe-dismiss mark it primed, so it is never offered again. The toggle
        // still lives in Settings > Privacy.
        val dismissPriming = {
            showContactsPriming = false
            viewModel.markContactsPrimed()
        }
        ModalBottomSheet(onDismissRequest = dismissPriming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.compose_contacts_priming_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.compose_contacts_priming_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = dismissPriming) {
                        Text(stringResource(R.string.compose_contacts_priming_not_now))
                    }
                    Button(onClick = {
                        showContactsPriming = false
                        viewModel.markContactsPrimed()
                        if (AndroidContacts.hasPermission(context)) {
                            viewModel.setContactSuggestions(true)
                        } else {
                            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }) {
                        Text(stringResource(R.string.compose_contacts_priming_enable))
                    }
                }
            }
        }
    }

    // The body and its baseline are the two big things on this screen, and the only two that do not
    // travel in the activity's saved-state parcel: `Parcel.writeString` writes UTF-16, so a ~324 kB
    val slotId = rememberSaveable { newComposerSlotId() }
    val resumeSlot = remember(context, slotId) { ComposerResumeSlot(composerResumeFile(context, slotId)) }
    val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot, viewModel.bodyMemory)) {
        ComposerResumeState(TextFieldValue(), "")
    }
    // The body did not come back, and the save must be told. Above the inline bound a failed disk
    // write parks nothing, so the saver carries the loss instead of the text, everything else comes
    LaunchedEffect(resume.bodyWasLost) { if (resume.bodyWasLost) viewModel.onComposerBodyLost() }

    // "Takes flight": when the message is queued (Done), play a brief lift-off, then navigate back.
    // The send already fired; this is purely cosmetic and must not delay it.
    val motionOn = rememberMotionEnabled()
    var flying by remember { mutableStateOf(false) }
    val fly by animateFloatAsState(
        targetValue = if (flying) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "send-flight",
    )
    LaunchedEffect(state) {
        if (state is ComposeState.Done) {
            if (motionOn) {
                flying = true
                delay(320)
            }
            // Sent, saved as a draft or scheduled: every one lands on Done, and the parked body must
            // go with the screen, see the `closed` flag in [ComposerResumeState] for why a bare
            // clear() here would be written straight back out at teardown.
            resume.close(resumeSlot)
            onDone()
        }
    }

    // A draft the phone was keeping has just been deleted: leave the way the X leaves, a bare
    // popBackStack() onto the Drafts list. Not through ComposeState.Done, which plays the tern's
    // flight and lands on the inbox. The signal comes from the ViewModel and not from the tap, because
    // the destroy has to be durable and the row consumed first.
    LaunchedEffect(Unit) {
        viewModel.localDraftDeleted.collect {
            resume.close(resumeSlot)
            onCancel()
        }
    }

    // Which field opens focused, decided once, before the prefill parameters are shadowed by the
    // editable state below. See [initialComposeFocus].
    val initialFocus = remember {
        initialComposeFocus(
            isDraft = draftId != null,
            isReply = replyTo != null && composeOpening(mode).quotes,
            linkTo = to,
            linkSubject = subject,
        )
    }
    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    // Read by the chips' labels and accessibility only, see `chipLabel`. Nothing clears it: a reopened
    // draft starts it empty and its chips show their addresses.
    var recipientNames by rememberSaveable(stateSaver = recipientNamesSaver) { mutableStateOf(emptyMap<String, String>()) }
    // Like the body, the subject carries its caret (a TextFieldValue, not a bare String) so a tap
    // before its first character can put the cursor at the very start (#26).
    var subject by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // The body carries its caret with it (a TextFieldValue, not a bare String), so a prefilled
    // compose can open with the cursor where writing continues (#63).
    var body by resume.bodyState
    // The body's inline styling (#131) lives beside the TextFieldValue, not inside it: `body` holds
    // plain text and the spans are laid over it at render time, so the ~70 sites that read
    // `body.text` see exactly what they saw before.
    var ranges by resume.rangesState
    // …and its list blocks (#131), beside the spans and beside the text for the same reason: a
    // block is a run of lines, and `body.text` is what the field, the save and the send still read.
    var blocks by resume.blocksState
    // …and its links (#131), held the same way and for the same reason: `body` stays plain text,
    // and a link is a span over it that happens to carry an address.
    var links by resume.linksState
    // What the next keystroke at the caret should carry, once a button was tapped with nothing
    // selected; `null` = no instruction. Transient on purpose: a gesture in flight, not a value worth
    // parking across a rotation.
    var pending by remember { mutableStateOf<Set<Inline>?>(null) }
    // Whether the link dialog is up (#131). `rememberSaveable`, like the composer's own dialogs: a
    // rotation with it open brings the dialog back. It says nothing about what was typed inside it:
    // the two fields are plain `remember`s, so an address half typed is lost to a rotation, measured
    // and accepted, not claimed here as though it were held.
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // "Ask for a read receipt" (RFC 8098), per message, from the overflow menu.
    var requestReceipt by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialCc by rememberSaveable { mutableStateOf("") }
    var initialBcc by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by resume.baselineState
    var baselineRanges by resume.baselineRangesState
    var baselineBlocks by resume.baselineBlocksState
    var baselineLinks by resume.baselineLinksState
    // …the box included: a reopened outbox message can open ticked, so "ticked" is not an edit while
    // ticking or clearing it is. Without this baseline, clearing the box then leaving sent the message
    // still asking (see [ComposeDirty]).
    var initialRequestReceipt by rememberSaveable { mutableStateOf(false) }
    // The body and its styling as one value: derived on every recomposition, never stored twice.
    val rich = RichBody(body.text, ranges, blocks, links)

    // Which compose this is, for the signature rules: a forward carries its original at send time, so
    // its body holds no quote, while both a reply and a forward obey the two settings.
    val isReplyBody = replyTo != null && composeOpening(mode).quotes
    val isReplyOrForward = replyTo != null
    // Changing "From" reads those settings from DataStore, which suspends.
    val scope = rememberCoroutineScope()

    // Land in the recipient field with the keyboard up, unless the recipients are already filled, a
    // reply, a reopened draft, or a mailto: link, in which case the subject or the body takes the
    // focus. The To field self-focuses via its `autoFocus` flag further down.
    val toFocus = remember { FocusRequester() }
    val subjectFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    // Whether the body holds the focus: the formatting bar is shown for it alone (#131).
    var bodyFocused by remember { mutableStateOf(false) }

    LaunchedEffect(prefill) {
        prefill?.let {
            if (!applied) {
                // Trailing ", " so prefilled (reply) recipients render as committed chips.
                val prefilledTo = if (it.to.isNotBlank()) it.to.trimEnd(',', ';', ' ') + ", " else ""
                to = prefilledTo
                cc = if (it.cc.isNotBlank()) it.cc.trimEnd(',', ';', ' ') + ", " else ""
                bcc = if (it.bcc.isNotBlank()) it.bcc.trimEnd(',', ';', ' ') + ", " else ""
                if (it.expand) expanded = true
                // Caret at the end of the prefilled subject ("Re: …"), which is where an edit
                // continues, not at offset 0 as the String field would leave it.
                subject = TextFieldValue(it.subject, TextRange(it.subject.length))
                // Open with the caret where the writing continues: after the last character of a
                // reopened draft, above the quoted original of a reply (#63), and for a mailto: link
                // after the body the link supplied, which is also just above the signature (#83).
                val caret = initialBodyCaret(
                    bodyLength = it.body.length,
                    focus = initialFocus,
                    isDraft = draftId != null,
                    // The link's own body, told from the signature appended under it (#83). Asked of
                    // the ViewModel, which still has it after a rotation empties the hand-off.
                    linkBodyLength = viewModel.claimLinkBody(bodyToken)?.takeIf { it.isNotBlank() }?.length ?: 0,
                )
                if (it.quoted) viewModel.onQuoteInBody()
                body = TextFieldValue(it.body, TextRange(caret ?: 0))
                // …with the styling the prefill carried (#131). `emptyMap()` here opens a styled
                // draft with its bold gone from the screen, and the next save stores that.
                ranges = it.bodyRanges
                // …and its lists, on the same terms: `emptyList()` here opens a draft that held a list
                // without it, and the next save writes that removal.
                blocks = it.bodyBlocks
                // …and its links, the last statement between the four reopen routes and the screen.
                // `emptyList()` here is not "no link yet": the draft is judged reproducible because
                // its anchor parsed, so the next save expunges the server original and stores this
                // removal in its place.
                links = it.bodyLinks
                initialTo = prefilledTo
                initialCc = cc
                initialBcc = bcc
                initialSubject = it.subject
                initialBody = it.body
                // The baseline is what the prefill landed with, styling included: `emptyMap()` here
                // makes a reopened styled draft look edited the moment it opens.
                baselineRanges = it.bodyRanges
                baselineBlocks = it.bodyBlocks
                baselineLinks = it.bodyLinks
                // This prefill carried the quote, the original was not cached, so `prepare` built the
                // whole body with it inside and there is no out-of-band hand-over to raise the flag.
                // Without this post the flag stays false while the quote is plainly in the body, and
                // after a process death the rebuilt ViewModel draws "Couldn't load…" over it (G6).
                if (it.quoted) quoteLanded = true
                // A reopened message that asked for a read receipt still asks: re-editing it enqueues
                // a new row, so a box left clear here cancels the request. Applied inside the same
                // `!applied` gate, so nothing overwrites a box the user has ticked.
                requestReceipt = it.requestReceipt
                initialRequestReceipt = it.requestReceipt
                applied = true
                // The To field self-focuses on first composition; the other two are asked here, once
                // the prefill they open on is in place.
                when (initialFocus) {
                    ComposeFocus.BODY -> runCatching { bodyFocus.requestFocus() }
                    ComposeFocus.SUBJECT -> runCatching { subjectFocus.requestFocus() }
                    ComposeFocus.RECIPIENTS -> Unit
                }
            }
        }
    }

    // A reply opens with To/Subject prefilled instantly from the cache; the quoted original arrives a
    // moment later. Drop it into the body only while the body is still the untouched initial prefill,
    // and re-baseline so it is not seen as an unsaved edit.
    LaunchedEffect(replyQuote) {
        // The re-entry guard, load-bearing with the consume below: retiring the quote writes `null`
        // into the flow, which is a new value, so this effect is relaunched with `replyQuote == null`.
        // Drop this `?:` and that second pass carries an empty quote into the branch below, the body
        // and its baseline would both be emptied, the reply losing its quote and its signature.
        val quote = replyQuote ?: return@LaunchedEffect
        if (canApplyReplyQuote(applied, body.text, initialBody)) {
            viewModel.onQuoteInBody()
            body = TextFieldValue(quote, TextRange(0))
            // A quote replaces a plain prefill: neither the body nor its baseline carries styling.
            ranges = emptyMap()
            blocks = emptyList()
            links = emptyList()
            baselineRanges = emptyMap()
            baselineBlocks = emptyList()
            baselineLinks = emptyList()
            initialBody = quote
            // Dropped in exactly once, after the write: the activity is recreated by a rotation while
            // the ViewModel survives, and an unconsumed quote replays here and puts the caret back at
            // offset 0. The write-then-retire order guards nothing today, no line between the two
            // suspends, but it is pinned so a suspending call added later cannot empty the flow first.
            viewModel.consumeReplyQuote()
            // The body now carries the quote, and this is the only place that may say so: the flag is
            // saved, so after a kill it is what tells the fresh ViewModel to keep quiet. Never
            // posted at the top of this effect nor in the toast branch, it would then mean "the
            // effect ran", and a reply that genuinely has no quote would go out without a word.
            quoteLanded = true
        } else if (applied) {
            // The user started writing before it arrived, so it is not dropped in over their text. Say
            // so instead of silently sending a reply with no quote (B6).
            viewModel.noticeQuoteNotAdded()
            // Said exactly once, after the notice, otherwise each activity recreation posts the toast
            // again, and they stack up in LENGTH_LONG.
            viewModel.consumeReplyQuote()
        }
        // No consume on the third way out (the quote came back before the header prefill landed):
        // nothing has posted it and nothing has announced it, so retiring it there would lose it.
    }

    // Which of the three screens a reopened draft gets. Taken here because it needs `applied`: once
    // the prefill has landed, the editor is drawn whatever the fetch is doing. `applied` survives
    // process death while the ViewModel does not, so after a kill it is the only thing that knows the
    // text on screen is the reader's, see [draftReopenView].
    val reopen = draftReopenView(draftLoading, draftLoadFailed, applied)

    val sending = state is ComposeState.Sending

    // The Save-as-draft icon greys out while there is nothing worth saving, the same #69 rule the
    // save itself uses, so the button's state matches what tapping it would do. A typed recipient
    // counts, so the icon lights up as soon as an address is entered.
    val canSaveDraft = draftHasContent(to, cc, bcc, subject.text, body.text, attachments.isNotEmpty())

    // Leaving without sending or saving. A message reopened from the Outbox goes back to the queue
    // (#70), closing its editor is not deleting it, unless this composer lost its body: then the row
    // is parked instead, because queued it leaves in under a second, when the screen has just said to
    // close and reopen it. The editor's raw text is what decides, hence `body.text`.
    val cancel = {
        viewModel.abandon(body.text)
        resume.close(resumeSlot)
        onCancel()
    }

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits. A message pulled back
    // out of a send (Undo) is guarded even untouched: it lives on this screen only (#70). The verdict
    val dirty = ComposeDirty.isDirty(
        onlyCopy = onlyCopy,
        to = to, initialTo = initialTo,
        cc = cc, initialCc = initialCc,
        bcc = bcc, initialBcc = initialBcc,
        subject = subject.text, initialSubject = initialSubject,
        body = RichBody(body.text, ranges, blocks, links), initialBody = RichBody(initialBody, baselineRanges, baselineBlocks, baselineLinks),
        requestReceipt = requestReceipt, initialRequestReceipt = initialRequestReceipt,
        attachmentsTouched = attachmentsTouched,
    )
    val attemptClose = { if (dirty && !sending) viewModel.askDiscard() else cancel() }

    // Pre-send guards, chained: "forgot attachment?" then "many recipients?". Both flags are read from
    // the ViewModel, so each gate is opened and closed through it.
    val recipientCount = listOf(to, cc, bcc).sumOf { field -> field.split(',', ';').count { it.isNotBlank() } }
    // Can send only with at least one To recipient and every entered address valid.
    val allRecipients = listOf(to, cc, bcc).flatMap { recipientTokens(it) }
    // When encrypting, every recipient must have a resolvable key (false = known-missing; absent = not
    // yet checked, allowed, the send resolves keys and reports precisely).
    val keysReady = !pgpMode.encrypts || allRecipients.none { recipientKeys[it] == false }
    val canSend = recipientTokens(to).isNotEmpty() && allRecipients.all(::isValidEmail) && keysReady
    val sendNow = { viewModel.send(to, cc, bcc, subject.text, rich, requestReceipt) }
    val proceedAfterAttachment = {
        if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()
    }
    val attemptSendPastQuote = {
        if (attachments.isEmpty() && mentionsAttachment("${subject.text}\n${body.text}")) {
            viewModel.askForgotAttachment()
        } else {
            proceedAfterAttachment()
        }
    }
    val attemptSend = { askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.SEND, viewModel::askQuoteInClear, attemptSendPastQuote) }
    val saveDraftNow = { viewModel.saveDraft(to, cc, bcc, subject.text, rich, requestReceipt) }

    // Here and not in the toolbar's Box: the question about a decrypted quote, drawn at this level,
    // opens the presets once it is confirmed.
    var scheduleMenu by remember { mutableStateOf(false) }
    // The instant the preset list is drawn for, stamped when the presets open, not
    // at the ⋮, or the time spent reading the menu is added to the presets' expiry.
    // State, not a call in the composition: the wall clock is not snapshot state,
    // and re-stamping is what redraws the menu after a refused tap.
    var menuOpenedAt by remember { mutableLongStateOf(0L) }
    val openSchedulePresets = { if (viewModel.mayScheduleOrSay(to, cc, bcc)) { menuOpenedAt = System.currentTimeMillis(); scheduleMenu = true } }

    // The gesture resumed is asked past the quote: `quoteRefusal` is still this composition's.
    if (quoteInClear != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissQuoteInClear() },
            text = { Text(stringResource(R.string.compose_quote_in_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    when (viewModel.confirmQuoteInClear()) {
                        QuoteInClearGesture.SEND -> attemptSendPastQuote()
                        QuoteInClearGesture.DRAFT -> saveDraftNow()
                        QuoteInClearGesture.SCHEDULE -> openSchedulePresets()
                        null -> Unit
                    }
                }) { Text(stringResource(R.string.compose_quote_in_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissQuoteInClear() }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    if (showForgotAttachment) {
        AlertDialog(
            onDismissRequest = { viewModel.clearForgotAttachment() },
            title = { Text(stringResource(R.string.compose_forgot_attachment_title)) },
            text = { Text(stringResource(R.string.compose_forgot_attachment_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearForgotAttachment()
                    proceedAfterAttachment()
                }) { Text(stringResource(R.string.compose_forgot_attachment_send)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearForgotAttachment() }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    if (showManyRecipients) {
        AlertDialog(
            onDismissRequest = { viewModel.clearManyRecipients() },
            title = { Text(stringResource(R.string.compose_many_recipients_title)) },
            text = { Text(stringResource(R.string.compose_many_recipients_message, recipientCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearManyRecipients()
                    sendNow()
                }) { Text(stringResource(R.string.compose_many_recipients_send)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearManyRecipients() }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    // The menu's fifth entry, in two steps: a calendar, then a clock (#161). Drawn here, at the top
    // level with the other dialogs, and not inside the toolbar's Box, which sits under the reopen gate
    PickInstantDialogs(
        step = if (pendingScheduleDay) PickInstantStep.Day else if (pendingScheduleTime) PickInstantStep.Time else PickInstantStep.None,
        chosenDay = scheduleDay,
        onDayChosen = { dayUtcMidnightMillis ->
            viewModel.chooseScheduleDay(dayUtcMidnightMillis)
            viewModel.clearScheduleDay()
            viewModel.askScheduleTime()
        },
        // Lowers both flags: only one is ever raised, and the other's door is a no-op.
        onDismiss = {
            viewModel.clearScheduleDay()
            viewModel.clearScheduleTime()
        },
        onPicked = { millis, hour, minute ->
            viewModel.clearScheduleTime()
            viewModel.rememberScheduleTime(hour, minute)
            // No confirmation here: the view model opens the Undo snackbar once the
            // row is written, never before.
            viewModel.scheduleSend(to, cc, bcc, subject.text, rich, millis, requestReceipt)
        },
    )

    // The day is dropped once the clock is closed, whichever way out was taken, so the next opening
    // starts clean. Keyed on the flag rather than written into those ways out: a rotation is none of
    // them, and the day has to survive it.
    LaunchedEffect(pendingScheduleTime) { if (!pendingScheduleTime) viewModel.forgetScheduleDay() }

    BackHandler(enabled = !showDiscard) { attemptClose() }

    if (pendingDraftDelete) {
        // Same shape as the Outbox's own delete confirmation, but not its words: that message has
        // never been sent and exists nowhere else, while this draft goes to the Trash exactly as it
        // would from the list.
        AlertDialog(
            onDismissRequest = { viewModel.clearDraftDelete() },
            title = { Text(stringResource(R.string.compose_delete_draft_title)) },
            // Material's text slot is a height-bounded box with no scrolling of its own: a bare Text in
            // it is cut, without an ellipsis and without anything saying so. At font_scale 2.0 in
            // German the body stopped at "…getippt hast, wird", losing the sentence that says what was
            // typed since the composer opened is not saved (#127).
            text = {
                Text(
                    // One title, two BODIES, because the object is not the same one (#95): a draft the
                    // phone is keeping has never reached a server, so there is no Trash for it and no
                    listOfNotNull(
                        stringResource(
                            if (editingLocalDraftId != null) {
                                R.string.compose_delete_draft_body_local
                            } else {
                                R.string.compose_delete_draft_body
                            },
                        ),
                        if (deleteKeepsServerCopy) {
                            stringResource(R.string.compose_emptied_draft_server_copy_kept)
                        } else {
                            null
                        },
                    ).joinToString("\n\n"),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearDraftDelete()
                    if (editingLocalDraftId != null) {
                        // A draft the phone is keeping is deleted by the composer: there is no server
                        // row for the list's move-to-Trash to act on, and the gesture is the emptying
                        // one (#95 × #69) reached by a button. The screen closes on
                        // viewModel.localDraftDeleted, with a bare pop, never on ComposeState.Done.
                        viewModel.deleteEditingLocalDraft()
                    } else {
                        // The message is taken inside the navigation guard: a tap the guard refuses must
                        // not have consumed the draft on its way to being ignored.
                        onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }
                    }
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearDraftDelete() }) {
                    Text(stringResource(R.string.inbox_cancel))
                }
            },
        )
    }

    if (showDiscard) {
        // The leave dialog obeys the same two rules as the toolbar's Save action (#35): an encrypted
        // message may not leave a plaintext copy on the server, so the dialog says why instead of
        // offering to keep a draft.
        val mayKeepDraft = draftSaveAllowed(pgpMode) && quoteRefusal == null
        // What the dialog is allowed to claim, decided by [discardWording] (#70): a message pulled back
        // out of the Outbox is not destroyed by leaving, so it may not be announced as "Discard
        // message?", nor promised delivery, since a failed send returns to failed.
        val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft = savedDraftBehind)
        // "Discard changes" wherever a copy of the message survives leaving, the outbox row, and the
        // draft still in Drafts. The draft variant says "changes" in its title, body and button alike.
        val discardLabel = stringResource(
            if (wording.keepsMessage) {
                R.string.compose_discard_changes
            } else {
                R.string.compose_discard_discard
            },
        )
        AlertDialog(
            onDismissRequest = { viewModel.clearDiscard() },
            title = {
                Text(
                    stringResource(
                        when {
                            wording.fromOutbox -> R.string.compose_discard_title_outbox
                            // A draft already saved on the server is not destroyed by leaving: cancel()
                            // then abandon() is a no-op outside the outbox. "Discard message?" was
                            // literally false, while the body beside it said "changes" (#35, #127).
                            wording == DiscardWording.DRAFT ||
                                wording == DiscardWording.ENCRYPTED_DRAFT ->
                                R.string.compose_discard_title_changes
                            else -> R.string.compose_discard_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        quoteRefusal ?: when (wording) {
                            // Two outbox wordings, because the body has to be true of both buttons
                            // beside it: "Save draft" takes the message out of the outbox (#70).
                            DiscardWording.OUTBOX -> R.string.compose_discard_message_outbox
                            DiscardWording.OUTBOX_ENCRYPTED ->
                                R.string.compose_discard_message_outbox_encrypted
                            DiscardWording.ENCRYPTED -> R.string.compose_discard_message_encrypted
                            // Encrypting a draft already on the server: it still owes the user the
                            // reason the Save button is gone, but may not end on "leaving discards the
                            // message", the copy in Drafts survives (#35).
                            DiscardWording.ENCRYPTED_DRAFT ->
                                R.string.compose_discard_message_encrypted_draft
                            // "You haven't saved your changes" is true of both, and of both
                            // buttons beside it; only the title has to tell them apart.
                            DiscardWording.DRAFT,
                            DiscardWording.PLAIN,
                            -> R.string.compose_discard_message
                        },
                    ),
                )
            },
            // AlertDialog has two button slots and this exit needs three answers, so all of them go in
            // the confirm slot as a FlowRow: wrapped where the labels do not fit, never truncated. Same
            confirmButton = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    discardChoices(mayKeepDraft, canSaveDraft).forEach { (choice, enabled) ->
                        when (choice) {
                            // Back to the composer, text intact, still encrypted if it was. This is
                            // what tapping outside the dialog has always done; it now has a button.
                            DiscardChoice.CANCEL -> TextButton(onClick = { viewModel.clearDiscard() }, enabled = enabled) {
                                Text(stringResource(R.string.compose_discard_cancel))
                            }
                            DiscardChoice.DISCARD -> TextButton(onClick = {
                                viewModel.clearDiscard()
                                // Discards the edits, not the queued message: that one goes back
                                // (#70), and a saved draft stays in Drafts (#35, #127).
                                cancel()
                            }, enabled = enabled) { Text(discardLabel, color = MaterialTheme.colorScheme.error) }
                            DiscardChoice.SAVE_DRAFT -> TextButton(onClick = {
                                viewModel.clearDiscard()
                                viewModel.saveDraft(to, cc, bcc, subject.text, rich, requestReceipt)
                            }, enabled = enabled) { Text(stringResource(R.string.compose_discard_save)) }
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = {
            // Above the keyboard. What shows here is the security verdict of the lock toggle, and the
            // keyboard is up at exactly the moment it is tapped, so left at the window's bottom edge
            // the warning appeared under the keyboard and was never seen (#35). Same inset the writing
            // area uses: whichever of the keyboard or the navigation bar is taller, never both.
            SnackbarHost(
                snackbarHostState,
                Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        },
        // Don't reserve a bottom nav-bar inset here: the body already pads with max(ime, nav bar), and
        // consuming it twice left a nav-bar-tall composer-coloured strip between the keyboard and the
        // body text (#26).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // The rule itself is [composeTitle], out of here so it can be tested (#96);
                    // this only turns its answer into words.
                    Text(
                        stringResource(
                            when (composeTitle(draftId, mode, replyTo, restore, editingOutbox)) {
                                ComposeTitle.DRAFT -> R.string.draft_label
                                ComposeTitle.FORWARD -> R.string.message_forward
                                ComposeTitle.REPLY -> R.string.compose_title_reply
                                ComposeTitle.OUTBOX_EDIT -> R.string.outbox_edit
                                ComposeTitle.NEW -> R.string.compose_title_new
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = attemptClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_discard))
                    }
                },
                actions = {
                  // Not one button while a reopened draft is still being read, or once it turned out it
                  // cannot be. Whole block, not each button disabled one by one: every one acts on
                  // fields that hold nothing. The X in navigationIcon deliberately stays.
                  if (reopen is DraftReopenView.Editor) {
                    // The lock. Short tap walks [nextLockMode]; long press opens [lockMenuModes], the
                    // only way to reach ENCRYPT_UNSIGNED, a short tap must not walk anyone into
                    // sending unsigned mail. The gesture is announced in the button's own
                    // contentDescription.
                    if (pgpAvailable || smimeAvailable.isNotEmpty()) {
                        var pgpMenu by remember { mutableStateOf(false) }
                        // Box, not IconButton: an IconButton has no long press. The 48 dp size and the
                        // circular clip are what it was giving for free. `enabled = !sending` sits on
                        // the clickable, so neither gesture acts while a send is in flight.
                        Box {
                            Icon(
                                imageVector = pgpModeIcon(pgpMode),
                                contentDescription = stringResource(
                                    R.string.compose_pgp_toggle_desc,
                                    stringResource(pgpModeLabel(pgpMode)),
                                ),
                                // The alpha is what IconButton gave for free. Neither gesture acts
                                // while a send is in flight, and a padlock at full strength would say
                                // the opposite (WYSIWYG).
                                tint = (
                                    if (pgpMode == PgpMode.OFF) {
                                        LocalContentColor.current
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                    ).copy(alpha = if (sending) 0.38f else 1f),
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = !sending,
                                        role = Role.Button,
                                        onClick = viewModel::cyclePgpMode,
                                        onLongClick = { pgpMenu = true },
                                    )
                                    .padding(12.dp),
                            )
                            DropdownMenu(expanded = pgpMenu, onDismissRequest = { pgpMenu = false }) {
                                lockMenuModes(openPgp = pgpAvailable, smime = smimeAvailable).forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(pgpModeLabel(entry))) },
                                        modifier = Modifier.semantics { selected = entry == pgpMode },
                                        leadingIcon = {
                                            Icon(pgpModeIcon(entry), contentDescription = null)
                                        },
                                        // The current mode is ticked; the others carry nothing, so
                                        // what is on screen says what is in force (WYSIWYG).
                                        trailingIcon = {
                                            if (entry == pgpMode) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            pgpMenu = false
                                            // Same ViewModel path the cycle takes, so a mode picked
                                            // here is user-set (never auto-downgraded) and says so
                                            // in the same snackbar.
                                            viewModel.setPgpMode(entry)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Attachments are allowed in every mode (they ride inside the encrypted entity).
                    Box {
                        var attachMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { attachMenu = true }, enabled = !sending) {
                            Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                        }
                        DropdownMenu(
                            expanded = attachMenu,
                            onDismissRequest = { attachMenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_attach_choose_file)) },
                                onClick = { attachMenu = false; picker.launch("*/*") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_attach_take_photo)) },
                                onClick = { attachMenu = false; takePhoto() },
                            )
                        }
                    }
                    IconButton(
                        onClick = attemptSend,
                        enabled = !sending && canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.compose_send))
                    }
                    // Four slots at most, and the title gets the rest (#164). This bar carried up to
                    // eight 48 dp slots and its title was clipped mid-word on a narrow screen. Save,
                    // overflow) and there was nothing left for its own title: on a narrow screen at
                    // a large font scale it was clipped in the middle of a word. Save, "send later"
                    Box {
                        var moreMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { moreMenu = true }, enabled = !sending) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                        }
                        DropdownMenu(
                            expanded = moreMenu,
                            onDismissRequest = { moreMenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            // Encrypting can't carry plaintext to the server: no draft. Same rule,
                            // same function as the leave dialog above (#35).
                            if (draftSaveAllowed(pgpMode)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_save_draft)) },
                                    onClick = { moreMenu = false; askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.DRAFT, viewModel::askQuoteInClear, saveDraftNow) },
                                    enabled = !sending && canSaveDraft,
                                )
                            }
                            // Lit in every mode, so a refused tap can say why (A3). The tap closes this
                            // menu and opens the presets beside it, no submenu nested inside an overflow.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_schedule_send)) },
                                onClick = { moreMenu = false; if (!viewModel.scheduleRefusedOrSay(to, cc, bcc)) askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.SCHEDULE, viewModel::askQuoteInClear, openSchedulePresets) },
                                enabled = !sending,
                            )
                            // Deleting the draft (#127). Outside the draftSaveAllowed block: a delete
                            // persists nothing, so it has no reason to disappear when the padlock
                            // closes. When it is offered is [draftDeleteOffered].
                            if (draftDeleteOffered(restore, draftId, editingDraft != null, editingLocalDraftId != null)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_delete_draft)) },
                                    // Asks first (#127): everything typed since this composer opened is
                                    // unsaved, and on a server draft the delete takes the server copy,
                                    // so the Undo restores the draft as it was there, never the text on
                                    // screen. On a draft the phone is keeping there is no Undo at all.
                                    onClick = { moreMenu = false; viewModel.askDraftDelete() },
                                    enabled = !sending,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_request_receipt)) },
                                modifier = Modifier.semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(requestReceipt)
                                },
                                trailingIcon = { Checkbox(checked = requestReceipt, onCheckedChange = null) },
                                onClick = { moreMenu = false; requestReceipt = !requestReceipt },
                            )
                            if (hasQuotedText(body.text)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_remove_quote)) },
                                    onClick = {
                                        moreMenu = false
                                        // `initialBody` and the baselines stay where they are: a removed
                                        // quote is an edit, so the composer must count as modified and
                                        // ask before it is left, unlike the sender change which recalibrates
                                        // them on purpose. The decrypted-quote guard is not lowered either.
                                        rewriteRichBody(RichBody(body.text, ranges, blocks, links), body.selection.start, ::removeQuotedText)?.let { rewritten ->
                                            body = TextFieldValue(
                                                rewritten.text,
                                                TextRange(body.selection.start.coerceAtMost(rewritten.text.length)),
                                            )
                                            ranges = rewritten.ranges
                                            blocks = rewritten.blocks
                                            links = rewritten.links
                                        }
                                    },
                                    enabled = !sending,
                                )
                            }
                        }
                        // The presets, anchored to this same Box as the overflow: the two menus are
                        // siblings, never one inside the other.
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, shape = MaterialTheme.shapes.medium) {
                            // All three fields travel to the tap: the preset says which kind of promise
                            // the entry made, and `drawnAt` is the instant it was drawn for, the answer
                            // itself for the absolute entries.
                            schedulePresets(context, menuOpenedAt, lastScheduleMinutes).forEach { (preset, label, drawnAt) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        // Decided again at the tap, against a fresh clock: the menu can
                                        // stand open for minutes, and an entry drawn at 5:55 PM still
                                        val sendAt = presetMillisAtTap(preset, drawnAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                                        // Closing and scheduling happen together or not at
                                        // all. On the refused path the menu stays up and is redrawn from
                                        // a new stamp: the lapsed entry vanishes under the finger, and
                                        // that is the message.
                                        if (sendAt != null) {
                                            scheduleMenu = false
                                            viewModel.scheduleSend(to, cc, bcc, subject.text, rich, sendAt, requestReceipt)
                                        } else {
                                            menuOpenedAt = System.currentTimeMillis()
                                        }
                                    },
                                )
                            }
                            // Last, under the presets, as Gmail does it: choose the instant yourself
                            // (#161). Nothing is decided here, the two dialogs hold the refusal.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schedule_pick_date_time)) },
                                onClick = {
                                    scheduleMenu = false
                                    viewModel.askScheduleDay()
                                },
                            )
                        }
                    }
                  }
                },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        // The editor is drawn or it is not. Every rememberSaveable above (`applied` first of all),
        // and both LaunchedEffects that apply the prefill, stay outside this `when`: the success path
        // must land exactly as it does today. Only the drawing moves.
        when (reopen) {
          // Still reading the draft. No sentence: a spinner already says "wait", and a line of text
          // under it would be read as a verdict on a fetch that has not answered.
          DraftReopenView.Waiting -> LoadingRing(Modifier.align(Alignment.Center))
          // Read and failed. One sentence and the X, nothing else: there is no draft to edit, and an
          // editor here would invite a save that adds a second draft beside the first.
          is DraftReopenView.DeadEnd -> Text(
              text = stringResource(reopen.notice),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              // No maxLines: German and Russian run to three or four lines here, and clipping would
              // leave the only explanation on the screen half-said.
              modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
          )
          DraftReopenView.Editor ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The body fills the space above the keyboard and scrolls its own content, so the cursor
                // stays in view (#26); it keeps three lines, the header scrolling past that (`yieldToBody`).
                // Pad the bottom by whichever is taller, the keyboard or the nav bar, with no double inset.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .graphicsLayer {
                    translationY = -fly * 64.dp.toPx()
                    alpha = 1f - fly
                },
        ) {
          val bodyMinHeight = composeBodyMinHeight(LocalTextStyle.current.lineHeight, LocalDensity.current)
          // The header sits on a faint tint so the writing area below reads as a distinct zone; its
          // dividers run full width.
          Column(
              Modifier
                  .yieldToBody(bodyMinHeight, barShown = bodyFocused, blockBelow = forwardedBlockReserve(forwardedPreview, noteFocused = bodyFocused))
                  .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                  .verticalScroll(rememberScrollState()),
          ) {
            // From, switch sending account / identity (only when there's a choice).
            if (fromOptions.size > 1) {
                var fromMenu by remember { mutableStateOf(false) }
                var fromVisibleFrameHeightPx by remember { mutableStateOf(0) }
                var fromAnchorBottomPx by remember { mutableStateOf(0) }
                // Measured from the composer's view and the anchor: inside the menu's content they
                // would measure the popup's own window.
                val fromView = LocalView.current
                val fromFrame = remember { Rect() }
                val fromMenuCap = fromMenuMaxHeight(
                    visibleFrameHeightPx = fromVisibleFrameHeightPx,
                    anchorBottomPx = fromAnchorBottomPx,
                    density = LocalDensity.current,
                )
                Box(
                    Modifier.onGloballyPositioned {
                        fromAnchorBottomPx = (it.positionInWindow().y + it.size.height).roundToInt()
                        fromView.getWindowVisibleDisplayFrame(fromFrame)
                        fromVisibleFrameHeightPx = fromFrame.height()
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fromMenu = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(stringResource(R.string.compose_from))
                        Text(
                            text = selectedFrom?.identity?.display() ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // An IconButton (not a bare Icon) so this chevron lines up with the To field's
                        // expand chevron, which is also an IconButton.
                        IconButton(onClick = { fromMenu = true }) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.compose_choose_sender))
                        }
                    }
                    DropdownMenu(
                        expanded = fromMenu,
                        onDismissRequest = { fromMenu = false },
                        modifier = Modifier.heightIn(max = fromMenuCap),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        fromOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.identity.display()) },
                                onClick = {
                                    // Changing "From" makes the signature follow the identity (D5): the
                                    // block is swapped while still there verbatim, an edited or deleted
                                    scope.launch {
                                        // The quoted original this compose opened with: the reply
                                        // baseline, and nothing for a new mail or a forward.
                                        val quoted = if (isReplyBody) initialBody else ""
                                        val rewrite: (String) -> String? =
                                            when (val change = viewModel.selectFrom(option, isReplyOrForward)) {
                                                null -> return@launch
                                                is SignatureChange.Swap -> { text ->
                                                    replaceSignatureBlock(
                                                        text, change.from, change.to, change.delimiter,
                                                    )
                                                }
                                                is SignatureChange.Insert -> { text ->
                                                    insertSignatureBlock(
                                                        text, change.signature, quoted, change.belowQuote,
                                                        change.delimiter,
                                                    )
                                                }
                                            }
                                        // The rewrite works on the text; the user's styling is carried
                                        // across it by diffing (#131), so a bold word above the
                                        // signature stays bold and stays put.
                                        rewriteRichBody(RichBody(body.text, ranges, blocks, links), body.selection.start, rewrite)?.let { rewritten ->
                                            body = TextFieldValue(
                                                rewritten.text,
                                                TextRange(body.selection.start.coerceAtMost(rewritten.text.length)),
                                            )
                                            ranges = rewritten.ranges
                                            blocks = rewritten.blocks
                                            links = rewritten.links
                                            rewriteRichBody(RichBody(initialBody, baselineRanges, baselineBlocks, baselineLinks), 0, rewrite)?.let {
                                                initialBody = it.text
                                                baselineRanges = it.ranges
                                                baselineBlocks = it.blocks
                                                baselineLinks = it.links
                                            }
                                        }
                                    }
                                    fromMenu = false
                                },
                            )
                        }
                    }
                }
                FieldDivider()
            }

            // Keep per-recipient key availability current while encrypting.
            LaunchedEffect(pgpMode, to, cc, bcc) {
                viewModel.updateRecipientKeys(to, cc, bcc)
            }
            val missingKeyFor: (String) -> Boolean = { addr ->
                pgpMode.encrypts && recipientKeys[addr] == false
            }
            val onScreen = { recipientsOnScreen(to, cc, bcc) }
            val suggestRecipients: (String) -> Unit = { viewModel.suggest(it, onScreen()) }
            val recipientFieldFocused: (String) -> Unit = { viewModel.recipientFieldFocused(it, onScreen()) }
            val namePicked: (ContactSuggestion) -> Unit = { recipientNames = recipientNamesWithPick(recipientNames, it) }
            RecipientChipsField(
                label = stringResource(R.string.compose_to),
                value = to,
                onValueChange = { to = it },
                suggestions = suggestions,
                onSuggest = suggestRecipients,
                onFocused = recipientFieldFocused,
                onClearSuggestions = viewModel::clearSuggestions,
                focusRequester = toFocus,
                // Whenever the recipients still have to be typed: a fresh mail and a forward. A reply
                // and a reopened draft already have them and focus the body (#63); a mailto: link
                // focuses the first field it left empty (#83).
                autoFocus = initialFocus == ComposeFocus.RECIPIENTS,
                missingKey = missingKeyFor,
                names = recipientNames,
                onNamePicked = namePicked,
                trailing = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc,
                            ),
                        )
                    }
                },
            )
            if (expanded) {
                RecipientChipsField(
                    stringResource(R.string.compose_cc), cc, { cc = it }, suggestions,
                    suggestRecipients, recipientFieldFocused, viewModel::clearSuggestions, missingKey = missingKeyFor,
                    names = recipientNames, onNamePicked = namePicked,
                )
                RecipientChipsField(
                    stringResource(R.string.compose_bcc), bcc, { bcc = it }, suggestions,
                    suggestRecipients, recipientFieldFocused, viewModel::clearSuggestions, missingKey = missingKeyFor,
                    names = recipientNames, onNamePicked = namePicked,
                )
            }
            // Subject has no fixed label: the localized string is the in-field placeholder, so the
            // subject starts further left than the labelled recipient rows (K-9 style).
            ComposeField(
                subject,
                { subject = it },
                placeholder = stringResource(R.string.compose_subject),
                focusRequester = subjectFocus,
            )
            // Only when the body is actually being encrypted to someone, so a fresh encrypt-by-default
            // compose with no recipients doesn't claim it yet (#35).
            if (pgpMode.encrypts && recipientKeys.values.any { it }) {
                Text(
                    stringResource(R.string.compose_pgp_subject_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Encrypt-by-default couldn't encrypt because these recipients have no key: say so and name
            // them (#35).
            if (pgpKeylessRecipients.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.compose_pgp_not_encrypted_no_key,
                        pgpKeylessRecipients.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            quoteRefusal?.let { refusal ->
                Text(
                    stringResource(refusal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (attachments.isNotEmpty()) {
                // Same as the recipient chips: cap the attachment list and scroll it so many files
                // don't grow the header without limit (#26).
                Column(
                    Modifier
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = stringResource(R.string.status_open_attachment),
                                    role = Role.Button,
                                ) { viewModel.openAttachment(att) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val look = attachmentLook(att)
                            // One slot for both, so names stay aligned whether a row shows a preview
                            // or an icon; at 40 dp it stays under the remove button's 48 dp, and the
                            // row keeps its height under the list's cap.
                            Box(
                                Modifier.padding(end = 8.dp).size(THUMBNAIL_DP.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val thumbnail = thumbnails[attachmentKey(att)]
                                if (thumbnail != null) {
                                    Image(
                                        thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                                    )
                                } else {
                                    Icon(look.icon, contentDescription = null)
                                }
                            }
                            // weight(1f) and one ellipsized line: a long name shortens instead of
                            // pushing the cross off the screen.
                            val attachmentName = att.name ?: stringResource(R.string.compose_attachment_fallback)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = attachmentName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = attachmentMeta(look.label, att.size)
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.removeAttachment(att) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.a11y_remove_named, attachmentName),
                                )
                            }
                        }
                    }
                }
                attachmentsTotal?.let { total ->
                    Text(
                        text = if (total.limit != null) {
                            stringResource(R.string.compose_attachments_total_of, formatSize(total.sum), formatSize(total.limit))
                        } else {
                            stringResource(R.string.compose_attachments_total, formatSize(total.sum))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (total.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            attachmentStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Above the body so they stay visible while it fills the rest of the screen.
            if (sending) LoadingRing(Modifier.padding(horizontal = 16.dp))
            (state as? ComposeState.Error)?.let {
                Text(
                    text = stringResource(composeErrorWording(it), it.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
          }

            // The value handed to the field is flat, and it is `body` itself: plain text, its
            // own selection, its own composition. No span goes in there: Compose's editing buffer
            // cannot hold one (`EditingBuffer.toAnnotatedString()` is `AnnotatedString(toString())`)
            // and a span would restart the input on every keystroke. Styling is drawn one layer
            // down, by `bodyTransformation`.
            BasicTextField(
                value = body,
                onValueChange = { next ->
                    // Read here, not `rich`: that one is captured at composition, and two callbacks
                    // before a recomposition would remap from a stale body.
                    val current = RichBody(body.text, ranges, blocks, links)
                    if (next.text != body.text) {
                        // One edit, whatever produced it: the styling follows the text by diffing, and
                        // so do the lists and the links, one remap, all three parts of its answer
                        // taken. A second call would diff the same edit twice.
                        val remapped = remapAfterEdit(current, next.text, span(body.selection), span(next.selection), pending)
                        ranges = remapped.ranges
                        blocks = remapped.blocks
                        links = remapped.links
                    } else if (next.selection != body.selection) {
                        // The caret moved: a style armed for the next keystroke no longer applies.
                        pending = null
                    }
                    body = TextFieldValue(next.text, next.selection, next.composition)
                },
                // The list markers and the inline styling are drawn here and nowhere else (#131): in
                // the text layer, by the one transformation a field has, so not one marker character
                visualTransformation = bodyTransformation(rich, MaterialTheme.colorScheme.primary),
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Capitalisation only: no imeAction here on purpose, because the body is the only
                // multi-line field of the screen and an action would take the newline's place (#26).
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    // Fills all the space below the header (so the whole area under it is the body's tap
                    // target) with a bounded height, so the field scrolls its own content (#26).
                    .weight(1f)
                    .focusRequester(bodyFocus)
                    .onFocusChanged { bodyFocused = it.isFocused },
                decorationBox = { inner ->
                    // The margins live inside the field, not around it (#26): padding from the outside
                    // kept the margin out of the field's touch area, so a tap before the first character
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = BODY_PADDING_TOP, bottom = BODY_PADDING_BOTTOM),
                    ) {
                        // …and no list either: a bullet tapped on an empty composer is drawn at the
                        // start of the first line, exactly where the placeholder sits, and the two
                        // overprint each other until the first keystroke.
                        if (body.text.isEmpty() && blocks.isEmpty()) {
                            Text(
                                stringResource(R.string.compose_body_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            ForwardedOriginalBlock(
                preview = forwardedPreview,
                collapsed = bodyFocused,
            )
            // The formatting bar, only while the body has the focus (#131). No `imePadding()` and no
            // inset of its own: the Column above already pads for the keyboard or the nav bar, and a
            // second inset here would double it (#26).
            if (bodyFocused) {
                FormattingBar(
                    active = pending ?: stylesAt(rich, span(body.selection)),
                    activeList = listKindAt(rich, span(body.selection)),
                    // Lit when the caret is in a link, the same answer the dialog opens on, so "the
                    // button is on" and "the dialog offers to edit it" cannot disagree.
                    linkActive = linkAt(rich, span(body.selection)) != null,
                    onLink = { linkDialog = true },
                    onToggle = { kind ->
                        val current = RichBody(body.text, ranges, blocks, links)
                        val sel = span(body.selection)
                        if (!sel.isEmpty) {
                            ranges = toggleFormatting(current, sel, kind).ranges
                        } else {
                            pending = (pending ?: stylesAt(current, sel)).let { if (kind in it) it - kind else it + kind }
                        }
                    },
                    onToggleList = { kind ->
                        // No `pending` and no empty-selection branch, unlike the four families above:
                        // a block is a run of lines, so the caret alone already names one, and the tap
                        // applies at once, the gesture in Gmail.
                        val current = RichBody(body.text, ranges, blocks, links)
                        blocks = toggleBlock(current, span(body.selection), kind).blocks
                    },
                    onClear = {
                        val current = RichBody(body.text, ranges, blocks, links)
                        val sel = span(body.selection)
                        if (!sel.isEmpty) ranges = clearFormatting(current, sel).ranges else pending = emptySet()
                    },
                )
            }
            if (linkDialog) {
                // The body and the caret go back into the composer's own state: `setLink` can INSERT
                // the label, so the text changes as it does under a keystroke, and the caret must land
                // after what was inserted or the next letter lands inside the link.
                LinkDialog(
                    body = rich,
                    selection = span(body.selection),
                    onApply = { applied ->
                        val inserted = applied.text.length - rich.text.length
                        body = TextFieldValue(applied.text, TextRange(body.selection.max + inserted))
                        ranges = applied.ranges
                        blocks = applied.blocks
                        links = applied.links
                        linkDialog = false
                    },
                    onRemove = { applied ->
                        links = applied.links
                        linkDialog = false
                    },
                    onDismiss = { linkDialog = false },
                )
            }
        }
        }
        // The tern that carries the message off-screen, top-right.
        if (flying) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .graphicsLayer {
                        translationX = fly * size.width * 3f
                        translationY = -fly * size.height * 6f
                        alpha = 1f - fly
                        rotationZ = fly * 16f
                    },
            ) { drawTern(spread = 1f, flap = 0.5f, color = ink) }
        }
      }
    }
}

/**
 * A frameless, full-width input with an in-field placeholder (no fixed leading label) and an
 * underline. The placeholder shows only while empty and disappears on the first character, so the
 * subject text starts flush-left, further left than the labelled recipient rows (K-9 style).
 */
@Composable
private fun ComposeField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Tapping anywhere on the row (not just the thin input) focuses the field, so the whole line is
    // the tap target (#26).
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    // Before the first character the caret goes to the very start; anywhere else the
                    // field keeps the caret it had (#26).
                    geometry.caretFor(value.text.length)?.let { caret ->
                        onValueChange(value.copy(selection = TextRange(caret)))
                    }
                    focus.requestFocus()
                }
                // At least a 48dp tap target even when the field is empty (accessibility).
                .heightIn(min = 48.dp)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = 16.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // The subject is a sentence, so the keyboard arms its first capital (#184).
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .headerTapText(geometry)
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            trailing?.invoke()
        }
        FieldDivider()
    }
}

/** Above this many recipients (To + Cc + Bcc), sending asks for confirmation. */
private const val MANY_RECIPIENTS = 5

/**
 * A recipient line showing committed addresses as chips (invalid ones in the error colour) plus an
 * inline editor with autocomplete, backed by a single comma-joined [value] string.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientChipsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactSuggestion>,
    onSuggest: (String) -> Unit,
    /** The input took the focus, holding the text still being typed. */
    onFocused: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** Open and focus the field on first composition (the To field on a fresh compose). */
    autoFocus: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** When encrypting, flags a recipient with no available public key. */
    missingKey: (String) -> Boolean = { false },
    /** The composer's name book, keyed by `recipientNameKey`. */
    names: Map<String, String>,
    /** A contact was picked from the menu, before its address is added. */
    onNamePicked: (ContactSuggestion) -> Unit,
) {
    val (chips, input) = splitRecipients(value)

    fun rebuild(newChips: List<String>, newInput: String) = joinRecipients(newChips, newInput)

    // Expanded shows the editable chip area with the input; collapsed shows chips only (or a one-line
    // "+N" summary), so tapping another field leaves no unused space. Start expanded only for a fresh
    // empty field so it can auto-focus (#26).
    var expanded by remember { mutableStateOf(false) }
    // Guards against the input's initial onFocusChanged(false) collapsing the field before the focus
    // request lands: only a loss after a real gain collapses it.
    var wasFocused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    // The inline input carries its caret, so a tap before its first character can put the cursor at the
    // start (#26). The parent string stays the single source of truth: as soon as the derived [input]
    // differs, the field is rebuilt around it with the caret at the end.
    var inputState by remember { mutableStateOf(TextFieldValue()) }
    // The field's on-screen width, so the suggestion menu can float directly under it at the same
    // width, instead of pushing the subject and body down as an inline list did.
    var fieldWidthPx by remember { mutableStateOf(0) }
    // …and its bottom edge inside the compose root, plus the root's own height, so the menu can be
    // capped at the room left below the field once the keyboard is up (#143).
    var fieldBottomPx by remember { mutableStateOf(0) }
    var windowHeightPx by remember { mutableStateOf(0) }
    val inputValue = if (inputState.text == input) {
        inputState
    } else {
        TextFieldValue(input, TextRange(input.length))
    }
    // Enter validates the address being typed, exactly as a comma does, and the field keeps the focus
    // so the next recipient can follow (#83). An empty input is left alone: Enter only ever commits
    // what is in front of it.
    fun commitInput() {
        val token = inputValue.text.trim()
        if (token.isEmpty()) return
        inputState = TextFieldValue()
        onValueChange(rebuild(chips + token, ""))
        onClearSuggestions()
    }

    val chipScroll = rememberScrollState()
    // Collapse to a one-line summary past what fits without scrolling (about two per line).
    val collapsed = !expanded && chips.size > 2
    // Open on first composition when asked (the To field on a fresh compose), so it self-focuses.
    LaunchedEffect(Unit) { if (autoFocus) expanded = true }
    // The input isn't composed while collapsed, so focus it once the field expands.
    LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }
    // Follow the growing chip area so the blinking input stays in view. Instantly, not animated (#94):
    // sliding the chips into place is the one moving part of this field, it plays whenever an address
    // is committed, and it shows nothing the jump doesn't.
    LaunchedEffect(chipScroll.maxValue) { if (expanded) chipScroll.scrollTo(chipScroll.maxValue) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    expanded = true
                    // An already-expanded field doesn't re-run the effect below, so ask here too,
                    // or a tap on the label does nothing at all (#26).
                    runCatching { focus.requestFocus() }
                    // On the label, or in the empty space left of what is being typed: caret to the
                    // start of that text. Committed chips aren't text, and an empty input has no start
                    // to aim at, so both leave the caret alone.
                    geometry.caretFor(inputValue.text.length)?.let { caret ->
                        inputState = inputValue.copy(selection = TextRange(caret))
                    }
                }
                // At least a 48dp tap target even when the field is empty/collapsed (accessibility).
                .heightIn(min = 48.dp)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = 16.dp)
                .onGloballyPositioned {
                    fieldWidthPx = it.size.width
                    fieldBottomPx = (it.positionInRoot().y + it.size.height).roundToInt()
                    windowHeightPx = it.findRootCoordinates().size.height
                },
        ) {
            FieldLabel(label)
            if (collapsed) {
                // One-line summary: the first recipient plus a count of the rest. Tapping expands.
                Row(
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chipLabel(chips.first(), names),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chips.size > 1) {
                        Text(
                            "+${chips.size - 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            } else {
              FlowRow(
                // Cap the chip area (~2.5 lines) and scroll it, so adding many recipients doesn't grow
                // the field without limit and eat the message body (#26).
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 104.dp)
                    .verticalScroll(chipScroll)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                chips.forEachIndexed { index, chip ->
                    // Invalid address or (while encrypting) no key for it: flagged.
                    val fault = recipientChipFault(chip, missingKey)
                    val faultSpoken = if (fault == null) null else stringResource(fault.message)
                    val shown = chipLabel(chip, names)
                    val spoken = chipDescription(chip, names)
                    InputChip(
                        selected = false,
                        // A tap hands the address back as plain text (#94): it leaves the chip row for
                        // the input, where the field opens it with the caret at its end, and a second
                        // tap puts the caret exactly where the typo is. Two taps, no double-tap timing
                        // to guess at. Whatever was half-typed is committed rather than lost.
                        onClick = {
                            expanded = true
                            inputState = TextFieldValue()
                            onValueChange(recipientsWithChipEdited(value, index))
                            onClearSuggestions()
                            runCatching { focus.requestFocus() }
                        },
                        // `FlowRow` gives a chip the whole line, so only a chip wider than the line gets a
                        // label narrower than its text; soft-wrapped, `maxLines = 1` would keep the first
                        // word and drop the rest with no mark.
                        label = { Text(shown, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.semantics {
                            if (spoken != null) contentDescription = spoken
                            if (faultSpoken != null) error(faultSpoken)
                        },
                        // The cross is only 16dp; the box around it is the tap target, so a tap that
                        // misses the glyph by a few dp removes the chip instead of opening it for editing.
                        // It stays the chip's height, or the chip itself would grow to 48dp.
                        trailingIcon = {
                            val removeLabel = stringResource(R.string.compose_remove_recipient, shown)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .widthIn(min = 48.dp)
                                    .height(InputChipDefaults.Height)
                                    .clickable(role = Role.Button) { onValueChange(rebuild(chips.filterIndexed { i, _ -> i != index }, input)) }
                                    .semantics { contentDescription = removeLabel },
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        },
                        colors = if (fault == null) {
                            InputChipDefaults.inputChipColors()
                        } else {
                            InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        // No elevation, and therefore none of Material's interaction-driven elevation
                        // animation (#94). An input chip's elevations are all 0dp anyway, so this
                        // changes nothing on screen, it stops an animated shadow being computed.
                        elevation = null,
                    )
                }
                // The input exists only while expanded, so an unfocused field shows no empty input line
                // (and no stray blinking cursor), it collapses to its chips.
                if (expanded) {
                  BasicTextField(
                    value = inputValue,
                    onValueChange = { typed ->
                        val raw = typed.text
                        val last = raw.lastOrNull()
                        if (last == ',' || last == ';' || last == ' ' || last == '\n') {
                            val token = raw.dropLast(1).trim()
                            inputState = TextFieldValue()
                            onValueChange(rebuild(if (token.isNotEmpty()) chips + token else chips, ""))
                            onClearSuggestions()
                        } else {
                            inputState = typed
                            onValueChange(rebuild(chips, raw))
                            onSuggest(raw)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // Enter now commits the address instead of merely closing the keyboard (#83). The
                    // action is named here rather than left to `singleLine`'s implicit one, because a
                    // hardware Enter is routed to THIS action and an unnamed one does nothing at all.
                    // Not calling the default action is what keeps the keyboard up.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier = Modifier
                        .headerTapText(geometry)
                        // Fill the rest of the line so a tap in the empty area past the last chip lands
                        // the caret at the end; wrap to a new line once space runs short.
                        .weight(1f)
                        .widthIn(min = 90.dp)
                        .padding(vertical = 6.dp)
                        .onFocusChanged { fs ->
                            if (fs.isFocused) {
                                if (!wasFocused) onFocused(input)
                                wasFocused = true
                            } else if (wasFocused) {
                                // Commit what was typed but not yet turned into a chip, so it stays
                                // visible when the field collapses and its input is hidden (#26).
                                val pending = input.trim()
                                if (pending.isNotEmpty()) onValueChange(rebuild(chips + pending, ""))
                                expanded = false
                                wasFocused = false
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                                input.isEmpty() && chips.isNotEmpty()
                            ) {
                                onValueChange(rebuild(chips.dropLast(1), ""))
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(focus),
                  )
                }
              }
            }
            trailing?.invoke()
        }
        FieldDivider()
        RecipientSuggestionMenu(
            suggestions = suggestions,
            visible = expanded,
            fieldWidthPx = fieldWidthPx,
            fieldBottomPx = fieldBottomPx,
            windowHeightPx = windowHeightPx,
            onPick = { contact ->
                onNamePicked(contact)
                onValueChange(rebuild(chips + contact.email, ""))
                onClearSuggestions()
            },
            onDismiss = onClearSuggestions,
        )
    }
}

/** Where a header row was last pressed and where its editable text starts, the two things needed to
 *  tell a tap on the label (or on the space before the first character) from a tap on the text itself.
 *  Read by the row's click handler, never during composition: plain fields, no snapshot state. */
private class HeaderTapGeometry {
    /** Last press, in the row's coordinates. */
    var pressX: Float = Float.NaN

    /** Row and editable-text origins in root coordinates; their difference is row-local. */
    var rowX: Float = Float.NaN
    var textX: Float = Float.NaN

    /** The caret a tap should force in a field holding [textLength] characters, or null for none. */
    fun caretFor(textLength: Int): Int? = headerTapCaret(pressX, textX - rowX, textLength)
}

/** The whole header row is the field's tap target (#26). Watches the pointer on the way down without
 *  consuming it, so a tap on the text is still handled by the field exactly as before, and records
 *  where it landed. Still a plain `clickable`, so what TalkBack sees is unchanged. */
@Composable
private fun Modifier.headerTapRow(geometry: HeaderTapGeometry, onTap: () -> Unit): Modifier = this
    .onGloballyPositioned { geometry.rowX = it.positionInRoot().x }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                    if (change.pressed && !change.previousPressed) geometry.pressX = change.position.x
                }
            }
        }
    }
    .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onTap() }

/** Marks the editable text of a header row, so [headerTapRow] can tell a tap before it apart. */
private fun Modifier.headerTapText(geometry: HeaderTapGeometry): Modifier =
    onGloballyPositioned { geometry.textX = it.positionInRoot().x }

@Composable
private fun FieldLabel(text: String) {
    // A fixed, tight width so From / To / Cc / Bcc all line up and the input starts just past the label
    // (K-9 style); still wide enough for the short field labels across locales.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(48.dp),
    )
}

@Composable
private fun FieldDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The label for each mode: the menu entry, and the mode named in the button's contentDescription. */
private fun pgpModeLabel(mode: PgpMode): Int = when (mode) {
    PgpMode.OFF -> R.string.compose_pgp_off
    PgpMode.SIGN -> R.string.compose_pgp_sign
    PgpMode.ENCRYPT -> R.string.compose_pgp_encrypt
    PgpMode.ENCRYPT_UNSIGNED -> R.string.compose_pgp_encrypt_unsigned
    PgpMode.SMIME_SIGN -> R.string.compose_smime_sign
    PgpMode.SMIME_ENCRYPT -> R.string.compose_smime_encrypt
}

/**
 * The eight formatting buttons under the body (#131). Placed down here on purpose:
 */
@Composable
private fun FormattingBar(
    active: Set<Inline>,
    activeList: BlockKind?,
    linkActive: Boolean,
    onLink: () -> Unit,
    onToggle: (Inline) -> Unit,
    onToggleList: (BlockKind) -> Unit,
    onClear: () -> Unit,
) {
    // Scrollable, since the bar went from five buttons to eight: 8 × 48 dp + 8 = 392 dp of fixed
    // width. It no longer fits a 360 dp phone, let alone a multi-window pane or a large "display size"
    // setting, where "clear formatting" would be off the screen with no way to reach it. Scrolling is
    // what Gmail's own bar does. A scroll container, not a focusable one.
    Row(
        Modifier
            .fillMaxWidth()
            .height(FORMATTING_BAR_HEIGHT)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        FormattingButton(Inline.BOLD, Icons.Filled.FormatBold, R.string.compose_format_bold, active, onToggle)
        FormattingButton(Inline.ITALIC, Icons.Filled.FormatItalic, R.string.compose_format_italic, active, onToggle)
        FormattingButton(Inline.UNDERLINE, Icons.Filled.FormatUnderlined, R.string.compose_format_underline, active, onToggle)
        FormattingButton(Inline.STRIKE, Icons.Filled.FormatStrikethrough, R.string.compose_format_strikethrough, active, onToggle)
        ListButton(BlockKind.BULLET, Icons.Filled.FormatListBulleted, R.string.compose_format_list_bulleted, activeList, onToggleList)
        ListButton(BlockKind.NUMBER, Icons.Filled.FormatListNumbered, R.string.compose_format_list_numbered, activeList, onToggleList)
        // The link is not a family: it carries an address, so it opens a dialog instead of toggling,
        // and it is lit by `linkAt` rather than by the `active` set.
        IconButton(onClick = onLink, modifier = Modifier.size(48.dp).semantics { selected = linkActive }) {
            Icon(
                Icons.Filled.Link,
                contentDescription = stringResource(R.string.compose_format_link),
                tint = if (linkActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.FormatClear, contentDescription = stringResource(R.string.compose_format_clear))
        }
    }
}

/** One list's button, built exactly like [FormattingButton]. `activeList` is the kind covering the
 *  whole of the selection (or the caret's line), so at most one of the two is ever lit, a run of
 *  lines is of one kind. */
@Composable
private fun ListButton(
    kind: BlockKind,
    icon: ImageVector,
    label: Int,
    activeList: BlockKind?,
    onToggleList: (BlockKind) -> Unit,
) {
    val isActive = kind == activeList
    IconButton(
        onClick = { onToggleList(kind) },
        modifier = Modifier.size(48.dp).semantics { selected = isActive },
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** One family's button: `selected` in the semantics and a primary tint while the family is active. */
@Composable
private fun FormattingButton(
    kind: Inline,
    icon: ImageVector,
    label: Int,
    active: Set<Inline>,
    onToggle: (Inline) -> Unit,
) {
    val isActive = kind in active
    IconButton(
        onClick = { onToggle(kind) },
        modifier = Modifier.size(48.dp).semantics { selected = isActive },
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/**
 * Quick "send later" presets as (preset, label, epoch-millis) for a menu drawn at [nowMillis].
 */
private fun schedulePresets(context: android.content.Context, nowMillis: Long, lastScheduleMinutes: Int?): List<Triple<SchedulePreset, String, Long>> =
    schedulePresetsAt(nowMillis, java.time.ZoneId.systemDefault())
        .let { fixed ->
            fixed + listOfNotNull(
                lastPickedPresetMillis(lastScheduleMinutes, nowMillis, java.time.ZoneId.systemDefault(), fixed.map { it.second })
                    ?.let { SchedulePreset.LAST_PICKED to it },
            )
        }
        .map { (preset, millis) ->
            val label = schedulePresetLabel(
                preset,
                millis,
                { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) },
                { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) },
                { res, arg -> if (arg == null) context.getString(res) else context.getString(res, arg) },
            )
            Triple(preset, label, millis)
        }
