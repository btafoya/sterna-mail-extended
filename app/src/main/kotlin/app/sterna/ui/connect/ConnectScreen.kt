package app.sterna.ui.connect

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.sterna.R
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.withLoginHint
import app.sterna.ui.components.AppPasswordHelpLink
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.PendingImportAccountsSection
import app.sterna.ui.components.autofill
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.settings.SettingsViewModel
import app.sterna.ui.settings.applyAppLanguage
import app.sterna.util.isValidEmail
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    firstRun: Boolean = false,
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importSignIn by viewModel.importSignIn.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ConnectState.Connected) onConnected()
    }
    // After importing accounts, we sign into each one first; only then enter the app.
    LaunchedEffect(importSignIn) {
        if (importSignIn is ConnectViewModel.ImportSignIn.Done) onConnected()
    }
    // Resume the per-account password prompts if we arrive here with imported, still-unauthenticated
    // accounts (e.g. the app was killed mid-sign-in) so the user finishes signing in, not a dead inbox.
    LaunchedEffect(Unit) { viewModel.resumeImportSignIn() }

    var protocol by rememberSaveable { mutableStateOf(MailProtocol.JMAP) }
    // JMAP only: authenticate with a server-generated API token (Bearer) instead of a password.
    var useApiToken by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Which step of the add-account walk the user has reached. It survives a rotation AND process
    // death, like every field on this screen — but the verdict it depends on does not, which is
    // why what is DRAWN is [stepToRender]'s answer and never this value.
    var rememberedStep by rememberSaveable(stateSaver = ConnectStepSaver) {
        mutableStateOf(ConnectStep.ADDRESS)
    }
    // The address step's button was tapped while the probe was still out: leave as soon as it
    // answers, and not one moment before — deciding early means deciding "manual", i.e. putting
    // the protocol question back on screen.
    var awaitingVerdict by rememberSaveable { mutableStateOf(false) }

    // Autodiscovery couldn't find the server → reveal the manual server field.
    LaunchedEffect(state) {
        if (state is ConnectState.NeedsServer) showAdvanced = true
    }

    // Quick setup and the server fields it fills are one piece of state, so the chips, the fields
    // and the Connect button can never disagree about which provider is selected (#105).
    var preset by rememberSaveable(stateSaver = PresetFormSaver) { mutableStateOf(PresetForm.NONE) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // The provider help link below leaves for a browser; a second tap while it comes up opened a
    // second one. This screen is composed above the navigation graph at first run, so the guard
    // hangs off the activity's lifecycle there and off the settings entry when it is reached from
    // Accounts → Add account — see [rememberLeaveOnce].
    val leaveOnce = rememberLeaveOnce()

    // Focusing any credential field scrolls the WHOLE credential block (account name, email,
    // password/token + the action buttons) above the keyboard, not just the focused field, so the
    // next field is reachable without dismissing the keyboard (#52 follow-up). The per-field
    // bring-into-view Material already does only guarantees the focused field itself.
    val credentialReveal = remember { CredentialBlockReveal() }
    // The focus event fires before the keyboard is up, so the reveal computed then uses the
    // pre-IME viewport. Re-run it as the IME inset settles: collectLatest retargets the scroll on
    // each animation frame and the last emission positions the block against the final viewport.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collectLatest { bottom -> if (bottom > 0) credentialReveal.reveal() }
    }
    // The foot of the column has its own requester, on a container that exists whether or not
    // there is anything to say — the block only speaks when something went wrong, and the keyboard
    // is up exactly then (she has just typed her address). See the effect at the foot of the column.
    val statusReveal = remember { BringIntoViewRequester() }

    // First-run only: import a settings backup here, since Settings is unreachable before an
    // account exists. A backup that carries account configuration recreates those accounts
    // (without passwords) and drops the user straight into the app to sign in; a preferences-only
    // backup just applies the preferences and leaves the user on this screen to add an account.
    val settingsViewModel: SettingsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun snackbar(message: String) = scope.launch { snackbarHostState.showSnackbar(message) }
    fun dismissWithUndo(account: app.sterna.core.data.account.StoredAccount) {
        viewModel.dismissImportAccount(account.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImportAccount(account)
        }
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importSettings(
                uri,
                onResult = { ok, accountsAdded ->
                    when {
                        // Accounts imported inert → the "accounts to sign in" list appears; no snackbar needed.
                        ok && accountsAdded > 0 -> viewModel.beginImportSignIn()
                        ok -> snackbar(context.getString(R.string.connect_import_no_accounts))
                        else -> snackbar(context.getString(R.string.connect_import_invalid))
                    }
                },
                onLanguageChanged = { applyAppLanguage(it) },
            )
        }
    }
    // K-9 / Thunderbird `.k9s` export: parsed (not by extension — the files carry no MIME type),
    // its accounts imported inert, then the "accounts to sign in" list appears.
    val importK9Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importK9Settings(uri) { ok, added, skipped, unverified ->
                when {
                    ok && added > 0 -> {
                        val imported = if (skipped > 0) {
                            context.getString(R.string.import_snackbar_imported_with_skipped, added, skipped)
                        } else {
                            context.getString(R.string.import_snackbar_imported, added)
                        }
                        // The file did not state a connection security we map for some accounts, so
                        // theirs is a safe guess: say so rather than let it pass for what K-9 used.
                        snackbar(
                            if (unverified > 0) {
                                imported + " " +
                                    context.getString(R.string.import_snackbar_check_security, unverified)
                            } else {
                                imported
                            },
                        )
                        viewModel.beginImportSignIn()
                    }
                    ok -> snackbar(context.getString(R.string.connect_import_k9_none))
                    else -> snackbar(context.getString(R.string.connect_import_invalid))
                }
            }
        }
    }

    // Where Connect goes and whether it has enough to go there — one decision, shared by the
    // button's label, its enabled state and the secret field's meaning.
    val route = connectRoute(preset, protocol, useApiToken, server)
    val ready = connectReady(
        route, username, password,
        preset.imapHost, preset.imapPort, preset.smtpHost, preset.smtpPort,
    )

    // Autoconfig cascade under this walk: with an address typed and nothing hand-entered,
    // discovery sets out in the background (the ViewModel gates it — armed chip, OAuth, offline or
    // a bad address all keep it home). What it finds lands ONLY in the visible fields, via
    // [presetFilledWith]; Connect keeps reading the fields and nothing else (WYSIWYG).

    // The DURABLE verdict, and the one the walk steps on — never the one-shot fill signal below.
    // [verdictFor] is what makes it honest: a verdict about the address the user has since changed
    // answers nothing here.
    val verdict by viewModel.discovery.collectAsStateWithLifecycle()
    val found = verdictFor(verdict, username) as? MailAutoconfigResult.Found
    // How the server says this account signs in, and therefore what step 2 may ask for. ⛔ It is
    // the server's answer, not a choice offered to the reader: OAuth used to be a button in the
    // manual fallback, so whoever needed it had to know the word and go looking for it.
    //
    // ⛔ READ ABOVE THE STEP, because the step now depends on it: a domain served over OAuth with
    // no autoconfig document reaches the credentials step on this answer alone (#55).
    val oauthVerdict by viewModel.oauthDiscovery.collectAsStateWithLifecycle()
    val ask = credentialsAsk(oauthVerdict, username)

    // ⛔ The step DRAWN, not the step remembered: after process death the ViewModel comes back
    // empty, and a credentials page for a server nobody discovered shows a state that is not true
    // and offers a button that leads nowhere. Both answers count: withhold the OAuth one here and
    // the credentials step reached by it is bounced back to the address step as it arrives.
    val walkStep = stepToRender(rememberedStep, found, oauthVerdict, username)

    // What the credentials step needs before it may run: the shipped rule, on the route that step
    // really takes (JMAP with a password), never on whatever the manual form's chips say.
    val credentialsReady = connectReady(
        ConnectRoute.JMAP_AUTODISCOVER, username, password,
        preset.imapHost, preset.imapPort, preset.smtpHost, preset.smtpPort,
    )

    // Whether this screen has already handed this address over to a browser sign-in, and for WHICH
    // address — the one fact that tells "nothing has started yet" from "she cancelled it". Both are
    // ConnectState.Idle, and the difference decides whether the step draws a wheel or an invitation,
    // and whether the automatic trigger may fire at all. Saved, so a rotation does not restart the
    // sign-in she just cancelled.
    var handoverFor by rememberSaveable { mutableStateOf(NO_HANDOVER) }
    val handedOver = handoverStarted(handoverFor, username)
    // The ONE way this screen starts a hand-over — the automatic trigger below and the button of
    // [CredentialsPane.SIGN_IN_OFFER] both come through here, so neither can start one without
    // recording that it did.
    fun startHandover() {
        handoverFor = username.trim()
        viewModel.connectOAuth(username, NO_TYPED_SERVER, accountName)
    }

    // The one place the discovery triggers agree on what they hand the guard. ⛔ Hosts a verdict
    // about ANOTHER address filled in go first: left in place they are what makes the guard refuse,
    // and the address step would then wait for a probe that can never run.
    fun probeAddress() {
        preset = presetForAddress(preset, verdict, username, walkStep)
        viewModel.discoverImapSettings(walkStep, route, preset, username)
    }
    // ⚠ Keyed on the STEP as well as the route: arriving at a step is as much a reason to probe as
    // changing protocol, and a route-only key would never fire again once the walk moved.
    LaunchedEffect(route, walkStep) {
        if (walkStep == ConnectStep.ADDRESS || route == ConnectRoute.IMAP_PASSWORD) probeAddress()
    }
    val imapFill by viewModel.imapDiscovery.collectAsStateWithLifecycle()
    LaunchedEffect(imapFill) {
        imapFill?.let {
            preset = presetFilledWith(preset, it, username)
            // Applied exactly once: a field the user clears afterwards must not refill itself.
            viewModel.consumeImapDiscovery()
        }
    }
    // The step this seam has already cleared a finished attempt for, null while it has cleared
    // nothing — the marker the guard below reads, and the whole of what tells a rotation from an
    // arrival. ⛔ NOT [rememberedStep], which is synchronised with walkStep inside the same effect
    // (it differs only on a demotion).
    // ⛔ SAVED, and null is not spelled ADDRESS. A rotation restores the step this screen already
    // cleared for, so the seam does not run again on it and what the screen has just said stays.
    // An arriving screen has no saved marker, so it still clears — and it must: at first run this
    // screen is composed straight under the activity ([SternaApp]), which owns its ViewModel, so
    // an attempt that ended before it last went away is still in hand when it comes back.
    var clearedFor by rememberSaveable(stateSaver = ClearedStepSaver) {
        mutableStateOf<ConnectStep?>(null)
    }
    // The step changed, and both loose ends are tied here — ONE seam, so neither can be forgotten
    // at one of the several places the walk moves.
    LaunchedEffect(walkStep) {
        // ⛔ A demotion is FINAL until she moves herself: stepToRender sends the credentials step
        // back to the address step when its verdict is gone (process death), and a probe that
        // succeeds two seconds later must not teleport her forward, out of the step she is reading.
        if (rememberedStep != walkStep) rememberedStep = walkStep
        // ⛔ And what a FINISHED attempt left behind does not travel with her: a stale NeedsServer
        // re-fired the IMAP handover the moment the credentials step was re-entered, adding an
        // account on a tap on "Continue" that nobody meant as a sign-in.
        //
        // ⛔ ONLY WHERE IT IS DUE ([clearingIsDue], executed in ConnectWalkTest). A ROTATION
        // recreates the composition and restarts this effect with the step unmoved: the sentence
        // the last attempt left ("You're offline…") was wiped by the turn of the phone alone,
        // measured at the bench over portrait→landscape→portrait.
        if (clearingIsDue(clearedFor, walkStep)) {
            clearedFor = walkStep
            viewModel.clearFinishedAttempt()
        }
    }
    // The address step is waiting for a verdict: [awaitedAnswer] says what ends that wait, and
    // nothing else moves the walk forward.
    //
    // ⛔ OFFLINE IS ANSWERED HERE TOO, not only under the tap. Offline no probe sets out and none
    // is recorded, so a wait armed before a process death comes back with nothing that could ever
    // end it: the button stays disabled on "Finding your server…" for ever, and disabled it cannot
    // even be tapped to say why. Ending it here is also what catches the link dropping between the
    // tap's own read and this one — this effect is re-run right after the tap, the wait being one
    // of its keys.
    LaunchedEffect(awaitingVerdict, verdict, oauthVerdict, username) {
        when (val answer = awaitedAnswer(awaitingVerdict, viewModel.advanceFromAddress(username))) {
            is AwaitedAnswer.Leave -> {
                awaitingVerdict = false
                rememberedStep = answer.step
            }
            AwaitedAnswer.SayOffline -> {
                awaitingVerdict = false
                viewModel.reportOffline()
            }
            AwaitedAnswer.Wait -> Unit
        }
    }
    // The server answered that this account signs in away from the app: start that sign-in, so the
    // reader never has to know the word OAuth nor find a button for it. ⛔ The existing flows are
    // untouched — this is only WHERE they are started from — and they take the whole screen
    // through their own panels above.
    LaunchedEffect(walkStep, ask) {
        if (!shouldStartOAuthSignIn(walkStep, ask, handedOver)) return@LaunchedEffect
        startHandover()
    }
    // JMAP first, on the secret the credentials step just collected (that is what it is collected
    // BEFORE the probe for — `discoverJmapServer` needs it); the IMAP/SMTP endpoints the domain
    // published only if that answers "no JMAP server here". [shouldTryDiscoveredImap] decides, a
    // test executes it, and the protocol chips are never asked.
    LaunchedEffect(state, walkStep, found) {
        if (!shouldTryDiscoveredImap(walkStep, state, found)) return@LaunchedEffect
        // Non-null by that decision; the compiler cannot see it across the call.
        val endpoints = checkNotNull(found)
        viewModel.connectImap(
            username, password, accountName,
            endpoints.incoming.host, endpoints.incoming.port, endpoints.incoming.security,
            endpoints.outgoing.host, endpoints.outgoing.port, endpoints.outgoing.security,
        )
    }

    // Where the Connect button goes, in one place for every step that has one: the manual form's
    // six routes are untouched, the walk only chooses to press the same button.
    fun submitConnect() {
        when (route) {
            ConnectRoute.OUTLOOK_OAUTH -> viewModel.connectOutlookOAuth(username, accountName)
            ConnectRoute.JMAP_TOKEN -> viewModel.connectToken(server, username, password, accountName)
            ConnectRoute.JMAP_AUTODISCOVER -> viewModel.connectAuto(username, password, accountName)
            ConnectRoute.JMAP_SERVER -> viewModel.connect(server, username, password, accountName)
            ConnectRoute.IMAP_PASSWORD -> viewModel.connectImap(
                username, password, accountName,
                preset.imapHost, preset.imapPort.toInt(), preset.imapSecurity,
                preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.connect_add_account)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Keep the form scrollable above the keyboard so the focused field (password…)
                // stays visible while typing (#52) — same recipe as the compose screen.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ⛔ ABOVE the panels, and that position is the whole of it: each of them hands the
            // column over with a `return@Column`, so a handler declared below is not composed
            // while one is on screen — Back was then answered by nobody and the system popped the
            // whole screen, leaving the user in the launcher with a sign-in still in flight. Here
            // it does what the panel's own Cancel does, and Back on a panel is a cancellation
            // everywhere else in this app.
            // ⚠ It reaches NEITHER of the two other sign-ins this screen can show. The
            // imported-accounts listing below draws ImportSignIn's own panel, not a ConnectState,
            // and keeps its own cancel (cancelImportOAuth) and its own measured gestures. The
            // manual form's Microsoft chip lands in the SAME ConnectState as the walk's own
            // hand-over, and its poll is app-scoped precisely so that it survives this screen
            // being popped — so what is asked here is WHOSE hand-over is on screen
            // ([backCancelsHandover]), never merely whether one is.
            BackHandler(enabled = backCancelsHandover(state, handedOver)) { viewModel.cancelOAuth() }
            val awaiting = state as? ConnectState.AwaitingApproval
            if (awaiting != null) {
                DeviceApprovalPanel(awaiting, onCancel = viewModel::cancelOAuth)
                return@Column
            }
            val awaitingBrowser = state as? ConnectState.AwaitingBrowser
            if (awaitingBrowser != null) {
                BrowserAuthorizationPanel(awaitingBrowser, onCancel = viewModel::cancelOAuth)
                return@Column
            }
            (importSignIn as? ConnectViewModel.ImportSignIn.Listing)?.let { listing ->
                val sel = listing.selected
                if (sel == null) {
                    Spacer(Modifier.height(8.dp))
                    // The section renders its own "Accounts to sign in" header (shared with
                    // Settings → Backup), so no extra title here.
                    PendingImportAccountsSection(
                        // Re-read the StoredAccounts each recomposition (driven by importSignIn) so the
                        // list reflects the just-signed-in / dismissed accounts dropping off.
                        accounts = viewModel.pendingStoredAccounts,
                        onSignIn = { viewModel.selectImportAccount(it.id) },
                        onDismiss = { dismissWithUndo(it) },
                    )
                    // Always offer the normal add-account form from here (same button as
                    // Settings → Accounts). When every imported account is still deferred there
                    // is no signed-in account, so nothing else on screen leads to adding one —
                    // without this exit the listing was an onboarding dead end.
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::leaveImportListing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connect_add_account))
                    }
                } else {
                    ImportAccountSignIn(sel, viewModel)
                }
                return@Column
            }
            // Back belongs to the walk, and only to the walk. From the address step
            // [stepBackKeeping] answers null, this handler is disabled, and the system pops the
            // screen (or the first-run home simply stays) — a walk must never paint a blank screen
            // of its own making. The address travels back with it: nobody retypes her address at
            // every round trip.
            BackHandler(enabled = stepBackKeeping(walkStep, username) != null) {
                stepBackKeeping(walkStep, username)?.let { back ->
                    rememberedStep = back.step
                    username = back.email
                }
            }

            when (walkStep) {
                // ---- 1. The address, alone ----------------------------------------------------
                ConnectStep.ADDRESS -> {
                    if (firstRun) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connect_welcome_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.connect_welcome_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Reached from the pending-imports list via its "Add account" exit (or after dismissing
                    // the last pending row): while deferred imported accounts remain, offer the way back to
                    // their sign-in list so leaving it is never one-way either.
                    if (viewModel.pendingStoredAccounts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = viewModel::resumeImportSignIn,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_pending_title))
                        }
                    }
                    // Import entry points, shown whenever adding an account (not just first run): migrating
                    // from K-9 / Thunderbird or a Sterna backup belongs here, where people add accounts, not
                    // buried in Settings → Backup.
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.connect_import_header), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = {
                            importK9Launcher.launch(
                                arrayOf("application/octet-stream", "text/xml", "application/xml", "*/*"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connect_import_k9))
                    }
                    OutlinedButton(
                        onClick = {
                            importSettingsLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connect_import_settings))
                    }
                    // The address, a name if she wants one, and one button. ⛔ Nothing else: no protocol
                    // question, no host field, no sign-in method — those are answers the probes give, or
                    // questions the manual fallback asks once the probes came back empty.
                    Column(
                        modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountNameField(accountName, { accountName = it }, credentialReveal)
                        EmailField(username, { username = it; awaitingVerdict = false }, credentialReveal) {
                            probeAddress()
                        }
                        Button(
                            onClick = {
                                when (val advance = viewModel.advanceFromAddress(username)) {
                                    is AddressAdvance.Go -> rememberedStep = advance.step
                                    // ⛔ Offline: nothing to wait for — no probe may set out and none
                                    // is recorded — so the walk says so and stays put. Fall through to
                                    // Waiting here and the button spins for ever; move her on and she
                                    // lands in the manual form with no idea why.
                                    // ⛔ AND THE GESTURE ASKS FOR THE ANSWER TO BE SEEN, because
                                    // the effect at the foot of the column cannot: reportOffline
                                    // writes the SAME ConnectState.Error every time, a data class
                                    // into a MutableStateFlow — equal value, no emission, no
                                    // recomposition, and LaunchedEffect(status) would not re-run
                                    // even if there were one. Nothing puts the state back to Idle
                                    // in between (typing does not, clearFinishedAttempt only fires
                                    // on a change of step). So the SECOND tap said nothing at all:
                                    // fix a typo in the address (focus scrolls the credential
                                    // block, which sits ABOVE this one, back under the IME), tap
                                    // Continue again, and nothing moves — the reported defect,
                                    // word for word.
                                    AddressAdvance.Offline -> {
                                        viewModel.reportOffline()
                                        scope.launch { statusReveal.bringIntoView() }
                                    }
                                    // The probe has not answered yet: ask for it (it may not even have
                                    // set out — the field never lost focus) and leave when it does.
                                    AddressAdvance.Waiting -> {
                                        awaitingVerdict = true
                                        probeAddress()
                                    }
                                    AddressAdvance.Blocked -> Unit
                                }
                            },
                            // ⛔ The shared address rule, and NOT advanceFromAddress: this runs in
                            // composition, i.e. on every keystroke, and that call reads the link
                            // from Android (a binder round trip) only to throw the answer away —
                            // whether the button is grey depends on the address alone. What the
                            // link decides is what the TAP does, and there it is read at the tap.
                            enabled = canLeaveAddressStep(username) && !awaitingVerdict,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (awaitingVerdict) R.string.connect_discovering else R.string.connect_step_continue,
                                ),
                            )
                        }
                        // ⛔ Only once a probe for THIS address came back with nothing. Beside a verdict
                        // that worked, this button is the protocol question walking back on screen.
                        if (offersManualSetup(verdict, oauthVerdict, username)) {
                            OutlinedButton(
                                onClick = { rememberedStep = ConnectStep.MANUAL },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.connect_step_manual)) }
                        }
                    }
                }
                // ---- 2. What the domain published, and the secret it needs --------------------
                ConnectStep.CREDENTIALS -> {
                    Spacer(Modifier.height(4.dp))
                    // Which address this is for: shown rather than assumed (WYSIWYG), and a username node
                    // for the password managers that match on address + domain, not on the secret field.
                    OutlinedTextField(
                        value = username,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connect_email_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().autofill(
                            listOf(AutofillType.EmailAddress, AutofillType.Username),
                        ) {},
                    )
                    // ⛔ The password field belongs to ONE of the server's two answers, and it is
                    // drawn only under it. Under the other the browser sign-in is already starting
                    // (the effect above), and its own panel is about to take the whole screen; a
                    // secret typed here in the meantime would be collected and then dropped in
                    // silence, which is the defect this walk removes.
                    // ⛔ And a wheel is drawn only while something IS running: after Cancel the
                    // hand-over is over, the ask has not changed, and this step used to spin for
                    // ever over nothing. [credentialsPane] decides, a test executes it.
                    when (credentialsPane(ask, state, handedOver)) {
                        CredentialsPane.WAITING -> LoadingRing()
                        // The hand-over is over without an account (cancelled, or failed — the
                        // message is drawn below): the step goes back to being an invitation, and
                        // waits for HER gesture. ⛔ It does not restart on its own: that is what
                        // makes the panel's Cancel mean something.
                        CredentialsPane.SIGN_IN_OFFER -> {
                            Text(stringResource(R.string.connect_oauth_code_step1), style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { startHandover() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.connect_oauth_open_browser))
                            }
                        }
                        CredentialsPane.PASSWORD -> {
                            // The provider the domain named refuses a normal password (Gmail and friends): one tap
                            // to the page where she creates the one that works, instead of three failed sign-ins.
                            found?.let { appPasswordUrlFor(it) }?.let { url ->
                                Text(
                                    stringResource(R.string.connect_provider_app_password_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        leaveOnce {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.isSuccess
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.connect_app_password_help))
                                }
                            }
                            Column(
                                modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SecretField(
                                    value = password,
                                    onValue = { password = it },
                                    label = stringResource(R.string.connect_password),
                                    visible = passwordVisible,
                                    onVisible = { passwordVisible = it },
                                    reveal = credentialReveal,
                                )
                                val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
                                // ⛔ NOT the manual form's submit: that one reads the protocol chips,
                                // and a single tap on IMAP/SMTP — three steps and a Back ago, for another
                                // address — would send this step straight to IMAP without ever asking
                                // JMAP. The walk decides by probing, so the walk names its own route.
                                Button(
                                    onClick = { viewModel.connectAuto(username, password, accountName) },
                                    enabled = !busy && credentialsReady,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        when (state) {
                                            is ConnectState.Discovering -> stringResource(R.string.connect_discovering)
                                            is ConnectState.Connecting -> stringResource(R.string.connect_connecting)
                                            else -> stringResource(R.string.connect_connect)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // ⛔ The way OUT (#55). A domain that publishes an autoconfig AND an OAuth
                    // document reaches this step with the invitation and nothing else: no
                    // password field, one button, and a server that refuses this app's client
                    // id. Back lands on the address step, which withholds "Set up manually"
                    // because its verdict FOUND something — so the account was unaddable by any
                    // gesture. Which pane owes a way out is [credentialsExit]'s call, executed by
                    // a test; drawn here, under the closed guard, so it belongs to no branch.
                    credentialsExit(credentialsPane(ask, state, handedOver))?.let { exit ->
                        OutlinedButton(
                            onClick = { rememberedStep = exit },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.connect_step_manual))
                        }
                    }
                }
                // ---- 3. The manual fallback — reached only when discovery came back empty -----
                ConnectStep.MANUAL -> {
                    Text(stringResource(R.string.connect_protocol), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = protocol == MailProtocol.JMAP,
                            onClick = {
                                protocol = MailProtocol.JMAP
                                // Leaving IMAP disarms an OAuth preset, so Connect can't still be
                                // pointing at the Microsoft flow while the screen says JMAP (#105).
                                preset = presetForProtocol(preset, MailProtocol.JMAP)
                            },
                            label = { Text(stringResource(R.string.connect_jmap)) },
                        )
                        FilterChip(
                            selected = protocol == MailProtocol.IMAP,
                            onClick = {
                                protocol = MailProtocol.IMAP
                                preset = presetForProtocol(preset, MailProtocol.IMAP)
                            },
                            label = { Text(stringResource(R.string.connect_imap_smtp)) },
                        )
                    }

                    if (protocol == MailProtocol.JMAP) {
                        Text(stringResource(R.string.connect_auth_method), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !useApiToken,
                                onClick = { useApiToken = false },
                                label = { Text(stringResource(R.string.connect_password)) },
                            )
                            FilterChip(
                                selected = useApiToken,
                                onClick = { useApiToken = true },
                                label = { Text(stringResource(R.string.connect_auth_api_token)) },
                            )
                        }
                        Text(
                            stringResource(
                                if (useApiToken) R.string.connect_api_token_hint else R.string.connect_jmap_autodiscover_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Fastmail's JMAP endpoint refuses password (Basic) auth — API tokens only
                        // (#54) — so steer its users to the token option before they hit the 401.
                        if (!useApiToken && isFastmailTarget(username, server)) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    stringResource(R.string.connect_fastmail_token_hint),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                        TextButton(
                            onClick = { showAdvanced = !showAdvanced },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (showAdvanced) R.string.connect_advanced_hide else R.string.connect_advanced_show,
                                ),
                            )
                        }
                        if (showAdvanced) {
                            OutlinedTextField(
                                value = server,
                                onValueChange = { server = it },
                                label = { Text(stringResource(R.string.connect_jmap_server)) },
                                placeholder = { Text(stringResource(R.string.connect_jmap_server_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text(stringResource(R.string.connect_provider_preset), style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MAIL_PROVIDERS.forEach { provider ->
                                // FilterChip, not AssistChip: the selected provider is visible, and
                                // tapping it again lets go of it. Outlook used to be a one-way door —
                                // it hid the server fields with nothing on screen to bring them back
                                // (#105).
                                FilterChip(
                                    selected = preset.selected == provider.name,
                                    onClick = { preset = presetChipTapped(preset, provider) },
                                    label = { Text(provider.name) },
                                )
                            }
                        }
                        // Outlook signs in by OAuth, so the app-password note and the manual
                        // server/port fields don't apply — hide them while it's selected.
                        if (preset.serverFieldsVisible) {
                            Text(
                                stringResource(R.string.connect_provider_app_password_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Selected preset needs an app-specific password (Gmail…): one tap to
                            // the provider's page to create one, since their normal password is refused.
                            preset.appPasswordUrl?.let { url ->
                                TextButton(
                                    onClick = {
                                        leaveOnce {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.isSuccess
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.connect_app_password_help))
                                }
                            }

                            Text(stringResource(R.string.connect_incoming_imap), style = MaterialTheme.typography.labelLarge)
                            HostPortRow(
                                host = preset.imapHost, onHost = { preset = preset.copy(imapHost = it, discoveredFor = null) },
                                port = preset.imapPort, onPort = { preset = preset.copy(imapPort = it) },
                                hostPlaceholder = stringResource(R.string.connect_imap_host_placeholder),
                            )
                            SecurityChips(preset.imapSecurity) { preset = preset.copy(imapSecurity = it) }

                            Text(stringResource(R.string.connect_outgoing_smtp), style = MaterialTheme.typography.labelLarge)
                            HostPortRow(
                                host = preset.smtpHost, onHost = { preset = preset.copy(smtpHost = it, discoveredFor = null) },
                                port = preset.smtpPort, onPort = { preset = preset.copy(smtpPort = it) },
                                hostPlaceholder = stringResource(R.string.connect_smtp_host_placeholder),
                            )
                            SecurityChips(preset.smtpSecurity) { preset = preset.copy(smtpSecurity = it) }
                        }
                    }

                    // One container for the whole credential block (fields + action buttons): revealing it
                    // as a unit brings ALL of it above the keyboard when it fits, so the next field and
                    // the Connect button are reachable without dismissing the keyboard first.
                    Column(
                        modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountNameField(accountName, { accountName = it }, credentialReveal)
                        EmailField(username, { username = it }, credentialReveal) { probeAddress() }
                        // In API-token mode this same secret field holds the token (labelled accordingly).
                        val tokenMode = route == ConnectRoute.JMAP_TOKEN
                        SecretField(
                            value = password,
                            onValue = { password = it },
                            label = stringResource(
                                if (tokenMode) R.string.connect_auth_api_token else R.string.connect_password,
                            ),
                            visible = passwordVisible,
                            onVisible = { passwordVisible = it },
                            reveal = credentialReveal,
                        )
                        val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
                        Button(
                            onClick = { submitConnect() },
                            enabled = !busy && ready,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when (state) {
                                    is ConnectState.Discovering -> stringResource(R.string.connect_discovering)
                                    is ConnectState.Connecting -> stringResource(R.string.connect_connecting)
                                    else -> stringResource(R.string.connect_connect)
                                },
                            )
                        }

                        if (protocol == MailProtocol.JMAP && !useApiToken) {
                            TextButton(
                                onClick = { viewModel.connectOAuth(username, server, accountName) },
                                enabled = !busy && username.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.connect_oauth_button)) }
                        }
                        // Outlook has no separate button — its provider chip launches the OAuth flow.
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            // What this block has to say is a VALUE first ([statusLine], executed in
            // ConnectStatusTest), because it is also what the effect below has to scroll to: drawn
            // from one reading of the state and scrolled from another, the two rules drift apart
            // in silence.
            // ⛔ And the Column is composed WHATEVER THE VALUE IS — with nothing in it just as
            // readily — so within the walk's own column the requester's node is attached and
            // placed long before there is a sentence to show. Hang the requester off the Text
            // instead, or wrap this Column in `if (status != null)`, and the node comes into
            // existence in the very frame the scroll is asked for.
            // ⚠ NOT unconditional on the screen, and that is a known, UNMEASURED edge: the three
            // `return@Column` above (the device-approval panel, the browser panel, the pending
            // imports listing) hand the whole column away, so this block is not composed at all
            // while one of them is up. Coming back out of a panel with an error already in state,
            // the node is composed in the same frame the effect fires in. Left alone on purpose —
            // the bench decides whether it needs anything.
            val status = statusLine(state, walkStep)
            Column(modifier = Modifier.bringIntoViewRequester(statusReveal)) {
                when (status) {
                    StatusLine.Working -> LoadingRing()
                    StatusLine.ServerNotFound -> Text(
                        text = stringResource(R.string.connect_server_not_found),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is StatusLine.Failed -> Text(
                        text = stringResource(R.string.connect_error, status.message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    null -> Unit
                }
            }
            // ⛔ THE BENCH DEFECT. At the address step, keyboard up, offline, the whole of the
            // walk's answer to "Continue" is the sentence above — and the IME covered it (drawn at
            // y≈1589, keyboard top y≈1500), so from the reader's side nothing happened at all.
            // Keyed on the value SAID: it fires when the block starts speaking, not on every
            // recomposition, and not once at first composition when there is nothing to say. The
            // wheel is deliberately left where it is ([statusMustBeRead]).
            LaunchedEffect(status) {
                if (statusMustBeRead(status)) statusReveal.bringIntoView()
            }
        }
    }
}

/**
 * What the walk's own sign-in passes where the manual form passes its "Advanced" server field.
 *
 * ⛔ NOT `server`. That value belongs to the manual form: it is a `rememberSaveable` only that form
 * writes, nothing ever blanks it, and it is not on screen at the credentials step — using it there
 * breaks WYSIWYG on its own. Worse, it decides WHICH host is asked: typed for one address, then
 * carried to another the walk discovered, it sends this sign-in to that host alone, which answers
 * "This server doesn't support OAuth sign-in" — a sentence that is false, under a wheel, with no
 * password field beside it, on a path the reader never chose. The automatic path knows only what
 * the probe found, which is exactly what `reusableOAuth` then hands the flow.
 */
private const val NO_TYPED_SERVER = ""

/**
 * Coordinates scrolling the credential block above the keyboard: one requester for the whole
 * block, plus the requester of whichever field currently holds focus.
 */
@OptIn(ExperimentalFoundationApi::class)
private class CredentialBlockReveal {
    /** The whole credential block (fields + action buttons). */
    val block = BringIntoViewRequester()

    /** The focused field's own requester, or null while no credential field has focus. */
    var focused by mutableStateOf<BringIntoViewRequester?>(null)

    /**
     * Best effort for the block, guarantee for the field: ask for the whole block first, then
     * re-ask for the focused field. When the block is taller than the space left above the
     * keyboard the block request can't show everything (the scroller moves it edge-to-edge at
     * most), so the follow-up request makes the focused field win — it is never left scrolled
     * out, and the rest of the block shows as much as fits.
     */
    suspend fun reveal() {
        val field = focused ?: return
        block.bringIntoView()
        field.bringIntoView()
    }
}

/**
 * Marks a credential field: gaining focus reveals the whole credential block (not just this
 * field, which is all Material's built-in bring-into-view guarantees).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.credentialField(reveal: CredentialBlockReveal): Modifier {
    val own = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(own)
        .onFocusEvent { focusState ->
            if (focusState.isFocused) {
                reveal.focused = own
                // Immediate reveal for the keyboard-already-open case (moving focus between
                // fields); the first opening is handled by the IME-inset watcher, which re-runs
                // the reveal against the final keyboard-shrunk viewport.
                scope.launch { reveal.reveal() }
            } else if (reveal.focused === own) {
                reveal.focused = null
            }
        }
}

/** The optional label for the account being added — the same field at every step that shows it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountNameField(value: String, onValue: (String) -> Unit, reveal: CredentialBlockReveal) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.connect_account_name_optional)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().credentialField(reveal),
    )
}

/**
 * The address field — one implementation for the address step and for the manual form, so the
 * malformed-address warning and the discovery trigger cannot drift apart between them.
 *
 * [onFocusLost] fires on the FALLING edge of focus (true only while the field WAS focused), so
 * "done typing, moved on" is what may launch discovery, not the initial unfocused state.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EmailField(
    value: String,
    onValue: (String) -> Unit,
    reveal: CredentialBlockReveal,
    onFocusLost: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    // Flag an obviously malformed email (missing @ or a too-short/absent extension) as the user
    // types, without hard-blocking: some IMAP servers accept a non-email username.
    val emailLooksInvalid = value.isNotBlank() && !isValidEmail(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        isError = emailLooksInvalid,
        supportingText = if (emailLooksInvalid) {
            { Text(stringResource(R.string.connect_email_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().credentialField(reveal)
            .onFocusChanged { focusState ->
                // Leaving the field with an address typed is the moment discovery may set out;
                // the ViewModel's guard decides whether it actually does.
                if (hadFocus && !focusState.isFocused) onFocusLost()
                hadFocus = focusState.isFocused
            }
            .autofill(listOf(AutofillType.EmailAddress, AutofillType.Username), onValue),
    )
}

/** The secret field: a password at the credentials step, a password or an API token on the manual
 *  form (which is why the [label] is the caller's to choose). */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SecretField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    visible: Boolean,
    onVisible: (Boolean) -> Unit,
    reveal: CredentialBlockReveal,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onVisible(!visible) }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.connect_password_hide else R.string.connect_password_show,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().credentialField(reveal)
            .autofill(listOf(AutofillType.Password), onValue),
    )
}

/** Device-flow approval screen: show the user code + a button to open the browser. */
@Composable
private fun DeviceApprovalPanel(state: ConnectState.AwaitingApproval, onCancel: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    // ⛔ state.loginHint, and NOT the composable's own username field: this panel is shown for the
    // generic JMAP flow AND for the app-scoped Outlook one, and only the state tells them apart.
    DeviceApprovalContent(
        state.userCode, state.verificationUri, state.verificationUriComplete, state.loginHint, onCancel,
    )
}

/**
 * Authorization-code panel (#55): open the sign-in page, then wait for the browser to come back.
 *
 * No user code to show and nothing to copy — the whole exchange travels in the URL — so this is
 * the device panel minus the code, plus the one thing that flow cannot fail at: an image with no
 * browser installed at all.
 */
@Composable
private fun BrowserAuthorizationPanel(state: ConnectState.AwaitingBrowser, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Two browsers for one authorization is the device flow's D2 defect over again, and worse
    // here: each window carries its own request, the user finishes in one, and the other comes
    // back with a state that is no longer the pending one.
    val leaveOnce = rememberLeaveOnce()
    var noBrowser by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_oauth_code_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        onClick = {
            leaveOnce {
                // ⚠ The launch REPORTS back instead of swallowing its exception: a build with no
                // browser (a stripped ROM, a work profile with no browser) must say so, not offer
                // a button that silently does nothing — and must not crash on
                // ActivityNotFoundException either.
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(state.authorizationUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                noBrowser = !opened
                opened
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_oauth_open_browser)) }
    if (noBrowser) {
        Text(
            stringResource(R.string.connect_oauth_no_browser),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        stringResource(R.string.connect_oauth_code_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** The shared device-flow approval body (code + open-browser + waiting + cancel), reused by both the
 *  add-account and imported-account OAuth flows. */
@Composable
private fun DeviceApprovalContent(
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String?,
    loginHint: String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    // Two browsers for one device-flow approval is worse than most double-opens: the user finishes
    // in one of the two windows and is left staring at the other, which will never complete.
    val leaveOnce = rememberLeaveOnce()
    Text(
        stringResource(R.string.connect_oauth_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Tap the code (or the icon) to copy it — typing it on the Microsoft page is a pain.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(userCode))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(userCode, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.connect_oauth_copy_code))
    }
    Button(
        onClick = {
            val approval = verificationUriComplete ?: verificationUri
            // ⚠ Computed OUTSIDE the guard on purpose — the hand-off itself stays inside it, which
            // is what NavHostSourceRulesTest holds. A hint the server already wrote wins, and an
            // unreadable URL comes back untouched: see withLoginHint.
            val target = if (loginHint.isNullOrBlank()) approval else withLoginHint(approval, loginHint)
            leaveOnce {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_oauth_open_browser)) }
    Text(
        stringResource(R.string.connect_oauth_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** The inline sign-in for the one imported account the user tapped: a live device-flow, an OAuth
 *  sign-in button, or a password field (basic-auth or a chosen app-password fallback). */
@Composable
private fun ImportAccountSignIn(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val approval = target.approval
    when {
        approval != null -> {
            Spacer(Modifier.height(8.dp))
            // ⛔ No login hint: this is the post-import Microsoft sign-in, out of #55's scope.
            DeviceApprovalContent(
                approval.userCode, approval.verificationUri,
                approval.verificationUriComplete, null, onCancel = viewModel::cancelImportOAuth,
            )
        }
        target.account.authType == AuthType.OAUTH && !target.forcePassword ->
            ImportOAuthPanel(target, viewModel)
        else ->
            ImportSignInPanel(target, viewModel)
    }
}

/**
 * Post-import step for an OAuth account (Microsoft): a browser sign-in activates it. Unknown XOAUTH2
 * hosts can't be signed into automatically, so only the app-password fallback + back are offered.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportOAuthPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    if (account.provider != null) {
        Text(
            stringResource(R.string.connect_import_oauth_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::startImportOAuth,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (target.verifying) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
            } else {
                Text(stringResource(R.string.connect_import_signin_microsoft))
            }
        }
    } else {
        Text(
            stringResource(R.string.connect_import_oauth_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (target.offerAppPasswordFallback || account.provider == null) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = viewModel::switchImportToAppPassword,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.connect_import_use_app_password)) }
        AppPasswordHelpLink()
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/**
 * Post-import step: ask for the selected imported account's password, verify it against the server,
 * and save it. On success the account drops off the list; "Back to list" returns without signing in.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportSignInPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    var password by rememberSaveable(account.id) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(account.id) { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    // Read-only email field: shows which account this is AND gives password managers a username
    // node so they match the right credential (matching is by username + domain, not the password
    // field alone). The account is fixed, so an autofilled username is ignored.
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    // A forced fallback means this account imported as OAuth but sign-in failed/was declined:
    // point the user at creating a Microsoft app password to paste below.
    if (target.forcePassword) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppPasswordHelpLink()
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.connect_password)) },
        singleLine = true,
        isError = target.error != null,
        enabled = !target.verifying,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (password.isNotBlank()) viewModel.submitImportPassword(password) }),
        // Autofill hint so password managers (Bitwarden…) recognise and fill this field.
        modifier = Modifier.fillMaxWidth().autofill(listOf(AutofillType.Password)) { password = it },
    )
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = { viewModel.submitImportPassword(password) },
        enabled = password.isNotBlank() && !target.verifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (target.verifying) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
        } else {
            Text(stringResource(R.string.connect_import_signin_button))
        }
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/**
 * Keeps the quick-setup selection across a rotation or a process death, like the separate fields it
 * replaced. Every member is a String, a Boolean or an enum name, so the list is plainly saveable.
 */
/**
 * Keeps the step the walk reached across a rotation AND a process death — by name, so a reordered
 * enum cannot resurrect a different step from an old bundle. ⛔ It is not enough on its own: the
 * verdict the credentials step needs does NOT survive process death, which is why what is drawn is
 * [stepToRender]'s answer.
 */
private val ConnectStepSaver = listSaver<ConnectStep, String>(
    save = { listOf(it.name) },
    restore = { ConnectStep.valueOf(it[0]) },
)

/** No step has been cleared for yet — what an ARRIVING screen holds, and a rotation never does. */
private const val NOTHING_CLEARED = ""

private val ClearedStepSaver = listSaver<ConnectStep?, String>(
    save = { listOf(it?.name ?: NOTHING_CLEARED) },
    restore = { saved -> saved[0].takeIf { it != NOTHING_CLEARED }?.let(ConnectStep::valueOf) },
)

private val PresetFormSaver = listSaver<PresetForm, String>(
    save = {
        listOf(
            it.selected.orEmpty(), it.oauth.toString(),
            it.imapHost, it.imapPort, it.imapSecurity.name,
            it.smtpHost, it.smtpPort, it.smtpSecurity.name,
            it.appPasswordUrl.orEmpty(),
            // ⛔ Saved WITH the fields it is about: the host values survive process death, so the
            // one fact that says who put them there has to survive with them, or they come back
            // indistinguishable from hand-typed ones and jam the cascade for good.
            it.discoveredFor.orEmpty(),
        )
    },
    restore = {
        PresetForm(
            // No provider name and no help page are ever the empty string, so "" stands in for
            // "none" — the saved list holds no nulls.
            selected = it[0].ifEmpty { null },
            oauth = it[1] == "true",
            imapHost = it[2],
            imapPort = it[3],
            imapSecurity = ConnectionSecurity.valueOf(it[4]),
            smtpHost = it[5],
            smtpPort = it[6],
            smtpSecurity = ConnectionSecurity.valueOf(it[7]),
            appPasswordUrl = it[8].ifEmpty { null },
            discoveredFor = it[9].ifEmpty { null },
        )
    },
)

@Composable
private fun HostPortRow(
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    hostPlaceholder: String = "",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(stringResource(R.string.connect_server)) },
            placeholder = { if (hostPlaceholder.isNotEmpty()) Text(hostPlaceholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { onPort(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.connect_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun SecurityChips(selected: ConnectionSecurity, onSelect: (ConnectionSecurity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == ConnectionSecurity.TLS, { onSelect(ConnectionSecurity.TLS) }, { Text(stringResource(R.string.connect_security_ssl_tls)) })
        FilterChip(selected == ConnectionSecurity.STARTTLS, { onSelect(ConnectionSecurity.STARTTLS) }, { Text(stringResource(R.string.connect_security_starttls)) })
    }
}
