package app.sterna.ui.compose

import android.Manifest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.clear as clearFormatting
import app.sterna.core.data.text.remapAfterEdit
import app.sterna.core.data.text.stylesAt
import app.sterna.core.data.text.toggle as toggleFormatting
import app.sterna.core.data.text.toggleBlock
import androidx.compose.ui.window.PopupProperties
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
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.components.ContactAvatar
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.drawTern
import app.sterna.contacts.ContactSuggestion
import java.time.ZoneId

// ExperimentalLayoutApi: the leave dialog's FlowRow, which wraps its three answers instead of
// truncating them where the labels are long (German, Russian) — same as the settings screens'.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Send the draft this composer is editing to the Trash, the way the list does it (#127) —
     * through the inbox's own ViewModel, so it is the same move and the same Undo.
     *
     * It receives a TAKER rather than the message: the draft is a one-shot state
     * ([ComposeViewModel.takeEditingDraft], which also refuses while a send is in flight), and
     * consuming it before the navigation guard has agreed to run means a tap the guard drops has
     * thrown the state away for nothing. The reader's delete puts its mutation inside the guard;
     * this is the same shape, for a value that has to be fetched rather than passed.
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
    body: String? = null,
    draftId: String? = null,
    /**
     * The queued row this composer was opened on by Outbox → Edit (#70), from the route — the twin
     * of [draftId]. Read only by a composer rebuilt after a process death, whose in-memory hand-over
     * is gone: it takes the row back by this id once the startup sweep has parked it.
     */
    outboxId: Long? = null,
    viewModel: ComposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val replyQuote by viewModel.replyQuote.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpMode by viewModel.pgpMode.collectAsStateWithLifecycle()
    val recipientKeys by viewModel.recipientKeys.collectAsStateWithLifecycle()
    val pgpKeylessRecipients by viewModel.pgpKeylessRecipients.collectAsStateWithLifecycle()
    val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()
    val editingOutbox by viewModel.editingOutbox.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    // The lease on a draft the PHONE is keeping and the server has not got (#95) — non-null only
    // while this composer really holds the row. Not `draftId` and not the route it belongs to: on a
    // row the upload worker consumed between the list being drawn and the tap landing, both still
    // say "local draft" while this screen holds nothing, and the button would do nothing.
    val editingLocalDraftId by viewModel.editingLocalDraftId.collectAsStateWithLifecycle()
    // Whether deleting that row would LEAVE the server copy it masks behind (#95) — true only when
    // this phone never read that copy whole, which is when the deletion deliberately spares it. The
    // confirmation has to say so before the finger lands: the row goes, the mask lifts, and the
    // draft from before the edit comes back at the top of Drafts.
    val deleteKeepsServerCopy by viewModel.deleteKeepsServerCopy.collectAsStateWithLifecycle()
    // The trash icon's confirmation (#127). Unconditional, unlike the leave dialog: this one is not
    // about unsaved changes, it is about removing the draft itself — the message the user came here
    // to edit — and the Undo it leaves behind restores the SERVER copy, not the screen.
    //
    // ⭐ A flag lives where what it acts on lives, so it is the ViewModel's and not a `remember`
    // here: a rotation recreates MainActivity and a bare `remember` fell back to false, taking the
    // dialog off screen without a word. ⛔ And not `rememberSaveable` either — see the field's own
    // KDoc: that one outlives the process, while the draft this dialog names does not. The flag's
    // real lifetime is this composer's NAVIGATION ENTRY — see the field's KDoc before quoting it.
    val pendingDraftDelete by viewModel.pendingDraftDelete.collectAsStateWithLifecycle()
    // The leave dialog (#35, #127) and the two chained pre-send guards. ⭐ All three are the
    // ViewModel's for exactly the reason above: a rotation recreates MainActivity, the composition
    // restarts, and a bare `remember` fell back to false — the question left the screen without a
    // word, as if it had been answered. ⛔ Not `rememberSaveable` either; the fields' KDoc says what
    // their real lifetime is, the composer's NAVIGATION ENTRY. The local names stay what the rest of
    // this file already calls them.
    val showDiscard by viewModel.pendingDiscard.collectAsStateWithLifecycle()
    val showForgotAttachment by viewModel.pendingForgotAttachment.collectAsStateWithLifecycle()
    val showManyRecipients by viewModel.pendingManyRecipients.collectAsStateWithLifecycle()
    // The "pick date and time" pair (#161), the menu's fifth entry: the calendar, then the clock,
    // and the day already chosen between the two. ⭐ All three are the ViewModel's for the reason
    // above — and the DAY especially: a rotation between the two dialogs would otherwise lose it
    // and bring the clock back over nothing. ⛔ Not `rememberSaveable` either, same KDoc, same
    // NAVIGATION-ENTRY lifetime.
    val pendingScheduleDay by viewModel.pendingScheduleDay.collectAsStateWithLifecycle()
    val pendingScheduleTime by viewModel.pendingScheduleTime.collectAsStateWithLifecycle()
    val scheduleDay by viewModel.scheduleDay.collectAsStateWithLifecycle()
    // Whether a draft saved on the SERVER survives leaving — what the leave dialog may not announce
    // as destroyed (#35). Not `draftId != null`: an undone send reopens with restore=true and no
    // draftId in the route while its draft is still sitting in Drafts.
    val savedDraftBehind by viewModel.savedDraftBehind.collectAsStateWithLifecycle()
    val attachmentsTouched by viewModel.attachmentsTouched.collectAsStateWithLifecycle()
    // Reopening a saved draft: whether its text is still being fetched, and what to say if it never
    // arrived. Two flows and not one state, because ComposeState is a single slot that Sending and
    // Error overwrite. The decision itself is [draftReopenView], out here in ComposeText so a JVM
    // test can execute it, and it hands back the sentence to show rather than a flag to interpret.
    // It is taken further down, once `applied` exists — the third thing it needs.
    val draftLoading by viewModel.draftLoading.collectAsStateWithLifecycle()
    val draftLoadFailed by viewModel.draftLoadFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attach)
    }
    // OpenKeychain's passphrase/key dialog round-trip during a PGP send.
    val pgpLauncher = rememberPgpInteractionLauncher { data -> viewModel.retryPgpSend(data) }
    LaunchedEffect(state) {
        (state as? ComposeState.PgpInteraction)?.let { pgpLauncher(it.pendingIntent) }
    }
    // Plain-language feedback when the user cycles the lock toggle, so the icon states
    // (off / sign / encrypt) aren't a mystery to newcomers. Only manual toggles announce; an
    // automatic opportunistic switch stays silent (#35).
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
                    },
                ),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Outcomes worth reporting after the screen closes (e.g. a draft save that had to keep the
    // original): a toast, because compose navigates away the moment the save succeeds.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { res -> Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }

    // Whether the body ON SCREEN already carries the reply's quoted original.
    //
    // ⚠ Declared HERE, away from the other `rememberSaveable`s further down, because the effect
    // right below reads it and Kotlin does not read ahead — this position is a compile constraint,
    // not a preference.
    //
    // ⚠ `rememberSaveable`, and NOT a ViewModel field or flow — same reason as `requestReceipt`
    // and `applied` below, and the idiom `primingHandled` already follows in this file. The only
    // case it exists for is the app being KILLED with the composer open: the ViewModel dies with
    // the process, `prepared` is an ordinary field so `prepare()` runs again on a brand new one,
    // while this screen comes back with its body, its baseline and its `applied` restored. A
    // ViewModel-side flag would be false again in exactly that case and close nothing.
    var quoteLanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.prepare(
            replyTo, mode, accountId, restore, to, cc, bcc, subject, body, draftId,
            quoteAlreadyOnScreen = quoteLanded,
            outboxId = outboxId,
        )
        // Attach any files shared into the app (ACTION_SEND) — a one-shot handoff we read and
        // clear, so it only lands on this compose screen (Codeberg #45).
        val app = context.applicationContext as android.app.Application
        app.container.pendingShareUris.takeIf { it.isNotEmpty() }?.let { shared ->
            app.container.pendingShareUris = emptyList()
            shared.forEach { viewModel.attach(it) }
        }
    }

    // A picked file is staged in cacheDir, which is not ours to keep: Settings → Apps → Sterna →
    // Storage → Clear cache empties it on request, and Android evicts it on its own when the phone
    // runs short of room. Neither closes this screen, so the chip went on being drawn over nothing
    // and the save was what found out, with a red banner and no draft written. Swept ON_START —
    // the composer coming back to the front is the only moment it can be noticed, and a
    // LaunchedEffect(Unit) would have run once, before any of it happened.
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
        // Real gate: suggestions off AND permission not granted AND not yet primed. The preview
        // flag forces the sheet regardless (still wired to the real permission launcher).
        val gateOpen = !contactSuggestionsOn && !AndroidContacts.hasPermission(context) && !contactsPrimed
        if (FORCE_ONBOARDING_PREVIEW || gateOpen) {
            showContactsPriming = true
            primingHandled = true
        }
    }
    if (showContactsPriming) {
        // Both "Not now" and a swipe-dismiss mark it primed, so it is never offered again. The
        // toggle still lives in Settings > Privacy.
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

    // The body and its baseline are the two big things on this screen, and they are the ONLY two
    // that do not travel in the activity's saved-state parcel. Replying to a ~324 kB message and
    // pressing HOME killed the activity on TransactionTooLargeException (a 1 340 240 byte parcel)
    // and the draft was gone without a word: `Parcel.writeString` writes UTF-16, so each of the two
    // saveables cost 4 bytes of parcel per character of body. They go through an app-private file
    // instead, and the parcel carries only its short token. See [ComposerResumeSlot].
    //
    // ⚠ Declared HERE, well above `body` which delegates to it, because the four exits below
    // close it and Kotlin does not read ahead — this position is a compile constraint, not a
    // preference. Same reason `quoteLanded` sits where it does.
    // ⛔ ONE SLOT PER NAVIGATION ENTRY, named by an id that is itself parcelled (a dozen bytes).
    // Two composers ARE reachable — MainActivity is singleTask, onNewIntent feeds pendingMailto,
    // and the host navigates to `compose` unguarded — so a mailto: link or a share arriving with
    // the composer open stacks a SECOND composer on the first. Sharing one file, closing the one on
    // top erased the record of the one underneath and the long reply came back blank.
    val slotId = rememberSaveable { newComposerSlotId() }
    val resumeSlot = remember(context, slotId) { ComposerResumeSlot(composerResumeFile(context, slotId)) }
    val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot)) {
        ComposerResumeState(TextFieldValue(), "")
    }
    // ⛔ THE BODY DID NOT COME BACK, AND THE SAVE MUST BE TOLD. Above the inline bound a failed
    // disk write parks nothing, so the saver carries the LOSS instead of the text. Everything else
    // on this screen comes back from saveables of its own — `applied`, `to`, `cc`, `subject` — so
    // the editor is redrawn looking like the draft with an empty body, `initialBody` is empty too
    // (the leave guard sees no change), and the Save icon is lit as soon as one recipient survived.
    // That save carries `replacesEmailId` and, without this line, a clean verdict:
    // `draftReplacementIsFaithful` never compares bodies, so the server copy is destroyed and a
    // bodiless one takes its place. On a rotation. Without a word.
    //
    // ⚠ An EFFECT into the view model, not an argument on `saveDraft`/`send`/`schedule`: FOUR
    // routes destroy (the interactive save, the deferred upload the worker finishes, the discard,
    // the send) and all four read one field, `editingDraftLossy`. Threading a per-call argument
    // through them is four chances to forget one, and the one forgotten is the lost draft.
    LaunchedEffect(resume.bodyWasLost) { if (resume.bodyWasLost) viewModel.onComposerBodyLost() }

    // "Takes flight": when the message is queued (Done), play a brief lift-off — the
    // content rises and fades, a tern arcs off-screen — then navigate back. The send
    // already fired in viewModel.send(); this is purely cosmetic and must not delay it.
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
            // Sent, saved as a draft or scheduled: every one of them lands on Done, and the parked
            // body must go with the screen — see the `closed` flag in [ComposerResumeState] for why
            // a bare clear() here would be written straight back out at teardown.
            resume.close(resumeSlot)
            onDone()
        }
    }

    // A draft the phone was keeping has just been deleted: leave the way the X leaves — a bare
    // popBackStack() onto the Drafts list this composer was opened from, which is where the row was
    // showing. ⛔ NOT through ComposeState.Done, which this branch used to end on: that plays the
    // tern's flight above, an animation written for a message that went OUT, and then lands on the
    // inbox. The signal comes from the ViewModel and not from the tap, because the destroy has to be
    // durable, the server copy evicted and the row consumed first — a deletion that throws keeps
    // this screen up, with its error banner.
    LaunchedEffect(Unit) {
        viewModel.localDraftDeleted.collect {
            resume.close(resumeSlot)
            onCancel()
        }
    }

    // Which field opens focused — decided once, from how this composer was opened, before the
    // prefill parameters are shadowed by the editable state below. See [initialComposeFocus].
    val initialFocus = remember {
        initialComposeFocus(
            isDraft = draftId != null,
            isReply = replyTo != null && composeOpening(mode).quotes,
            linkTo = to,
            linkSubject = subject,
        )
    }
    // How much body the composer was OPENED with — a mailto: link's `body=`, nothing otherwise.
    // Read here, next to the focus rule and for the same reason: below, the editable state shadows
    // the parameter. It is what tells the prefilled body's own text from the signature appended
    // under it, so the caret can start after the former (#83).
    val linkBodyLength = remember { if (body.isNullOrBlank()) 0 else body.length }

    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    // Like the body, the subject carries its caret (a TextFieldValue, not a bare String) so a tap
    // before its first character can put the cursor at the very start (#26).
    var subject by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // The body carries its caret with it (a TextFieldValue, not a bare String), so a prefilled
    // compose can open with the cursor where writing continues (#63).
    var body by resume.bodyState
    // The body's inline styling (#131) lives BESIDE the TextFieldValue, not inside it: `body`
    // holds plain text, and the spans are laid over it at render time, so the ~70 sites that read
    // `body.text` / `body.selection` see exactly what they saw before.
    var ranges by resume.rangesState
    // …and its list blocks (#131), beside the spans and beside the text for the same reason: a
    // block is a run of LINES, and `body.text` is what the field, the save and the send still read.
    var blocks by resume.blocksState
    // What the next keystroke at the caret should carry, once a button was tapped with nothing
    // selected; `null` = no instruction, the sticky rule of `remapAfterEdit` decides. Transient
    // on purpose: it is a gesture in flight, not a value worth parking across a rotation.
    var pending by remember { mutableStateOf<Set<Inline>?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // "Ask for a read receipt" (RFC 8098), per message, from the overflow menu.
    //
    // ⚠ rememberSaveable, and NOT a ViewModel flow like the padlock beside it. The padlock survives
    // a process death only because it is re-derived from a default setting; this box has no setting
    // to be reborn from, so a ViewModel field would come back CLEAR after the app was killed with
    // the composer open — the screen would then show "not asked for" while the user had asked, and
    // the send would agree with the screen. It is a field value like the recipients and the body,
    // it is saved like them, and it is handed to send/save/schedule as an argument like them.
    var requestReceipt by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialCc by rememberSaveable { mutableStateOf("") }
    var initialBcc by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by resume.baselineState
    var baselineRanges by resume.baselineRangesState
    var baselineBlocks by resume.baselineBlocksState
    // …the box included: a reopened outbox message can OPEN ticked, so "ticked" is not an edit while
    // ticking or clearing it is. Without this baseline the leave guard could not see either, and
    // clearing the box then leaving sent the message still asking (see [ComposeDirty]).
    var initialRequestReceipt by rememberSaveable { mutableStateOf(false) }
    // The body and its styling as one value: derived on every recomposition, never stored twice.
    val rich = RichBody(body.text, ranges, blocks)

    // Which compose this is, for the signature rules: a forward carries its original at send time
    // (so its body holds no quote), while both a reply and a forward obey the "signature in
    // replies" / "below the quoted text" settings.
    val isReplyBody = replyTo != null && composeOpening(mode).quotes
    val isReplyOrForward = replyTo != null
    // Changing "From" reads those settings from DataStore, which suspends.
    val scope = rememberCoroutineScope()

    // Land in the recipient field with the keyboard up, unless the recipients are already filled
    // (a reply, a reopened draft, or a mailto: link) — then the subject or the body takes the
    // focus, see the prefill below. The To field opens and self-focuses via its `autoFocus` flag
    // further down.
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
                // continues; it used to sit at offset 0 by accident of the String field.
                subject = TextFieldValue(it.subject, TextRange(it.subject.length))
                // Open with the caret where the writing continues, and the keyboard up: after the
                // last character of a reopened draft, above the quoted original of a reply (#63),
                // and for a mailto: link after the body the link supplied — one writes after that
                // text, not before it, which is also just above the signature the prefill appended
                // below it. A link with no body of its own still opens above the signature (#83).
                val caret = initialBodyCaret(
                    bodyLength = it.body.length,
                    focus = initialFocus,
                    isDraft = draftId != null,
                    linkBodyLength = linkBodyLength,
                )
                body = TextFieldValue(it.body, TextRange(caret ?: 0))
                // …with the styling the prefill carried (#131): a reopened draft brings its own
                // spans, every other opening brings none. ⛔ `emptyMap()` here, as it was, opens a
                // styled draft with its bold gone from the screen — and the next save stores that.
                ranges = it.bodyRanges
                // …and its lists, on the same terms: `emptyList()` here opens a draft that HELD a
                // list without it, and the next save writes that removal.
                blocks = it.bodyBlocks
                initialTo = prefilledTo
                initialCc = cc
                initialBcc = bcc
                initialSubject = it.subject
                initialBody = it.body
                // The baseline is what the prefill LANDED with, styling included: `emptyMap()`
                // here makes a reopened styled draft look edited the moment it opens, and the
                // leave guard then asks about changes nobody made.
                baselineRanges = it.bodyRanges
                baselineBlocks = it.bodyBlocks
                // This prefill CARRIED the quote — the original was not cached, so `prepare` built
                // the whole body with the quoted original inside it and there is no out-of-band
                // hand-over to raise the flag below. Without this post the flag stays false while
                // the quote is plainly in the body, and after a process death the rebuilt
                // ViewModel draws "Couldn't load the original message." straight over it (G6).
                if (it.quoted) quoteLanded = true
                // A reopened message that asked for a read receipt still asks (an undone send, a
                // queued item taken back out of the Outbox): re-editing it enqueues a NEW row, so a
                // box left clear here cancels the request. Applied with the other prefilled fields,
                // inside the same `!applied` gate, so nothing overwrites a box the user has ticked.
                requestReceipt = it.requestReceipt
                initialRequestReceipt = it.requestReceipt
                applied = true
                // The To field self-focuses on first composition; the other two are asked here,
                // once the prefill they open on is actually in place.
                when (initialFocus) {
                    ComposeFocus.BODY -> runCatching { bodyFocus.requestFocus() }
                    ComposeFocus.SUBJECT -> runCatching { subjectFocus.requestFocus() }
                    ComposeFocus.RECIPIENTS -> Unit
                }
            }
        }
    }

    // A reply/reply-all opens with To/Subject prefilled instantly from the cache; the quoted
    // original arrives a moment later (it needs the full message fetched, which offline stalls on
    // the network timeout). Drop it into the body only while the body is still the untouched initial
    // prefill — never over text the user has started typing — and re-baseline so it isn't seen as an
    // unsaved edit. The caret stays at the top, above the quote, exactly as a fresh reply opens.
    LaunchedEffect(replyQuote) {
        // ⛔ THE re-entry guard, and it became load-bearing with the consume below: retiring the
        // quote writes `null` into the flow, which is a NEW value, so this effect is relaunched
        // once more with `replyQuote == null`. Drop this `?:` and that second pass carries an
        // empty quote into the branch below with `applied` true and the body still equal to the
        // baseline just re-seated — the body and its baseline would both be emptied, the reply
        // losing its quote AND its signature with nothing left dirty to warn about it.
        val quote = replyQuote ?: return@LaunchedEffect
        if (canApplyReplyQuote(applied, body.text, initialBody)) {
            body = TextFieldValue(quote, TextRange(0))
            // A quote replaces a plain prefill: neither the body nor its baseline carries styling.
            ranges = emptyMap()
            blocks = emptyList()
            baselineRanges = emptyMap()
            baselineBlocks = emptyList()
            initialBody = quote
            // Dropped in exactly once, AFTER the write: the activity is recreated by a rotation, a
            // theme flip, a language change or a multi-window resize while the ViewModel survives,
            // and an unconsumed quote replays here and puts the caret back at offset 0 over the
            // selection just restored. ⚠ The write-then-retire ORDER guards nothing today — no
            // line between the two suspends, so cancellation cannot land there. It is the repo's
            // order (ConnectScreen.kt:318-325) and it is pinned so that a suspending call added
            // between them later cannot empty the flow before the quote has landed.
            viewModel.consumeReplyQuote()
            // The body now carries the quote, and this is the ONLY place that may say so: the flag
            // is saved, so after the app is killed and the composer rebuilt it is what tells the
            // fresh ViewModel to keep quiet about a quote that is already here. ⛔ Never posted at
            // the top of this effect nor in the toast branch below — it would then mean "the effect
            // ran", and a reply that genuinely has no quote would go out without one and without a
            // word, which is the very thing the B6 toast exists to prevent.
            quoteLanded = true
        } else if (applied) {
            // The user started writing before it arrived, so it is not dropped in over their text.
            // Say so instead of silently sending a reply with no quote (B6).
            viewModel.noticeQuoteNotAdded()
            // Said exactly once, AFTER the notice — otherwise each activity recreation posts the
            // toast again, and they stack up in LENGTH_LONG.
            viewModel.consumeReplyQuote()
        }
        // ⛔ No consume on the third way out (the quote came back before the header prefill
        // landed): nothing has posted it and nothing has announced it, so retiring it there would
        // lose it in silence.
    }

    // Which of the three screens a reopened draft gets. Taken HERE and not up with the two flows,
    // because it needs `applied`: once the prefill has landed, the editor is drawn whatever the
    // fetch is doing. `applied` is a rememberSaveable and survives process death while the
    // ViewModel does not, so after a kill it is the only thing that knows the text on screen is the
    // reader's — see [draftReopenView], which explains why hiding it would be the worse lie.
    val reopen = draftReopenView(draftLoading, draftLoadFailed, applied)

    val sending = state is ComposeState.Sending

    // The Save-as-draft icon greys out (disabled) while there is nothing worth saving — the same
    // #69 rule the save itself uses (draftHasContent), so the button's state matches what tapping it
    // would do. A typed recipient counts, so the icon lights up as soon as an address is entered.
    // Recomputed on each edit to recipients/subject/body/attachments (all observed state).
    val canSaveDraft = draftHasContent(to, cc, bcc, subject.text, body.text, attachments.isNotEmpty())

    // Leaving without sending or saving. A message reopened from the Outbox goes back to the queue
    // (#70) — closing its editor is not deleting it, and the queue holds the only copy — unless
    // this composer LOST its body and holds nothing: then the row is parked in the Outbox instead
    // of queued, because queued it leaves in under a second, when the screen has just said to
    // close and reopen it. The editor's raw text is what decides, hence `body.text` here. For
    // anything else this is a no-op and the screen just closes.
    val cancel = {
        viewModel.abandon(body.text)
        resume.close(resumeSlot)
        onCancel()
    }

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits. A message the user
    // pulled back out of a send (Undo) is also guarded even untouched: it lives on this screen only,
    // so closing would destroy it (#70) — unlike an outbox message, which [cancel] gives back. The
    // verdict itself is [ComposeDirty]: recipients compared as sets so reordering a chip is not an
    // "edit" (#94), and a pre-filled Cc/Bcc/attachment not counted as one either (#70). The
    // read-receipt box is one of its fields: it is what the message will ASK FOR, and both senses of
    // changing it were being dropped in silence on a reopened outbox message — clearing the box and
    // leaving let the message go out still asking.
    val dirty = ComposeDirty.isDirty(
        onlyCopy = onlyCopy,
        to = to, initialTo = initialTo,
        cc = cc, initialCc = initialCc,
        bcc = bcc, initialBcc = initialBcc,
        subject = subject.text, initialSubject = initialSubject,
        body = RichBody(body.text, ranges, blocks), initialBody = RichBody(initialBody, baselineRanges, baselineBlocks),
        requestReceipt = requestReceipt, initialRequestReceipt = initialRequestReceipt,
        attachmentsTouched = attachmentsTouched,
    )
    val attemptClose = { if (dirty && !sending) viewModel.askDiscard() else cancel() }

    // Pre-send guards, chained: "forgot attachment?" then "many recipients?". Both flags are read
    // from the ViewModel at the top of this composable, so each gate is opened and closed through it.
    val recipientCount = listOf(to, cc, bcc).sumOf { field -> field.split(',', ';').count { it.isNotBlank() } }
    // Can send only with at least one To recipient and every entered address valid.
    val allRecipients = listOf(to, cc, bcc).flatMap { recipientTokens(it) }
    // When encrypting, every recipient must have a resolvable key (false = known-missing;
    // absent = not yet checked, allowed — the send resolves keys and reports precisely).
    val keysReady = !pgpMode.encrypts || allRecipients.none { recipientKeys[it] == false }
    val canSend = recipientTokens(to).isNotEmpty() && allRecipients.all(::isValidEmail) && keysReady
    val sendNow = { viewModel.send(to, cc, bcc, subject.text, rich, requestReceipt) }
    val proceedAfterAttachment = {
        if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()
    }
    val attemptSend = {
        if (attachments.isEmpty() && mentionsAttachment("${subject.text}\n${body.text}")) {
            viewModel.askForgotAttachment()
        } else {
            proceedAfterAttachment()
        }
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

    // The menu's fifth entry, in two steps: a calendar, then a clock (#161). ⛔ Drawn HERE, at the
    // top level of the composable with the four other dialogs, and NOT inside the toolbar's Box:
    // that Box sits under `if (reopen is DraftReopenView.Editor)`, so a reopened draft that fails
    // its read underneath would take the dialog off screen mid-choice. ⚠ The reason CHANGED with
    // #164 and the rule did not: the Box used to sit under `if (draftSaveAllowed(pgpMode))` too,
    // where closing the padlock was the way it vanished. The padlock no longer removes this Box —
    // the reopen gate still does, and one gate is enough to keep the dialogs out of here.
    //
    // ⛔ Both locks against a send into the PAST are in this pair, because there is none further
    // down: `ScheduledSends.enqueue` coerces a negative delay to zero, so a lapsed instant would
    // fire the message at once, and a send cannot be recalled. No wording says so — a greyed-out
    // button is the whole message.
    if (pendingScheduleDay) {
        // ⛔ In LANDSCAPE the calendar's GRID does not fit. `DatePickerDialog` lays its surface out
        // at `requiredWidth(ContainerWidth).heightIn(max = ContainerHeight)`, i.e. 360 dp wide and
        // at most 568 dp tall — a MAX, never a min. Turned, the real ceiling is the SCREEN's
        // 411 dp, and Material's own content box has no scroll of its own, so whatever the month
        // grid does not fit into is simply CLIPPED. Measured on `emu` at 914×411 dp, 2026-08-21,
        // opening "Pick date and time" and THEN turning: August 2026 stopped at Saturday the 29th,
        // that cell itself truncated, the 30th and the 31st had no node at all, Saturday the 1st
        // was gone off the top, and Cancel/OK were 37 px tall instead of 53, drawn over the 23–29
        // row. Nothing scrolled: a swipe up uncovered neither the 30th nor the 31st.
        // So the typed entry, which is Material's own answer to a constrained height — the very
        // same decision the clock takes below, on the very same rule.
        //
        // ⛔ And the typed face does not fit inside `DatePickerDialog` EITHER, once the keyboard
        // is up — which it is on the first touch, the face being a text field. Measured on `emu`
        // in landscape at 914×411 dp, 2026-08-21: the `ime` inset started at y=502, the Cancel/OK
        // row was announced at y=492..618, and a tap in the middle of `OK` did nothing at all — it
        // landed on the numeric keypad drawn there, and the clock never opened.
        // ⚠ It is NOT the weight: `DatePickerDialog` already gives its content box a
        // `weight(1f, fill = false)` and its action row none, so the row is measured FIRST there
        // just as it is in [SchedulePickerDialog] — that weight is the CLOCK's mechanism, not this
        // one. What differs is `usePlatformDefaultWidth = false`, which the grid needs for its
        // 360 dp: at `false`, `DialogLayout.internalOnMeasure` throws away the constraints the
        // window handed it and re-measures against `AT_MOST(displayWidth) × AT_MOST(displayHeight)`
        // — the whole display, keyboard included. ⚠ The chain from there to the 44 dp of overlap
        // measured is NOT established; what is established is the reading above, and that the
        // clock's shell, measured green with the keyboard up the same day, does not have it.
        //
        // So the two faces take two shells, and neither may be given the other's:
        //  - the GRID keeps `DatePickerDialog`, whose `requiredWidth(360.dp)` is what makes the
        //    seven 48 dp columns fit a 360 dp screen at all. A shell of ours there would bring the
        //    clipping back in PORTRAIT, on the commonest gesture there is.
        //  - the TYPED face takes [SchedulePickerDialog], the clock's shell, which was measured
        //    green with the keyboard up. Its `verticalScroll` measures what it holds in INFINITE
        //    height, which the grid may never be handed — see the guard on the picker slot below.
        val dayConfiguration = LocalConfiguration.current
        val dayAsInput = schedulePickerAsInput(dayConfiguration.screenHeightDp, dayConfiguration.screenWidthDp)
        // Two bounds, one apiece, and they are not interchangeable. `yearRange` decides which
        // years the chevron DRAWS: Material's default is 1900..2100, which put a hundred and
        // twenty-six dead years in front of this one. `isSelectableDate` is the lock — the only
        // thing refusing a past DAY. ⛔ No upper bound of ours: no horizon exists anywhere in the
        // scheduled-send code and inventing one here would be a limit to carry forever.
        val dayState = rememberDatePickerState(
            yearRange = scheduleFirstYear(System.currentTimeMillis(), ZoneId.systemDefault())..DatePickerDefaults.YearRange.last,
            initialDisplayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    scheduleDaySelectable(utcTimeMillis, System.currentTimeMillis(), ZoneId.systemDefault())
            },
        )
        // ⚠ `rememberDatePickerState` SAVES its displayMode: the restored state comes back on the
        // face it was opened with, and `initialDisplayMode` above is never consulted again. Without
        // this effect the defect's OWN gesture survives the fix — open in portrait, then turn, and
        // the cut-off grid is redrawn. Keyed on the shape of the window and on nothing else: it is
        // the SHAPE that decides, and nothing else may fight it frame by frame.
        LaunchedEffect(dayAsInput) {
            dayState.displayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker
        }
        // Both faces answer with the SAME two buttons, so their bodies exist once and are handed to
        // whichever shell is drawn. ⚠ They are `@Composable` slots, not values: each shell calls
        // them where its own action row is.
        val dayOk: @Composable () -> Unit = {
            TextButton(
                // Nothing is scheduled here: the day is kept, this dialog closes and the clock
                // opens. Until a day is picked the picker hands back null, so there is nothing
                // to keep and OK stays grey.
                enabled = dayState.selectedDateMillis != null,
                onClick = {
                    viewModel.chooseScheduleDay(dayState.selectedDateMillis ?: 0L)
                    viewModel.clearScheduleDay()
                    viewModel.askScheduleTime()
                },
            ) { Text(stringResource(R.string.settings_ok)) }
        }
        val dayCancel: @Composable () -> Unit = {
            TextButton(onClick = { viewModel.clearScheduleDay() }) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
        if (dayAsInput) {
            SchedulePickerDialog(
                onDismissRequest = { viewModel.clearScheduleDay() },
                picker = {
                    // ⛔ The calendar is composed here ONLY on the typed face, and this guard is
                    // not the same test as the branch above. `rememberDatePickerState` restores the
                    // face it was SAVED on, so the first frame after a rotation taken on the grid
                    // comes back `DisplayMode.Picker` while the window is already landscape — the
                    // `LaunchedEffect` only lands after that frame. A grid inside this shell is
                    // measured in INFINITE height by its `verticalScroll`, and the year chevron's
                    // `LazyVerticalGrid` THROWS when it is measured that way; Material holds
                    // `yearPickerVisible` in a `rememberSaveable`, so a rotation taken with the year
                    // list open brings it back open. Without this guard the app STOPS on that frame
                    // instead of merely being clipped, and is one tap from it otherwise. One empty
                    // frame in a rare gesture is the price.
                    // ⛔ `showModeToggle = false` is the other half: Material's default of `true`
                    // draws a pencil that is one tap into that same crash.
                    if (dayState.displayMode == DisplayMode.Input) {
                        DatePicker(state = dayState, showModeToggle = false)
                    }
                },
                buttons = { dayCancel(); dayOk() },
            )
        } else {
            DatePickerDialog(
                onDismissRequest = { viewModel.clearScheduleDay() },
                // ⛔ `usePlatformDefaultWidth = false` is not an addition of ours: it is
                // `DatePickerDialog`'s OWN default being put back. Handing it a `DialogProperties`
                // replaces the whole object, and that flag defaults to `true`, which caps the window
                // at the platform's dialog width — under the 360 dp the surface REQUIRES, so the
                // grid would be cut sideways on a 360 dp screen in portrait.
                // `decorFitsSystemWindows = false` and `safeDrawingPadding()` stay on this branch
                // too: Material's own pencil is up here, so the typed face — a text field, keyboard
                // and all — can be reached by hand on a screen the grid fits.
                modifier = Modifier.safeDrawingPadding(),
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                confirmButton = dayOk,
                dismissButton = dayCancel,
            ) {
                // ⚠ Material's own face toggle stays exactly as Material puts it on THIS branch:
                // the grid fits here, so nothing is taken away on the commonest gesture there is.
                // It is the shell above that hides it, because there the pencil leads into a crash.
                DatePicker(state = dayState, showModeToggle = true)
            }
        }
    }

    if (pendingScheduleTime) {
        val zone = ZoneId.systemDefault()
        // 0L is 1970 — a day that is never STRICTLY ahead, so a clock that somehow opened with no
        // day behind it can only refuse.
        val day = scheduleDay ?: 0L
        // ⛔ NOT Material's default of midnight. For TODAY, midnight is an hour long gone, so the
        // dial would open on precisely the state the OK button below refuses — greyed out, and by
        // design there is no wording anywhere to explain it. An hour ahead is the answer, EXCEPT
        // late in the evening on today: past 23:00 an hour ahead has rolled over to tomorrow, and
        // keeping only its hour and minute pasted them back onto today, which reopened that same
        // dead dialog roughly a day in the past. The fallback is then the last minute today still
        // has. ⚠ In the final minute of the day even that is gone and OK is honestly grey.
        val opensAt = scheduleClockOpensAt(day, System.currentTimeMillis(), zone)
        // ⛔ The device's own 12/24-hour setting, not a forced 24 h: this dial is read by whoever
        // set that preference.
        val timeState = rememberTimePickerState(
            initialHour = opensAt.hour,
            initialMinute = opensAt.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        // ⛔ In LANDSCAPE Material lays its own dial out BESIDE the time display, wider than the
        // dialog. Measured on `emu` at 914×411 dp, 2026-08-20: the right half of the dial was off
        // screen, its digits drew over the AM/PM selector, and `PM` was unreachable. ⚠ Scrolling is
        // not the answer and the shell below does not make it one: the cut is HORIZONTAL, and the
        // vertical scroll added since moves nothing sideways. So the keyboard entry there, which is
        // Material's own answer to a constrained height, on Material's own threshold.
        val configuration = LocalConfiguration.current
        val clockAsInput = schedulePickerAsInput(configuration.screenHeightDp, configuration.screenWidthDp)
        // ⛔ [SchedulePickerDialog], a shell of ours, and NOT Material's `AlertDialog`: what makes
        // the difference is the SOFTWARE KEYBOARD, and Material puts the focus in `TimeInput`'s
        // hour field, so the keys come up on their own — at the rotation and at the first touch
        // alike. The reading that bought that shell, and what each of its lines does, are written
        // over the shell itself.
        SchedulePickerDialog(
            onDismissRequest = { viewModel.clearScheduleTime() },
            picker = {
                if (clockAsInput) TimeInput(state = timeState) else TimePicker(state = timeState)
            },
            buttons = {
                TextButton(onClick = { viewModel.clearScheduleTime() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
                TextButton(
                    // Lock 2, the one the calendar cannot give: "today, at an hour already gone".
                    // Read from the picker's own state, so the button lights and unlights while the
                    // dial turns.
                    enabled = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis()) != null,
                    onClick = {
                        // ⚠ Asked AGAIN, with a fresh clock. A button lit at composition and tapped
                        // after the minute turned would otherwise schedule into the past, which is
                        // to say send at once, irreversibly. Two reads cost one line.
                        val millis = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis())
                        // ⛔ The dialog closes only on the way that SCHEDULES something. When the
                        // minute turned between composition and the tap, closing here would take
                        // the clock off screen having scheduled nothing and said nothing. Left up,
                        // the choice is still there to correct. ⚠ And it is only correcting it
                        // that greys the button: `enabled` above reads the wall clock, which is
                        // not snapshot state, so a tap alone recomposes nothing and the button
                        // stays lit until the dial or the fields are touched. That window is at
                        // most the tail of one minute, and never on a day with hours left in it.
                        if (millis != null) {
                            viewModel.clearScheduleTime()
                            // ⛔ The confirmation belongs to the schedule, not to the tap: the view
                            // model answers `false` on every refusal it owns (the PGP/attachment
                            // choke point, and a body this composer LOST, which the two greyed-out
                            // buttons do not cover once the guard is real). Announced regardless,
                            // "Scheduled — …" is a sentence about a message that was not scheduled,
                            // immediately contradicted by the notice saying why.
                            if (viewModel.scheduleSend(to, cc, bcc, subject.text, rich, millis, requestReceipt)) {
                                // ⛔ The INSTANT, formatted exactly as the Scheduled list formats
                                // it — never a preset label, a sentence about another time.
                                val shown = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.compose_scheduled_toast, shown),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_ok)) }
            },
        )
    }

    // The day is dropped once the CLOCK is closed, whichever way out was taken (Cancel, a tap
    // outside, the system Back, or the confirmed schedule), so the next opening starts clean.
    // ⚠ Keyed on the flag rather than written into those ways out: a ROTATION is none of them, and
    // the day has to survive it — on a recreation this effect restarts with the flag still raised
    // and forgets nothing.
    LaunchedEffect(pendingScheduleTime) { if (!pendingScheduleTime) viewModel.forgetScheduleDay() }

    BackHandler(enabled = !showDiscard) { attemptClose() }

    if (pendingDraftDelete) {
        // Same shape as the Outbox's own delete confirmation (title, body, error-coloured action,
        // Cancel) — but NOT its words: that message has never been sent and exists nowhere else,
        // while this draft goes to the Trash exactly as it would from the list.
        AlertDialog(
            onDismissRequest = { viewModel.clearDraftDelete() },
            title = { Text(stringResource(R.string.compose_delete_draft_title)) },
            // Material's text slot is a height-bounded box with no scrolling of its own: a bare
            // Text in it is CUT, without an ellipsis and without anything on screen saying so. At
            // font_scale 2.0 in German (Moto G, 720×1280) the body stopped at "…getippt hast,
            // wird", losing "nicht gespeichert." — the sentence that says what was typed since the
            // composer opened is not saved (#127). Same modifier, same reason, as the two
            // per-sender rule dialogs.
            text = {
                Text(
                    // ⛔ One title, two BODIES, because the object is not the same one (#95). The
                    // server draft "goes to the Trash"; a draft the phone is keeping has never
                    // reached a server, so there is no Trash for it to go to and no Undo behind
                    // it — it and its files go at once. Saying "goes to the Trash" over it would
                    // be the app promising a way back that does not exist.
                    //
                    // ⛔ And the local body states ONLY what is true of all three local cases: the
                    // copy this phone holds, and its attachments, go straight away. It used to add
                    // "this draft hasn't reached the server … this can't be undone", which is the
                    // opposite of what happens when the row masks a server copy this phone never
                    // read whole: that copy is deliberately KEPT (emptiedLocalDraftKeepsServerCopy)
                    // and the pre-edit draft comes back at the top of Drafts the moment the row
                    // goes. So that case says so HERE, before the finger — the sentence the empty
                    // save posts afterwards, which names no gesture precisely because it now serves
                    // two.
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
                        // A draft the phone is keeping is deleted BY THE COMPOSER: there is no
                        // server row for the list's move-to-Trash to act on, and the gesture is
                        // the emptying one (#95 × #69) reached by a button — the server copy this
                        // row replaced destroyed under its proofs, then the row and its files. The
                        // screen closes on viewModel.localDraftDeleted above, with a bare pop —
                        // never on ComposeState.Done, which is a message taking flight.
                        viewModel.deleteEditingLocalDraft()
                    } else {
                        // The message is taken INSIDE the navigation guard (see SternaApp): a tap
                        // the guard refuses must not have consumed the draft on its way to being
                        // ignored.
                        //
                        // ⛔ THE FOURTH EXIT, and the one that was missed. A SERVER draft leaves
                        // through the host's popBackStack(), not through Done, not through
                        // localDraftDeleted and not through `cancel` — so nothing closed the slot,
                        // and the entry's teardown wrote the file back out with `closed` still
                        // false: the body of the draft just DELETED stayed on disk, consumable by
                        // nobody. The encrypted case takes this same branch. Closed on the TAKER'S
                        // RESULT so it happens exactly when the deletion really departs: a tap the
                        // guard drops must neither consume the draft nor throw its text away.
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
        // The leave dialog obeys the same TWO rules as the toolbar's Save action (#35): an
        // encrypted message may not leave a plaintext copy on the server, so when encrypting the
        // dialog does not offer to save — it says WHY (a draft is stored as typed, unprotected) and
        // offers only Discard and Cancel. Offering "Save draft" here was the leak: the toolbar hid
        // the action, this dialog uploaded the same text one tap later. The second rule is
        // `canSaveDraft` below, handed to discardChoices: over an emptied composer the toolbar
        // greys its icon and this dialog offered a live button, which deletes the draft it was
        // opened on rather than saving it.
        val mayKeepDraft = draftSaveAllowed(pgpMode)
        // What the dialog is allowed to CLAIM, decided by [discardWording] (#70): a message pulled
        // back out of the Outbox is not destroyed by leaving — its row goes back where it was — so
        // it may not be announced as "Discard message?". Nor may it be promised delivery: a failed
        // send returns to FAILED and waits for Retry. The buttons on offer are a separate question,
        // still [mayKeepDraft]'s (#35).
        val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft = savedDraftBehind)
        // "Discard changes" wherever a copy of the message survives leaving — the outbox row, and
        // the draft still in Drafts. The DRAFT variant used to say "Discard" beside a title and a
        // body that both spoke of changes only.
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
                            // A draft already saved on the server is not destroyed by leaving:
                            // cancel() → abandon() is a no-op outside the outbox, and the draft is
                            // still in Drafts afterwards. "Discard message?" was literally false —
                            // the body beside it said "changes" all along (#35, #127). Its own key,
                            // not the outbox's: the two name different situations.
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
                        when (wording) {
                            // Two outbox wordings, because the body has to be true of BOTH buttons
                            // beside it: "Save draft" takes the message OUT of the outbox, so it is
                            // only mentioned where that button exists (#70).
                            DiscardWording.OUTBOX -> R.string.compose_discard_message_outbox
                            DiscardWording.OUTBOX_ENCRYPTED ->
                                R.string.compose_discard_message_outbox_encrypted
                            DiscardWording.ENCRYPTED -> R.string.compose_discard_message_encrypted
                            // Encrypting a draft that is already on the server: it still owes the
                            // user the reason the Save button is gone, but it may not end on
                            // "leaving discards the message" — the copy in Drafts survives (#35).
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
            // AlertDialog has two button slots and this exit needs three answers, so all of them go
            // in the confirm slot as a FlowRow: one line where the labels fit, wrapped where they
            // don't (German, Russian), never truncated. Same shape and same order as the settings
            // screens' own three-answer exit (SaveChangesDialog), recopied rather than reused: that
            // one hard-codes its title and its three labels, and parameterising all four would have
            // been a new component rather than a shared one.
            //
            // WHICH buttons is [discardChoices], where it can be run: that Cancel is never missing
            // (#35, #127) and that Save draft never appears while encrypting (the 1.4.3 plaintext
            // leak) are both guarantees somebody has already come looking for, and neither was
            // reachable from a test while it was the shape of an `if` here.
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
            // Above the keyboard. What shows here is the security verdict of the lock toggle —
            // "not encrypted: anyone handling this mail can read it" — and the keyboard is up at
            // exactly the moment it is tapped (the composer opens focused on the recipients), so
            // left at the window's bottom edge the warning appeared UNDER the keyboard and was
            // never seen (#35). Same inset the writing area uses below: whichever of the keyboard
            // or the navigation bar is taller, never both.
            SnackbarHost(
                snackbarHostState,
                Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        },
        // Don't reserve a bottom system-bar (nav-bar) inset here: the body already pads the
        // bottom with max(ime, nav bar) below, and consuming the nav bar twice left a nav-bar-
        // tall composer-coloured strip between the keyboard and the body text (#26).
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
                  // Not one button while a reopened draft is still being read, or once it turned
                  // out it cannot be. Whole block, not each button disabled one by one: every one
                  // of them acts on fields that hold nothing, and the padlock, the paperclip and
                  // the overflow have no meaning on a message that is not on screen. The X in
                  // navigationIcon deliberately stays — it is the only way out.
                  if (reopen is DraftReopenView.Editor) {
                    // OpenPGP lock. SHORT tap keeps the three-stop cycle it always had (off →
                    // sign → encrypt → off, [nextPgpMode]); LONG press opens the list of all four
                    // modes, which is the only way to reach ENCRYPT_UNSIGNED — a short tap must
                    // not walk anyone into sending unsigned mail. The gesture is announced in the
                    // button's own contentDescription: nothing else on this screen tells anybody
                    // it exists. Only when the account is set up.
                    if (pgpAvailable) {
                        var pgpMenu by remember { mutableStateOf(false) }
                        // Box, not IconButton: an IconButton has no long press. The 48 dp size and
                        // the circular clip are what an IconButton was giving for free — the
                        // minimum touch target, and a ripple that is round rather than square.
                        // ⛔ `enabled = !sending` sits on the clickable, so NEITHER gesture acts
                        // while a send is in flight, as before.
                        Box {
                            Icon(
                                imageVector = pgpModeIcon(pgpMode),
                                contentDescription = stringResource(
                                    R.string.compose_pgp_toggle_desc,
                                    stringResource(pgpModeLabel(pgpMode)),
                                ),
                                // ⛔ The alpha is what IconButton gave for free. Neither gesture
                                // acts while a send is in flight, and a padlock at full strength
                                // would say the opposite (WYSIWYG).
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
                                PgpMode.entries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(pgpModeLabel(entry))) },
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
                    IconButton(onClick = { picker.launch("*/*") }, enabled = !sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                    }
                    IconButton(
                        onClick = attemptSend,
                        enabled = !sending && canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.compose_send))
                    }
                    // ⭐ FOUR SLOTS AT MOST, AND THE TITLE GETS THE REST (#164). This bar carried
                    // up to EIGHT 48 dp slots (X, padlock, paperclip, delete, save, schedule, send,
                    // overflow) and there was nothing left for its own title: on a narrow screen at
                    // a large font scale it was clipped in the middle of a word. Save, "send later"
                    // and delete are the three that read as WORDS rather than as glyphs, so they
                    // moved down here, above the receipt; the padlock, the paperclip and the send
                    // stay up because they are the gestures one aims at without reading. Each entry
                    // keeps the exact condition and the exact `enabled` rule its icon had — the
                    // move is a change of place, not of behaviour ([ComposeBarWiringTest] pins both
                    // whole-line). The receipt's wording still says what the app DOES (ask), never
                    // that a receipt will arrive: only the recipient's client decides that.
                    Box {
                        var moreMenu by remember { mutableStateOf(false) }
                        var scheduleMenu by remember { mutableStateOf(false) }
                        // The instant the preset list below is DRAWN for, stamped when the PRESETS
                        // open — ⛔ NOT when this overflow opens. Stamped at the ⋮, the whole time
                        // spent reading the menu is added to the presets' expiry: a lapsed entry
                        // reaches `ScheduledSends.enqueue`, the delay is coerced to zero, and the
                        // message goes out AT ONCE and irreversibly while the banner announces a
                        // schedule. ⚠ State, not a call in the composition: the wall clock is not
                        // snapshot state, so a tap recomposes nothing on its own (the free picker
                        // above says the same about its `enabled`). Re-stamping it is what redraws
                        // the menu after a refused tap and takes the lapsed entry off screen.
                        var menuOpenedAt by remember { mutableLongStateOf(0L) }
                        IconButton(onClick = { moreMenu = true }, enabled = !sending) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                        }
                        DropdownMenu(
                            expanded = moreMenu,
                            onDismissRequest = { moreMenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            // Encrypting can't carry plaintext to the server: no draft, no schedule.
                            // Same rule, same function as the leave dialog above (#35).
                            if (draftSaveAllowed(pgpMode)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_save_draft)) },
                                    onClick = { moreMenu = false; viewModel.saveDraft(to, cc, bcc, subject.text, rich, requestReceipt) },
                                    enabled = !sending && canSaveDraft,
                                )
                                // A scheduled send is fired by a headless worker that can't sign,
                                // and its table carries no attachments: disabled for a SIGN/ENCRYPT
                                // message or one with attachments, which would otherwise go out
                                // unsigned or amputated (A2/A3). The tap closes this menu and opens
                                // the presets beside it — ⛔ no submenu nested inside an overflow.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_schedule_send)) },
                                    onClick = { moreMenu = false; menuOpenedAt = System.currentTimeMillis(); scheduleMenu = true },
                                    enabled = !sending && canSend && scheduleSendAllowed(pgpMode, attachments.isNotEmpty()),
                                )
                            }
                            // Deleting the draft (#127). OUTSIDE the draftSaveAllowed block above: a
                            // delete persists nothing, so it has no reason to disappear when the
                            // padlock closes. When it is offered is [draftDeleteOffered]; the
                            // message it acts on comes from the ViewModel, which refuses while a
                            // send is in flight (INV-6).
                            if (draftDeleteOffered(restore, draftId, editingDraft != null, editingLocalDraftId != null)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_delete_draft)) },
                                    // Asks first (#127): everything typed since this composer opened
                                    // is unsaved, and on a server draft the delete takes the SERVER
                                    // copy — so the Undo restores the draft as it was on the server,
                                    // never the text on screen. On a draft the phone is keeping there
                                    // is no Undo at all (#95), which is what the dialog's own body
                                    // says. The composer already asks before dropping those edits
                                    // when the X is tapped; this entry must never delete outright.
                                    onClick = { moreMenu = false; viewModel.askDraftDelete() },
                                    enabled = !sending,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_request_receipt)) },
                                trailingIcon = { Checkbox(checked = requestReceipt, onCheckedChange = null) },
                                onClick = { moreMenu = false; requestReceipt = !requestReceipt },
                            )
                        }
                        // The presets, anchored to this same Box as the overflow: the two menus are
                        // siblings, never one inside the other.
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, shape = MaterialTheme.shapes.medium) {
                            // All three fields travel to the tap: the preset says WHICH KIND of
                            // promise the entry made, and `drawnAt` is the instant it was drawn for
                            // — which is the answer itself for the three ABSOLUTE entries ("6 PM",
                            // "tomorrow 8 AM"), and only what the relative one used to be worth.
                            schedulePresets(context, menuOpenedAt).forEach { (preset, label, drawnAt) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        // ⚠ Decided AGAIN at the tap, against a fresh clock — the
                                        // same two reads as the free picker above. The composer can
                                        // sit in the background for minutes with this menu up: an
                                        // entry drawn at 5:55 PM still reads "This evening, 6 PM"
                                        // when tapped at 6:05, and that lapsed instant reaching
                                        // `ScheduledSends.enqueue` is coerced to a zero delay — the
                                        // message goes out AT ONCE, irreversibly, while the banner
                                        // announces a schedule. ⛔ Deciding is not re-computing:
                                        // only "In 1 hour" is re-read, the three absolute entries
                                        // keep the instant they were drawn for or are refused —
                                        // re-computing them would move them a day at midnight. The
                                        // rule is [presetMillisAtTap], pure and unit-tested.
                                        val sendAt = presetMillisAtTap(preset, drawnAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                                        // ⛔ Closing, scheduling and confirming happen together or
                                        // not at all. On the refused path the menu STAYS UP and is
                                        // redrawn from a new stamp: the lapsed entry vanishes under
                                        // the finger, and that is the message. Deliberately no new
                                        // string — the free picker refuses silently too, and nine
                                        // translations do not buy a window a few seconds wide.
                                        if (sendAt != null) {
                                            scheduleMenu = false
                                            // ⛔ Scheduled AND confirmed, or neither: the view model
                                            // answers `false` on the refusals it owns (PGP and
                                            // attachments, and a body this composer LOST), and the
                                            // banner below is a sentence about a message that would
                                            // not have been scheduled at all.
                                            if (viewModel.scheduleSend(to, cc, bcc, subject.text, rich, sendAt, requestReceipt)) {
                                                // The LABEL, true of what was just scheduled: "In 1
                                                // hour" is an hour from this tap, and "6 PM" is the
                                                // 6 PM the entry was drawn for, or nothing left.
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.compose_scheduled_toast, label),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } else {
                                            menuOpenedAt = System.currentTimeMillis()
                                        }
                                    },
                                )
                            }
                            // Last, under the presets, as Gmail does it: choose the instant
                            // yourself (#161). Nothing is decided here — the two dialogs it opens
                            // hold the refusal of an instant already gone.
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
        // ⭐ The editor is drawn or it is not. Every rememberSaveable above (`applied` first of
        // all), and both LaunchedEffects that apply the prefill, stay OUTSIDE this `when` and
        // unconditional: the success path must land exactly as it does today, and `applied` must
        // keep surviving rotation and process death. Only the drawing moves.
        when (reopen) {
          // Still reading the draft. No sentence: a spinner already says "wait", and a line of text
          // under it would be read as a verdict on a fetch that has not answered.
          DraftReopenView.Waiting -> LoadingRing(Modifier.align(Alignment.Center))
          // Read and failed. One sentence and the X, nothing else: there is no draft to edit, and
          // an editor here would invite a save that adds a second draft beside the first.
          is DraftReopenView.DeadEnd -> Text(
              text = stringResource(reopen.notice),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              // No maxLines: German and Russian run to three or four lines here, and clipping the
              // sentence would leave the only explanation on the screen half-said.
              modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
          )
          DraftReopenView.Editor ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The header stays put and the body fills the space above the keyboard; the body
                // owns its own scroll, so writing a long message keeps the cursor in view (#26).
                // Pad the bottom by whichever is taller — the keyboard or the nav bar — so the
                // body sits flush on the keyboard when it's open (the ime inset already spans the
                // nav-bar area) and above the nav bar when it's closed, with no double inset (#26).
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .graphicsLayer {
                    translationY = -fly * 64.dp.toPx()
                    alpha = 1f - fly
                },
        ) {
          // The header (recipients + subject + attachments) sits on a faint tint so the writing
          // area below reads as a distinct zone; its dividers run full width (edge to edge).
          Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))) {
            // From — switch sending account / identity (only when there's a choice).
            if (fromOptions.size > 1) {
                var fromMenu by remember { mutableStateOf(false) }
                Box {
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
                        // An IconButton (not a bare Icon) so this chevron lines up with the To
                        // field's expand chevron, which is also an IconButton.
                        IconButton(onClick = { fromMenu = true }) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.compose_choose_sender))
                        }
                    }
                    DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }, shape = MaterialTheme.shapes.medium) {
                        fromOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.identity.display()) },
                                onClick = {
                                    // Changing "From" makes the signature follow the identity (D5):
                                    // the block is swapped while it is still there verbatim, an
                                    // edited or deleted one is left as the user made it, and an
                                    // identity that had none at all gets the new signature inserted
                                    // where the prefill would have put it — before the quote when
                                    // there is one. The caret keeps its offset, and the
                                    // unsaved-changes baseline is rewritten the same way, so
                                    // switching identity alone never counts as "dirty".
                                    scope.launch {
                                        // The quoted original this compose opened with: the reply
                                        // baseline, and nothing for a new mail or a forward (whose
                                        // original is carried at send time, not in the body).
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
                                        // The rewrite works on the TEXT; the user's styling is
                                        // carried across it by diffing (#131), so a bold word
                                        // above the signature stays bold and stays put.
                                        rewriteRichBody(RichBody(body.text, ranges, blocks), body.selection.start, rewrite)?.let { rewritten ->
                                            body = TextFieldValue(
                                                rewritten.text,
                                                TextRange(body.selection.start.coerceAtMost(rewritten.text.length)),
                                            )
                                            ranges = rewritten.ranges
                                            blocks = rewritten.blocks
                                            rewriteRichBody(RichBody(initialBody, baselineRanges, baselineBlocks), 0, rewrite)?.let {
                                                initialBody = it.text
                                                baselineRanges = it.ranges
                                                baselineBlocks = it.blocks
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
            RecipientChipsField(
                label = stringResource(R.string.compose_to),
                value = to,
                onValueChange = { to = it },
                suggestions = suggestions,
                onSuggest = viewModel::suggest,
                onClearSuggestions = viewModel::clearSuggestions,
                focusRequester = toFocus,
                // Whenever the recipients still have to be typed: a fresh mail and a forward. A
                // reply and a reopened draft already have them, and focus the body instead (#63);
                // a mailto: link has them too, and focuses the first field it left empty (#83).
                autoFocus = initialFocus == ComposeFocus.RECIPIENTS,
                missingKey = missingKeyFor,
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
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
                )
                RecipientChipsField(
                    stringResource(R.string.compose_bcc), bcc, { bcc = it }, suggestions,
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
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
            // Only when the body is actually being encrypted to someone (a recipient has a key),
            // so a fresh encrypt-by-default compose with no recipients doesn't claim it yet (#35).
            if (pgpMode.encrypts && recipientKeys.values.any { it }) {
                Text(
                    stringResource(R.string.compose_pgp_subject_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Encrypt-by-default couldn't encrypt because these recipients have no key: say so and
            // name them, so it's clear why the message won't be encrypted (Codeberg #35).
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

            if (attachments.isNotEmpty()) {
                // Same as the recipient chips: cap the attachment list and scroll it so many files
                // don't grow the header without limit (#26 follow-up).
                Column(
                    Modifier
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    attachments.forEach { att ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = att.name ?: stringResource(R.string.compose_attachment_fallback),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.removeAttachment(att) }) {
                                Text(stringResource(R.string.compose_remove))
                            }
                        }
                    }
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
                    text = stringResource(
                        if (it.whileSaving) R.string.compose_could_not_save else R.string.compose_could_not_send,
                        it.message,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
          }

            // Body — no frame, fills the rest of the width and grows with content.
            // ⚠ `body` holds PLAIN text; the styling is laid over it here at render time and the
            // value handed back by the field is stored without its spans (#131). Selection and
            // composition are `body`'s own, or the IME loses its composing region on every frame.
            BasicTextField(
                value = body.copy(annotatedString = toAnnotatedString(rich)),
                onValueChange = { next ->
                    // ⛔ Read HERE, not `rich`: that one is captured at composition, and two
                    // callbacks before a recomposition would remap from a stale body.
                    val current = RichBody(body.text, ranges, blocks)
                    if (next.text != body.text) {
                        // One edit, whatever produced it: the styling follows the text by diffing,
                        // and so do the lists — ONE remap, both halves of its answer taken. A
                        // second call would diff the same edit twice and could answer differently.
                        val remapped = remapAfterEdit(current, next.text, span(body.selection), span(next.selection), pending)
                        ranges = remapped.ranges
                        blocks = remapped.blocks
                    } else if (next.selection != body.selection) {
                        // The caret moved: a style armed for the next keystroke no longer applies.
                        pending = null
                    }
                    body = TextFieldValue(next.text, next.selection, next.composition)
                },
                // ⛔ The list markers are drawn HERE and nowhere else (#131): in the text layer, by
                // a transformation, so not one of their characters enters `body.text`. The mapping
                // that comes with it is what keeps the caret, the selection and the IME in step —
                // see [markedText].
                visualTransformation = markerTransformation(blocks),
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Capitalisation ONLY: no imeAction here on purpose, because the body is the only
                // multi-line field of the screen and an action would take the newline's place (#26).
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    // Fills all the space below the header (so the whole area under it is the body's
                    // tap target) with a bounded height, so the field scrolls its own content and
                    // keeps the cursor in view as you write (#26).
                    .weight(1f)
                    .focusRequester(bodyFocus)
                    .onFocusChanged { bodyFocused = it.isFocused },
                decorationBox = { inner ->
                    // The margins live INSIDE the field, not around it (#26). The header rows got
                    // the caret rule in 1.3.13: a tap before the first character of a line means
                    // "put the cursor at the start of that line", which otherwise takes
                    // pixel-perfect aim just left of the first glyph. The body needs the same, and
                    // padding the field from the OUTSIDE kept that margin out of its touch area,
                    // so the tap did nothing at all. Padding from the inside hands the margin to
                    // the field, which already resolves such a tap itself: it is coerced into the
                    // visible text and lands at the start of the tapped line, its own scrolling
                    // and RTL included. A tap on the text is unchanged, no gesture is intercepted
                    // and nothing new listens for one, so the body's vertical scrolling (#5, #6,
                    // #10) is routed exactly as before.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp, bottom = 16.dp),
                    ) {
                        // ⛔ …and no list either: a bullet tapped on an empty composer is drawn
                        // at the start of the first line, exactly where the placeholder sits, and
                        // the two overprint each other until the first keystroke.
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
            // The formatting bar, only while the body has the focus (#131). ⛔ No `imePadding()`
            // and no inset of its own: the Column above already pads for the keyboard or the nav
            // bar, whichever is taller, and a second inset here would double it (#26).
            if (bodyFocused) {
                FormattingBar(
                    active = pending ?: stylesAt(rich, span(body.selection)),
                    activeList = listKindAt(rich, span(body.selection)),
                    onToggle = { kind ->
                        val current = RichBody(body.text, ranges, blocks)
                        val sel = span(body.selection)
                        if (!sel.isEmpty) {
                            ranges = toggleFormatting(current, sel, kind).ranges
                        } else {
                            pending = (pending ?: stylesAt(current, sel)).let { if (kind in it) it - kind else it + kind }
                        }
                    },
                    onToggleList = { kind ->
                        // ⚠ No `pending` and no empty-selection branch, unlike the four families
                        // above: a block is a run of LINES, so the caret alone already names one
                        // (`toggleBlock`), and the tap applies at once — the gesture in Gmail.
                        val current = RichBody(body.text, ranges, blocks)
                        blocks = toggleBlock(current, span(body.selection), kind).blocks
                    },
                    onClear = {
                        val current = RichBody(body.text, ranges, blocks)
                        val sel = span(body.selection)
                        if (!sel.isEmpty) ranges = clearFormatting(current, sel).ranges else pending = emptySet()
                    },
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
 * The dialog BOTH "pick date and time" pickers wear on their typed face (#161), where the software
 * keyboard is up: the clock always, the calendar in landscape. Material has no dialog that holds
 * with a keyboard over it — `AlertDialogContent` gives its `text` slot neither a scroll nor a
 * weight, so the picker takes the height it wants and the actions spill off the bottom.
 *
 * ⛔ Measured on `emu` in landscape at 914×411 dp with the CLOCK in Material's `AlertDialog`,
 * 2026-08-21: the `ime` inset covered [132,502][2400,1080], the platform SLID the window 167 px —
 * just enough to clear the focused field — and stopped there, leaving the Cancel/OK row announced
 * at y=531..584, under the top of the keys at y=502. ⛔ The tap did not miss in silence either:
 * touching `OK` typed a digit into the hour field (02 became 03) instead of confirming. With the
 * keyboard closed everything was correct again.
 *
 * Three lines carry the fix, and none of them works alone:
 *  - `decorFitsSystemWindows = false`: with the default the platform slides the window and stops at
 *    the focused field; at `false` there is no sliding left and the keyboard becomes an INSET.
 *    ⚠ On Android 12+ this is the only mechanism there is: `DialogWrapper.updateParameters` sets a
 *    `softInputMode` only below API 31.
 *  - `safeDrawingPadding()`: what SUBTRACTS that inset. The window stays `wrap_content` and
 *    CENTRED, so taking the keyboard's height off the bottom lifts the dialog above the keys by
 *    itself.
 *  - `weight(1f, fill = false)` on the picker's box and NOTHING on the action row: in a `Column`,
 *    the children WITHOUT a weight are measured FIRST. The row can therefore no longer be pushed
 *    off screen, whatever height is left over, and it is the picker that scrolls instead of being
 *    cut.
 *
 * ✓ Measured green with the keyboard up, same bench, same day: the action row at y=314..440
 * against keys at y=502, the picker scrolling once the height ran out, and `OK` confirming instead
 * of typing a digit.
 *
 * ⛔ [picker] is composed inside a `verticalScroll`, i.e. in INFINITE height: nothing lazy and
 * VERTICAL may be handed to it. A `LazyVerticalGrid` measured that way throws, which is why the
 * calendar's grid face keeps `DatePickerDialog` and is guarded out of this shell.
 *
 * ⚠ The second slot is [buttons] and NOT `actions`: `DraftReopenWiringTest` pins the gate on the
 * top bar by taking the FIRST `actions = {` line of this file, and a second one declared above it
 * makes that lint read this dialog instead — which is exactly what it did, once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchedulePickerDialog(
    onDismissRequest: () -> Unit,
    picker: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .align(Alignment.CenterHorizontally)
                        .verticalScroll(rememberScrollState()),
                ) {
                    picker()
                }
                // ⛔ A FLOW row, not a plain one, because Material's `AlertDialogFlowRow` —
                // which this shell replaces — wraps its actions onto a second line. At 360 dp
                // with the font scaled up, "Abbrechen" or "Annuleren" beside "OK" does not fit
                // on one line, and a plain Row would put OK off the edge instead of under it.
                // ⚠ And the SECOND line needs air under the first: `AlertDialogFlowRow` spaces
                // its rows by 12 dp, a `FlowRow` by nothing at all. Left out, the two buttons
                // touch on exactly the screens that make them wrap — German or Dutch at 360 dp
                // with the font scaled up — and read as one block.
                FlowRow(
                    modifier = Modifier.align(Alignment.End).padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    buttons()
                }
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
    // Tapping anywhere on the row (not just the thin input) focuses the field, so the whole
    // line is the tap target (#26).
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    // Before the first character (here: the row's left inset) the caret goes to the
                    // very start; anywhere else the field keeps the caret it had (#26).
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
 * A recipient line that shows committed addresses as chips (invalid ones flagged in
 * the error colour) plus an inline editor with autocomplete. The field stays backed
 * by a single comma-joined [value] string, so send / draft / schedule are unchanged:
 * everything before the last separator is a committed chip; the trailing token is the
 * still-editing input. A comma, semicolon or space commits the token to a chip;
 * Backspace on an empty input removes the last chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientChipsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactSuggestion>,
    onSuggest: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** Open and focus the field on first composition (the To field on a fresh compose). */
    autoFocus: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** When encrypting, flags a recipient with no available public key. */
    missingKey: (String) -> Boolean = { false },
) {
    val (chips, input) = splitRecipients(value)

    fun rebuild(newChips: List<String>, newInput: String) = joinRecipients(newChips, newInput)

    // Expanded shows the editable chip area with the input; collapsed shows chips only (or a
    // one-line "+N" summary) with no empty input line, so tapping another field leaves no unused
    // space. Start expanded only for a fresh empty field so it can auto-focus (#26 follow-up).
    var expanded by remember { mutableStateOf(false) }
    // Guards against the input's initial onFocusChanged(false) collapsing the field before the
    // focus request lands: only a loss AFTER a real gain collapses it.
    var wasFocused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    // The inline input carries its caret (a TextFieldValue), so a tap before its first character can
    // put the cursor at the start (#26). The parent string stays the single source of truth: as soon
    // as the derived [input] differs, the field is rebuilt around it with the caret at the end.
    var inputState by remember { mutableStateOf(TextFieldValue()) }
    // The field's on-screen width, so the suggestion menu can float directly under it at the same
    // width, instead of pushing the subject and body down as an inline list did. Its position comes
    // from the popup's own anchor bounds; only the width has to be measured here.
    var fieldWidthPx by remember { mutableStateOf(0) }
    // …and its bottom edge inside the compose root, plus the root's own height, so the menu can be
    // capped at the room actually left below the field once the keyboard is up (#143). On API 30+
    // the window is NOT resized for the IME (MainActivity goes edge to edge), so the keyboard is
    // known here only as an inset and nothing else in this field knows it took the bottom half of
    // a small screen. On API 26-29 that is NOT established: the manifest still carries
    // adjustResize, the root may shrink as well, and the keyboard would then be counted twice —
    // no menu at all instead of a short one. The reporter's device is API 27; the bench (Moto G,
    // API 28) is what settles it. See SuggestionMenuFit.kt.
    var fieldBottomPx by remember { mutableStateOf(0) }
    var windowHeightPx by remember { mutableStateOf(0) }
    val inputValue = if (inputState.text == input) {
        inputState
    } else {
        TextFieldValue(input, TextRange(input.length))
    }
    // Enter validates the address being typed, exactly as a comma does, and the field keeps the
    // focus so the next recipient can follow straight away — the keyboard stays open and nothing
    // moves on to the next field (#83). An empty input is left alone: Enter only ever commits
    // what is in front of it, it never grows a second meaning.
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
    // Follow the growing chip area so the blinking input stays in view as recipients are added.
    // Instantly, not animated (#94): sliding the chips into place is the one moving part of this
    // field, it plays whenever an address is committed — including on the way out, when leaving for
    // the subject commits what was typed — and it shows nothing the jump doesn't.
    LaunchedEffect(chipScroll.maxValue) { if (expanded) chipScroll.scrollTo(chipScroll.maxValue) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    expanded = true
                    // An already-expanded field doesn't re-run the effect below, so ask here too —
                    // this is the case where a tap on the label used to do nothing at all (#26).
                    runCatching { focus.requestFocus() }
                    // On the label, or in the empty space left of what is being typed: caret to the
                    // start of that text. Committed chips aren't text, and an empty input has no
                    // start to aim at, so both leave the caret alone.
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
                        chips.first(),
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
                // Cap the chip area (~2.5 lines) and scroll it, so adding many recipients doesn't
                // grow the field without limit and eat the message body (#26 follow-up).
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 104.dp)
                    .verticalScroll(chipScroll)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                chips.forEachIndexed { index, chip ->
                    // Invalid address OR (while encrypting) no key for it → flagged.
                    val valid = isValidEmail(chip) && !missingKey(chip)
                    InputChip(
                        selected = false,
                        // A tap hands the address back as plain text (#94): it leaves the chip row
                        // for the input, where the field opens it with the caret at its end, and a
                        // second tap puts the caret exactly where the typo is — the way it worked
                        // before addresses were committed to chips. Two taps, and nothing hidden:
                        // one gesture, one meaning, no double-tap timing to guess at. Whatever was
                        // half-typed is committed rather than lost, see [recipientsWithChipEdited].
                        onClick = {
                            expanded = true
                            inputState = TextFieldValue()
                            onValueChange(recipientsWithChipEdited(value, index))
                            onClearSuggestions()
                            runCatching { focus.requestFocus() }
                        },
                        label = { Text(chip, maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.compose_remove),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onValueChange(rebuild(chips.filterIndexed { i, _ -> i != index }, input)) },
                            )
                        },
                        colors = if (valid) {
                            InputChipDefaults.inputChipColors()
                        } else {
                            InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        // No elevation, and therefore none of Material's interaction-driven
                        // elevation animation (#94). An input chip's elevations are all 0dp anyway,
                        // so this changes nothing on screen — it just stops an animated shadow from
                        // ever being computed for a chip that is a flat outline by design.
                        elevation = null,
                    )
                }
                // The input exists only while expanded, so an unfocused field shows no empty input
                // line (and no stray blinking cursor) — it collapses to its chips.
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
                    // Enter now commits the address instead of merely closing the keyboard, which
                    // is all it did before (#83). The action is named here rather than left to
                    // `singleLine`'s implicit one, because a hardware Enter is routed to THIS
                    // action (the field is single-line, so Enter is no line break) and an unnamed
                    // one does nothing at all. Not calling the default action is what keeps the
                    // keyboard up and the focus in the field.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier = Modifier
                        .headerTapText(geometry)
                        // Fill the rest of the line so a tap in the empty area past the last chip
                        // lands the caret at the end; wrap to a new line once space runs short.
                        .weight(1f)
                        .widthIn(min = 90.dp)
                        .padding(vertical = 6.dp)
                        .onFocusChanged { fs ->
                            if (fs.isFocused) {
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
        val density = LocalDensity.current
        // The keyboard is read HERE, in the field's own composition, and never inside the Popup: a
        // Popup is a separate window and its insets are not the screen's. Reading it as an inset is
        // the only way to know the keyboard is up at all, since the window itself is not resized
        // (edge to edge) — the same read ConnectScreen makes to lift its credential block.
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        // The height the menu may take, or null when there is not even one row of room left under
        // the field — in which case no menu is drawn at all (#143). Keyed on every quantity that
        // moves: the keyboard's height changes without any of this composable's own state
        // changing, and an unkeyed remember would keep serving the pre-keyboard cap.
        val menuMaxHeight = remember(windowHeightPx, fieldBottomPx, imeBottomPx, density) {
            suggestionMenuMaxHeight(
                windowHeightPx = windowHeightPx,
                fieldBottomPx = fieldBottomPx,
                imeHeightPx = imeBottomPx,
                density = density,
            )
        }
        if (expanded && suggestions.isNotEmpty() && menuMaxHeight != null) {
            // A floating contextual menu, not an inline list: it hangs under the field and over
            // whatever is below, so the subject and body never shift as suggestions appear or vanish
            // (the inline list used to push them down). Positioned at the field's bottom-left by the
            // popup's own anchor bounds, and sized to the field's measured width. Not focusable, so
            // the keyboard stays up and each keystroke keeps filtering; it closes when a row is
            // picked, when the field loses focus (which collapses it), or on a tap outside.
            val below = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
                }
            }
            Popup(
                popupPositionProvider = below,
                onDismissRequest = onClearSuggestions,
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = if (fieldWidthPx > 0) {
                        Modifier.width(with(density) { fieldWidthPx.toDp() })
                    } else {
                        Modifier
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    // Cap the menu at the room measured under the field with the keyboard up, and
                    // let it scroll on its own — a lower cap therefore loses no suggestion, it only
                    // stops the list from covering the keys that filter it. Items stack with a
                    // divider so they read as distinct rows.
                    Column(
                        Modifier
                            .heightIn(max = menuMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEachIndexed { index, contact ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                            // Picture on the left when the address book has one, monogram otherwise —
                            // the slot is the same size either way, so rows keep their height while
                            // photos decode and the list never jumps under the finger.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(rebuild(chips + contact.email, ""))
                                        onClearSuggestions()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(
                                    email = contact.email,
                                    name = contact.name,
                                    photoUri = contact.photoUri,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.name ?: contact.email,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (contact.name != null) {
                                        Text(
                                            contact.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where a header row was last pressed and where its editable text starts — the two things needed to
 * tell a tap on the label (or on the empty space before the first character) from a tap on the text
 * itself. Filled in by [headerTapRow] and [headerTapText], read by the row's click handler, never
 * during composition: plain fields, no snapshot state.
 */
private class HeaderTapGeometry {
    /** Last press, in the row's coordinates. */
    var pressX: Float = Float.NaN

    /** Row and editable-text origins in root coordinates; their difference is row-local. */
    var rowX: Float = Float.NaN
    var textX: Float = Float.NaN

    /** The caret a tap should force in a field holding [textLength] characters, or null for none. */
    fun caretFor(textLength: Int): Int? = headerTapCaret(pressX, textX - rowX, textLength)
}

/**
 * The whole header row is the field's tap target (#26). Watches the pointer on the way down
 * WITHOUT consuming it, so a tap that lands on the text is still handled by the field exactly as
 * before (caret under the finger, selection, handles), and records where it landed so [onTap] can
 * put the caret at the start of the text when the tap fell before it. Still a plain `clickable`,
 * so what TalkBack sees of the row is unchanged.
 */
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
    // A fixed, tight width so From / To / Cc / Bcc all line up and the input starts just past
    // the label (K-9 style). Narrower than before so the label sits closer to the left and the
    // input follows tightly; still wide enough for the short field labels across locales.
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
}

/**
 * The seven formatting buttons under the body (#131): the four inline families, the two lists,
 * then "clear". Placed DOWN HERE on purpose: `DraftReopenWiringTest` reads the FIRST `actions = {`
 * line of this file as the top bar (nothing here declares one), and `ScheduleMenuWiringTest` reads
 * `schedulePresets` below to the END of the file, so nothing may follow it. ⚠ Nothing focusable
 * beyond the buttons themselves: a bar that takes the focus hides itself.
 */
@Composable
private fun FormattingBar(
    active: Set<Inline>,
    activeList: BlockKind?,
    onToggle: (Inline) -> Unit,
    onToggleList: (BlockKind) -> Unit,
    onClear: () -> Unit,
) {
    // ⛔ SCROLLABLE, since the bar went from five buttons to seven: 7 × 48 dp + 8 = 344 dp of fixed
    // width. It fits a 360 dp phone, and it does NOT fit a side-by-side multi-window pane (this
    // screen is rebuilt by exactly that resize, see the token comment above) nor a large "display
    // size" setting — where the last button, "clear formatting", would simply be off the screen
    // with no way to reach it. Scrolling is also what Gmail's own bar does when it runs out of room
    // (rule 4). ⚠ A scroll container, not a focusable one: the bar must never take the focus, or it
    // hides itself.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        FormattingButton(Inline.BOLD, Icons.Filled.FormatBold, R.string.compose_format_bold, active, onToggle)
        FormattingButton(Inline.ITALIC, Icons.Filled.FormatItalic, R.string.compose_format_italic, active, onToggle)
        FormattingButton(Inline.UNDERLINE, Icons.Filled.FormatUnderlined, R.string.compose_format_underline, active, onToggle)
        FormattingButton(Inline.STRIKE, Icons.Filled.FormatStrikethrough, R.string.compose_format_strikethrough, active, onToggle)
        ListButton(BlockKind.BULLET, Icons.Filled.FormatListBulleted, R.string.compose_format_list_bulleted, activeList, onToggleList)
        ListButton(BlockKind.NUMBER, Icons.Filled.FormatListNumbered, R.string.compose_format_list_numbered, activeList, onToggleList)
        IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.FormatClear, contentDescription = stringResource(R.string.compose_format_clear))
        }
    }
}

/** One list's button, built exactly like [FormattingButton]: same 48 dp, same `selected`, same
 *  tint. `activeList` is the kind covering the whole of the selection (or the caret's line), so at
 *  most one of the two is ever lit — a run of lines is of ONE kind. */
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
 * Quick "send later" presets → (preset, label, epoch-millis) for a menu drawn at [nowMillis]. The
 * zone is read here and the decision itself is [schedulePresetsAt]'s, which is pure and unit-tested:
 * this only resolves the labels, so the menu can never show an entry the rule dropped.
 *
 * ⛔ The clock is an ARGUMENT and never read in this body. The caller stamps it when the menu opens
 * and holds it in state, so a refused tap can re-stamp it and have the list redrawn; a clock read
 * here would instead tie the shown list to recomposition, which a tap does not cause.
 *
 * All three fields reach the tap, which decides with [presetMillisAtTap]: the preset says whether
 * the entry promised something RELATIVE or ABSOLUTE, and the millis is the instant it was drawn for
 * — the answer itself for the absolute ones, and what a refusal is measured against.
 */
private fun schedulePresets(context: android.content.Context, nowMillis: Long): List<Triple<SchedulePreset, String, Long>> =
    schedulePresetsAt(nowMillis, java.time.ZoneId.systemDefault())
        .map { (preset, millis) ->
            val label = when (preset) {
                SchedulePreset.IN_1_HOUR -> R.string.schedule_in_1_hour
                SchedulePreset.THIS_EVENING -> R.string.schedule_this_evening
                SchedulePreset.TOMORROW_MORNING -> R.string.schedule_tomorrow_morning
                SchedulePreset.TOMORROW_EVENING -> R.string.schedule_tomorrow_evening
            }
            Triple(preset, context.getString(label), millis)
        }
