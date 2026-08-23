package app.sterna.ui.connect

import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.OAuthMetadata

/**
 * The add-account walk, continued: what [ConnectStep] and its transitions ([stepAfterAddress],
 * [stepToRender], [stepBack] in `ConnectPreset.kt`) do not say, and the screen must not decide for
 * itself.
 *
 * Everything here is a pure function over plain values, so a test EXECUTES the decision with
 * pinned arguments instead of re-reading the composable that contains it — the screen has no
 * interface test of any kind in this module (no Robolectric, no instrumented tests), so a decision
 * left inside it is a decision nothing can see.
 */

/**
 * A discovery verdict and the address it is a verdict ABOUT.
 *
 * The address is what makes the verdict durable without becoming a lie: the walk keeps it across a
 * rotation (the ViewModel survives), and the user can walk BACK and type a different address. A
 * bare `MailAutoconfigResult` held on its own would then answer for an address it never probed —
 * "we found your server" about somebody else's domain.
 */
internal data class DiscoveryVerdict(val email: String, val result: MailAutoconfigResult)

/**
 * The verdict in hand FOR [email], or null: a verdict about another address is no verdict at all,
 * and null is what sends the walk back to the address step ([stepToRender]) and the cascade back
 * out.
 */
internal fun verdictFor(verdict: DiscoveryVerdict?, email: String): MailAutoconfigResult? =
    verdict?.takeIf { it.email == email.trim() }?.result

/**
 * What a probe that never set out records — the guard ([shouldDiscoverImapSettings]) refused.
 *
 * It has to record SOMETHING for the refusals the user can act on, or the address step waits for an
 * answer nobody will ever send. But WHICH refusal it was decides that, and [online] is the fact
 * that tells them apart.
 *
 * ⛔ A refusal for want of a NETWORK records nothing. `NotFound` says "this domain publishes
 * nothing" about a domain nobody asked — and the lie outlives the outage: the verdict stays in
 * hand, so once the link is back [addressAdvance] reads it, sends her to the manual form, and no
 * probe ever sets out for that address again. Offline, what answers the user is the advance itself
 * ([AddressAdvance.Offline]) — a sentence on screen, not a fabricated verdict.
 *
 * Two more things it must NOT do, and these hold for every other refusal:
 *  - answer about an address the walk cannot act on at all ([canLeaveAddressStep] says no): there
 *    is nothing to be a verdict about, and recording one for the empty field would throw away the
 *    real verdict held for the address the user is in the middle of retyping;
 *  - overwrite an answer already in hand for the SAME address: a refusal is the absence of a
 *    probe, not the answer "this domain publishes nothing". The guard refuses on a filled host
 *    field too — which is exactly the state a successful discovery leaves behind — so without this
 *    rule every rotation of the manual form would demote its own Found to NotFound.
 */
internal fun verdictAfterRefusal(
    current: DiscoveryVerdict?,
    email: String,
    online: Boolean,
): DiscoveryVerdict? = when {
    !online -> current
    !canLeaveAddressStep(email) -> current
    current?.email == email.trim() -> current
    else -> DiscoveryVerdict(email.trim(), MailAutoconfigResult.NotFound)
}

/** What the address step's button does when it is tapped. */
internal sealed interface AddressAdvance {
    /** Not an address the walk can act on: the button does nothing (and renders disabled). */
    data object Blocked : AddressAdvance

    /** No verdict for this address yet: wait for the probe, decide nothing. */
    data object Waiting : AddressAdvance

    /**
     * Nothing to wait FOR: the device is offline, so say so and stay on the address step.
     *
     * ⛔ Distinct from [Waiting] on purpose, and that difference is the whole point. Offline no
     * probe sets out ([shouldDiscoverImapSettings]) and none is recorded ([verdictAfterRefusal]),
     * so "wait for the probe" is a wheel that turns until the app is killed.
     */
    data object Offline : AddressAdvance

    /** Go to [step] — [stepAfterAddress]'s answer, delegated, never recomputed here. */
    data class Go(val step: ConnectStep) : AddressAdvance
}

/**
 * Where a tap on the address step's button leads, given the verdict in hand and the link.
 *
 * The four answers are deliberately distinct: blocked, offline and waiting all leave the user on
 * the address step, but only one of them is worth a spinner, only one of them owes her a sentence,
 * and only one of them means the button should be disabled.
 *
 * ⛔ ORDER. A verdict in hand is an answer whatever the link is doing (the sign-in beyond has its
 * own offline guard, and a stale answer is not made truer by a network), so [AddressAdvance.Go]
 * comes first. But with no verdict, offline is answered BEFORE waiting: the caller that reads
 * [AddressAdvance.Waiting] asks for the probe again and draws "Finding your server…", and offline
 * that probe cannot set out, so the wheel never stops. Swap those two and the reported defect comes
 * back wearing a spinner.
 *
 * ⚠ [oauth] changes WHERE a verdict leads ([stepAfterAddress]), never WHETHER the walk moves. The
 * two probes travel together but do not land together, and an OAuth answer alone would advance the
 * walk while the settings cascade is still out — deciding, seconds early, that the domain publishes
 * no autoconfig. So with no settings verdict in hand the answer is still Waiting (or Offline), and
 * the order above is untouched.
 */
internal fun addressAdvance(
    email: String,
    verdict: DiscoveryVerdict?,
    oauth: OAuthDiscovery?,
    online: Boolean,
): AddressAdvance {
    if (!canLeaveAddressStep(email)) return AddressAdvance.Blocked
    val result = verdictFor(verdict, email)
    if (result != null) return AddressAdvance.Go(stepAfterAddress(result, oauth, email))
    return if (online) AddressAdvance.Waiting else AddressAdvance.Offline
}

/** What the address step's WAIT for a verdict may end with — and the only ways it ends at all. */
internal sealed interface AwaitedAnswer {
    /** Nothing to do: either nothing is being awaited, or no answer has come yet. */
    data object Wait : AwaitedAnswer

    /** The verdict landed: end the wait and go to [step]. */
    data class Leave(val step: ConnectStep) : AwaitedAnswer

    /** No answer is ever coming — there is no link. End the wait and SAY so. */
    data object SayOffline : AwaitedAnswer
}

/**
 * What to do about a wait for the address step's verdict, given [awaiting] and what
 * [addressAdvance] now says.
 *
 * ⛔ THE LOCK THIS EXISTS TO OPEN. Offline no probe sets out ([shouldDiscoverImapSettings]) and
 * none is recorded ([verdictAfterRefusal]) — deliberately, because a fabricated verdict outlives
 * the outage. So an armed wait offline has NO other way out: the wait survives process death (it is
 * saved) while the verdict does not, and the caller's effect only re-runs when the wait, the
 * verdict or the address change. Come back offline with the wait armed and the button stays
 * disabled on "Finding your server…" for ever — and disabled, it cannot even be tapped to say why.
 * [SayOffline] is that exit, and it is the same sentence the tap itself gives.
 *
 * ⛔ [awaiting] is an argument, and the FIRST thing read, because the caller's effect also runs when
 * no wait is armed at all — on every keystroke, since the address is one of its keys. Ignore it and
 * a verdict landing for the address being typed carries the reader off the address step mid-word,
 * which is exactly the "no automatic jump" the bench signed off on.
 *
 * ⚠ [AddressAdvance.Blocked] and [AddressAdvance.Waiting] both mean "keep waiting": the wait is
 * ended by an ANSWER, and editing the address is what disarms it (there is no answer to give about
 * an address that is not one).
 */
internal fun awaitedAnswer(awaiting: Boolean, advance: AddressAdvance): AwaitedAnswer = when {
    !awaiting -> AwaitedAnswer.Wait
    advance is AddressAdvance.Go -> AwaitedAnswer.Leave(advance.step)
    advance is AddressAdvance.Offline -> AwaitedAnswer.SayOffline
    else -> AwaitedAnswer.Wait
}

/**
 * Whether the address step may offer "Set up manually".
 *
 * ⛔ ONLY after a probe for THIS address came back with nothing. Offered beside a verdict that
 * worked, that button puts the protocol question (JMAP? IMAP/SMTP?) back on screen for everyone —
 * the one thing this whole walk exists to take off it. Offered before any verdict, it invites the
 * user to answer a question the probe was about to answer for her.
 *
 * ⛔ AND A DRIVABLE OAUTH ANSWER IS A VERDICT THAT WORKED, autoconfig or no autoconfig. A domain
 * served over OAuth that publishes no Thunderbird document ([oauth] usable, verdict `NotFound`)
 * now leads to the credentials step ([stepAfterAddress]), so the protocol question here would be
 * offered beside a walk that succeeded — the case above, verbatim.
 *
 * ⚠ THE MANUAL DOOR DOES NOT DISAPPEAR, it moves one step along: the credentials step offers it
 * under [CredentialsPane.SIGN_IN_OFFER] ([credentialsExit]), which is exactly what a domain
 * recognised by autoconfig has always done.
 */
internal fun offersManualSetup(
    verdict: DiscoveryVerdict?,
    oauth: OAuthDiscovery?,
    email: String,
): Boolean = verdictFor(verdict, email) is MailAutoconfigResult.NotFound &&
    !oauthOpensCredentials(oauth, email)

/** Where Back lands, and the address it lands with. */
internal data class WalkBack(val step: ConnectStep, val email: String)

/**
 * Back from [current], carrying [email] through untouched.
 *
 * The address is IN the answer rather than left to the call site on purpose: "Back keeps what she
 * typed" is the rule, and a rule the screen re-states by hand is a rule the screen can quietly
 * drop — retyping the address at every round trip is the classic way a walk becomes worse than the
 * single page it replaced. `null` (from [stepBack]) means Back is not ours: the system pops the
 * screen, so the address step can never answer Back with a blank screen of its own making.
 */
internal fun stepBackKeeping(current: ConnectStep, email: String): WalkBack? =
    stepBack(current)?.let { WalkBack(it, email) }

/**
 * The quick-setup form to carry into a probe for [email], at the [step] on screen.
 *
 * ⛔ THE RATCHET THIS EXISTS TO BREAK. The cascade's guard refuses to set out over a non-blank
 * host — and a successful discovery leaves exactly that behind, in a form that survives process
 * death while the verdict behind it does not. Come back with the fields full and no verdict, and
 * every probe is refused, every refusal records "nothing published" ([verdictAfterRefusal]), and
 * that answer then sticks to every address typed afterwards: one successful discovery became a
 * permanent failure.
 *
 * So host values are dropped — per side, atomically, the mirror of [presetFilledWith] — as soon as
 * NOTHING backs them any more: the verdict in hand is about another address, or there is no
 * verdict at all. Blank fields are what let the cascade set out again, and the probe rewrites them.
 *
 * The two things it must never do, and the two facts that decide it:
 *  - ⛔ never throw away what the USER typed. [PresetForm.discoveredFor] is the only thing that can
 *    tell hers from the cascade's once the verdict is gone; unset, this function keeps everything;
 *  - ⛔ never take anything off the manual form while she is standing on it ([step]). There the
 *    fields ARE the work in progress, stale or not — she can see them and edit them, and a stale
 *    host there costs a correction, not a lost configuration.
 */
internal fun presetForAddress(
    preset: PresetForm,
    verdict: DiscoveryVerdict?,
    email: String,
    step: ConnectStep,
): PresetForm {
    if (step != ConnectStep.ADDRESS) return preset
    val filledFor = preset.discoveredFor ?: return preset
    // ⛔ A Found, not merely "a verdict": NotFound is not what put hosts in these fields, and
    // reading it as backing them leaves the guard jammed with the very answer that jammed it.
    val backed = filledFor == email.trim() && verdictFor(verdict, email) is MailAutoconfigResult.Found
    if (backed) return preset
    return preset.copy(
        imapHost = PresetForm.NONE.imapHost,
        imapPort = PresetForm.NONE.imapPort,
        imapSecurity = PresetForm.NONE.imapSecurity,
        smtpHost = PresetForm.NONE.smtpHost,
        smtpPort = PresetForm.NONE.smtpPort,
        smtpSecurity = PresetForm.NONE.smtpSecurity,
        discoveredFor = null,
    )
}

/**
 * Whether leaving a step must wipe what the last attempt left in [state].
 *
 * ⛔ A FINISHED attempt only. [ConnectState.NeedsServer] and [ConnectState.Error] are verdicts
 * about a sign-in that is over; carried into the next step they make the screen act on something
 * nobody asked for again — a stale `NeedsServer` re-fired the IMAP handover the moment the
 * credentials step was re-entered, adding an account on a tap on "Continue".
 *
 * ⛔ And nothing else: `Connecting`/`Discovering`/the two awaiting-approval states are attempts
 * still RUNNING, and every add route refuses a second submit by reading exactly those. Clearing
 * them would hand the user a second Connect over a coroutine still in flight, i.e. two accounts.
 */
internal fun clearsAttemptOnLeave(state: ConnectState): Boolean =
    state is ConnectState.NeedsServer || state is ConnectState.Error

/**
 * Whether what a finished attempt left behind is due to be cleared on arriving at [arriving] —
 * that is, whether this screen has not already cleared for that step. [cleared] is the step it did
 * clear for, and null when it has cleared nothing yet.
 *
 * ⛔ WHY THIS EXISTS, measured at the bench (emu, release, airplane mode). The seam that clears a
 * finished attempt is keyed on the step and its comment says "the step changed" — but a ROTATION
 * recreates the composition, so every effect on this screen restarts with the step unmoved. What
 * the last attempt left was then wiped by the rotation itself: the reader taps Continue, is finally
 * told why nothing happens ("You're offline…"), turns her phone, and the screen is mute again with
 * nothing having changed. An ordinary recomposition (the keyboard coming and going) kept the
 * sentence; only the rotation threw it away, and this is the fact the effect never checked.
 *
 * ⛔ A REAL MOVE MUST STILL CLEAR — this is a guard against a step already cleared for, never a way
 * of keeping a finished attempt. Carried across steps, a stale [ConnectState.NeedsServer] re-fires
 * the IMAP hand-over the moment the credentials step is re-entered, adding an account on a tap on
 * "Continue" ([clearsAttemptOnLeave], whose list of cleared states this does not touch).
 *
 * ⛔ AND SO MUST AN ARRIVAL, which is why null is not spelled "the first step". The screen's view
 * model is NOT new every time the screen appears: at first run the screen is composed straight
 * under the activity, so an attempt that ended before it last went away is still in hand when it
 * comes back. Null says "this screen has cleared nothing yet"; a rotation restores a step and says
 * the opposite.
 */
internal fun clearingIsDue(cleared: ConnectStep?, arriving: ConnectStep): Boolean = cleared != arriving

/**
 * The page where the provider behind [found] has the user create an app-specific password, or null.
 *
 * Recognised by the incoming host the domain itself published, matched against the hard-coded
 * [MAIL_PROVIDERS] — the same table the quick-setup chips read, so there is one list of "who
 * refuses a normal password", not two. No match means no link: a guess here sends the user to
 * somebody else's account page.
 */
internal fun appPasswordUrlFor(found: MailAutoconfigResult.Found): String? =
    MAIL_PROVIDERS.firstOrNull { it.imapHost.equals(found.incoming.host, ignoreCase = true) }?.appPasswordUrl

/**
 * Whether the credentials step should now sign in over IMAP/SMTP, on the endpoints the domain
 * published.
 *
 * The walk asks JMAP first — that is what the secret is collected before probing FOR, since
 * `discoverJmapServer` needs it, and a JMAP session is what this app serves best — and
 * [ConnectState.NeedsServer] is that probe's own answer of "no JMAP server here". It is the ONLY
 * state that hands over: an [ConnectState.Error] means the server answered and refused (a wrong
 * password, most of the time), and replaying the same secret at another door would turn a typo
 * into a second rejection.
 */
internal fun shouldTryDiscoveredImap(
    step: ConnectStep,
    state: ConnectState,
    found: MailAutoconfigResult.Found?,
): Boolean = step == ConnectStep.CREDENTIALS && state is ConnectState.NeedsServer && found != null

// ---- How the server says this account signs in ------------------------------------------------

/**
 * What the OAuth search found FOR an address: the host that would sign this account in and what it
 * advertises, or `chosen == null` when nothing usable answered.
 *
 * The address is in it for the same reason it is in [DiscoveryVerdict]: the user can walk back and
 * type another one, and an answer about somebody else's domain is not an answer about hers.
 */
internal data class OAuthDiscovery(val email: String, val chosen: Pair<String, OAuthMetadata>?)

/**
 * What the credentials step asks for — the server's answer, never the user's choice.
 *
 * That is the whole of this volet: OAuth used to be a button reading "Sign in with OAuth (no
 * password)" buried in the manual fallback, so a reader whose server wants OAuth had to know what
 * OAuth is, guess that "Set up manually" is where it lives, and find it there.
 */
internal sealed interface CredentialsAsk {
    /**
     * No OAuth answer for this address at all.
     *
     * ⛔ NOT the password field. A password typed and then thrown away in silence, because the
     * server turned out to want a browser, is exactly the defect being removed — so the absence of
     * an answer asks for nothing at all.
     *
     * ⛔ AND NOT REACHABLE AT THE CREDENTIALS STEP, which is the other half of the same rule and
     * the one that was missing: drawn there this asks for nothing, offers nothing and starts
     * nothing — a bare wheel, for an address that can then never be probed again (a discovered
     * host fills the form, and the cascade refuses to set out over one). What keeps it out is that
     * the two answers TRAVEL TOGETHER: one pass over an address yields both ([AddressProbe], whose
     * OAuth side is not nullable), the ViewModel publishes both, and the credentials step is only
     * ever reached on a settings verdict that pass produced. The gate that used to withhold the
     * OAuth answer on some steps is gone; nothing may take its place.
     */
    data object Awaiting : CredentialsAsk

    /** The server signs this account in away from the password field, by [grant]. */
    data class SignInAway(val grant: OAuthGrant) : CredentialsAsk

    /** No OAuth, or none this app can drive: the password field, exactly as before. */
    data object Password : CredentialsAsk
}

/**
 * What the credentials step must ask for [email], given the OAuth answer in hand.
 *
 * ⛔ WHICH grant is [chooseOAuthGrant]'s call and stays there — a server offering both is signed in
 * with the device flow, which is the shipped production path. This function only says whether the
 * answer is a password field or a hand-over.
 *
 * ⚠ A server that advertises OAuth but no grant this app can drive ([OAuthGrant.NONE]) asks for the
 * password: it is the honest fallback, and the one thing that must NOT happen there is telling the
 * reader her server does not support OAuth — it does.
 */
internal fun credentialsAsk(discovery: OAuthDiscovery?, email: String): CredentialsAsk {
    val answered = discovery?.takeIf { it.email == email.trim() } ?: return CredentialsAsk.Awaiting
    val chosen = answered.chosen ?: return CredentialsAsk.Password
    return when (val grant = chooseOAuthGrant(chosen.second)) {
        OAuthGrant.DEVICE_CODE, OAuthGrant.AUTHORIZATION_CODE -> CredentialsAsk.SignInAway(grant)
        OAuthGrant.NONE -> CredentialsAsk.Password
    }
}

/**
 * Whether the OAuth answer in hand is one this walk can actually DRIVE for [email] — the ONE
 * spelling of "usable OAuth", so the address step, the manual door and the credentials step cannot
 * quietly disagree about it.
 *
 * ⛔ NOT `chosen != null`, and that difference is the whole of why this function exists rather than
 * a field read at three call sites. A host advertising OAuth with no grant this app can drive
 * ([OAuthGrant.NONE]) yields [CredentialsAsk.Password] — and at the credentials step reached on
 * the OAuth answer ALONE, with no autoconfig verdict behind it, that password field has no server
 * to send anything to ([shouldTryDiscoveredImap] wants a `Found`). A dead end one step further
 * from the manual form than the address step she came from.
 *
 * ⛔ It is [credentialsAsk] EXECUTED, never a second reading of the same facts: the address match
 * and the grant ranking are decided there, once. Spelled again here they drift, silently.
 */
internal fun oauthOpensCredentials(discovery: OAuthDiscovery?, email: String): Boolean =
    credentialsAsk(discovery, email) is CredentialsAsk.SignInAway

/**
 * Whether the screen must now start the OAuth sign-in on its own — the moved TRIGGER, and the whole
 * of what this volet changes about the flows themselves: nothing else.
 *
 * ⛔ Gated on the step as well as on the ask: the manual fallback keeps its own explicit button, and
 * an answer collected at the address step must not launch a browser while the user is still typing
 * her address.
 *
 * ⛔ AND ONCE PER ADDRESS ([handoverStarted]). Automatic and unconditional are not the same thing:
 * the hand-over panel has a Cancel, and a trigger that fires again the moment that panel is gone
 * reopens the browser the user just refused — Cancel then means nothing at all. Restarting is HER
 * gesture afterwards ([CredentialsPane.SIGN_IN_OFFER]), and the walk waits for it.
 */
internal fun shouldStartOAuthSignIn(
    step: ConnectStep,
    ask: CredentialsAsk,
    handoverStarted: Boolean,
): Boolean = step == ConnectStep.CREDENTIALS && ask is CredentialsAsk.SignInAway && !handoverStarted

/**
 * Whether a hand-over has already been started, on this screen, FOR the address on screen.
 *
 * [startedFor] is the address the screen last handed over for, [NO_HANDOVER] when it never did. It
 * is an address and not a flag for the same reason every other answer on this walk is: she can walk
 * back and type a different one, and a hand-over started for the previous address is no reason to
 * withhold the automatic one for the new address.
 */
internal fun handoverStarted(startedFor: String, email: String): Boolean =
    startedFor != NO_HANDOVER && startedFor == email.trim()

/** No hand-over started yet — the initial value of the screen's "started for" address. */
internal const val NO_HANDOVER = ""

/** What the credentials step draws under the server's answer. */
internal enum class CredentialsPane {
    /** The password field and its block, exactly as before. */
    PASSWORD,

    /** A wheel, and nothing else: something IS running that this step is waiting on. */
    WAITING,

    /** The invitation and its button: a hand-over is possible, and none is running. */
    SIGN_IN_OFFER,
}

/**
 * What step 2 draws, given the server's answer, the attempt's state, and whether this screen has
 * already handed over for this address.
 *
 * ⛔ THE DEFECT. Under "the server signs this account in away from here", the step used to draw a
 * wheel whenever it was not drawing the password field. Cancel on the hand-over panel puts the
 * attempt back to [ConnectState.Idle] and leaves the ask exactly as it was, so the step came back
 * to a wheel that turns for ever, over an address field, with nothing loading behind it and no way
 * out but the system Back — a screen asserting a progress that does not exist, one tap off the
 * default path.
 *
 * The three answers, and the fact that separates them:
 *  - [PASSWORD] — the server wants a password. Nothing else here touches that branch;
 *  - [WAITING] — a wheel is TRUE: either the server's answer is still coming ([CredentialsAsk
 *    .Awaiting]), or the hand-over is starting this very frame (the effect runs just after this
 *    composition), or it is running and its own panel is about to take the screen;
 *  - [SIGN_IN_OFFER] — a hand-over that is over without an account: cancelled ([ConnectState.Idle])
 *    or failed ([ConnectState.Error], whose message is drawn under it). The step becomes an
 *    invitation again — the same sentence and the same button the browser panel shows — and it
 *    waits for a gesture instead of restarting on its own.
 *
 * ⚠ [ConnectState.Connected] is deliberately not an offer: the account is in, the screen is leaving,
 * and an invitation to sign in again would flash over a walk that succeeded.
 */
internal fun credentialsPane(
    ask: CredentialsAsk,
    state: ConnectState,
    handoverStarted: Boolean,
): CredentialsPane = when {
    ask is CredentialsAsk.Password -> CredentialsPane.PASSWORD
    ask !is CredentialsAsk.SignInAway -> CredentialsPane.WAITING
    !handoverStarted -> CredentialsPane.WAITING
    state is ConnectState.Idle || state is ConnectState.Error -> CredentialsPane.SIGN_IN_OFFER
    else -> CredentialsPane.WAITING
}

/**
 * The step the credentials step must offer as a way OUT under [pane], or null when it owes none.
 *
 * ⛔ A DISCOVERY THAT WORKED MUST NEVER TAKE A DOOR AWAY, and this is the half of that rule that was
 * missing. [offersManualSetup] holds it at the address step, for a probe that found nothing; this
 * holds it at the step a probe that FOUND something leads to. A domain publishing both an autoconfig
 * and an RFC 8414 document lands the reader on [CredentialsPane.SIGN_IN_OFFER]: no password field
 * (the server said OAuth), one button, and a server that refuses this app's client id — so the
 * button fails every time. Back returns to the address step, whose "Set up manually" is withheld
 * because the verdict is a `Found`. The account cannot be added by any gesture at all (#55).
 *
 * ⛔ UNDER THE OFFER ONLY, and the other two are refusals, not omissions:
 *  - [CredentialsPane.WAITING] means something IS running — the browser is open, or its panel is one
 *    frame away. A manual form reached from under a hand-over in flight adds a second account behind
 *    the first;
 *  - [CredentialsPane.PASSWORD] already has its way through, and it is the field. A second button
 *    beside it is the protocol question (JMAP? IMAP/SMTP?) back on screen for everyone.
 */
internal fun credentialsExit(pane: CredentialsPane): ConnectStep? =
    if (pane == CredentialsPane.SIGN_IN_OFFER) ConnectStep.MANUAL else null

/**
 * Whether a hand-over panel (device code, or browser authorization) currently owns the screen.
 *
 * It is what makes the walk's Back reachable while one is up: those panels return out of the column
 * before anything else is composed, so a handler declared below them is not composed at all, and
 * the system pops the whole screen — the user is in the launcher with a sign-in still in flight.
 */
internal fun showsHandoverPanel(state: ConnectState): Boolean =
    state is ConnectState.AwaitingApproval || state is ConnectState.AwaitingBrowser

/**
 * Whether the system Back must cancel the hand-over whose panel is on screen — the panel being up
 * ([showsHandoverPanel]) AND this walk being the one that started it ([startedByWalk], i.e.
 * [handoverStarted] for the address on screen).
 *
 * ⛔ THE STATE SAYS A PANEL IS UP, NEVER WHOSE IT IS. Two flows put one on this screen:
 *  - the walk's own, started at step 2 ([shouldStartOAuthSignIn], or the [CredentialsPane
 *    .SIGN_IN_OFFER] button) and always through the seam that records the address it handed over
 *    for. Back there left the APPLICATION with a sign-in still in flight — the defect measured at
 *    the bench — and Back on a panel is a cancellation everywhere else in this app;
 *  - the manual form's Microsoft/Outlook chip, whose progress is mirrored into the very same
 *    [ConnectState.AwaitingApproval]. That one is app-scoped ON PURPOSE: `connectOutlookOAuth`
 *    delegates to `OutlookSignIn` so the token poll survives the round trip to the browser —
 *    backgrounding, app lock, AND this screen being popped. Cancelling it on Back kills a sign-in
 *    that used to add the account by itself while the reader was in the browser.
 *
 * The manual form never goes through the walk's seam, so [startedByWalk] is what tells them apart.
 */
internal fun backCancelsHandover(state: ConnectState, startedByWalk: Boolean): Boolean =
    startedByWalk && showsHandoverPanel(state)

/**
 * The OAuth server already found for [email], reusable by the sign-in itself, or null to search.
 *
 * ⛔ Two guards, and both are about answering for the right server. A [server] typed by hand under
 * Advanced is the ONE host that sign-in must ask, so a domain-derived answer may not stand in for
 * it; and an answer about another address is not an answer about this one.
 *
 * ⚠ Why reuse at all: without it the hand-over probes a second time, seconds after the first, and a
 * host that answered once but not twice would be reported as "this server doesn't support OAuth
 * sign-in" — a sentence that is factually false, on a path the reader did not even choose.
 */
internal fun reusableOAuth(
    discovery: OAuthDiscovery?,
    email: String,
    server: String,
): Pair<String, OAuthMetadata>? =
    if (server.isBlank()) discovery?.takeIf { it.email == email.trim() }?.chosen else null

// ---- What the foot of the column says, and whether it has to be read ---------------------------

/**
 * What the status block at the foot of the walk has to say, whatever step it stands on.
 *
 * ⛔ IT IS A VALUE BEFORE IT IS A `Text`, and that is the whole of it. The block is also what a
 * scroll has to aim at, and a screen that draws it from one reading of [ConnectState] and scrolls
 * it from another is a screen with two spellings of the same rule: they drift, and the drift is
 * silent — the sentence drawn under one, the scroll withheld by the other.
 */
internal sealed interface StatusLine {
    /** An attempt is running. A wheel, and NOT a sentence — see [statusMustBeRead]. */
    data object Working : StatusLine

    /** Discovery came back with nothing: name the field the manual form now wants filled. */
    data object ServerNotFound : StatusLine

    /** An attempt is over and failed, and [message] is the whole of what it said. */
    data class Failed(val message: String) : StatusLine
}

/**
 * What the block says for [state] at [step], or null for the states that say nothing.
 *
 * ⛔ A FAILURE SPEAKS AT EVERY STEP, and no step condition may ever be added to that branch: the
 * address step's own offline answer IS a [ConnectState.Error] (`reportOffline`), so conditioning
 * this on the step computes a message, writes it to the state, and renders nothing at all.
 *
 * ⚠ [ConnectState.NeedsServer] is the one line that IS step-conditioned, and it belongs here rather
 * than in the screen for the same reason as everything else in this file: on the manual form it
 * names the field the user must now fill; at the credentials step the walk is already dialling the
 * endpoints the domain published, so "enter it below" would point at nothing.
 */
internal fun statusLine(state: ConnectState, step: ConnectStep): StatusLine? = when (state) {
    is ConnectState.Connecting, is ConnectState.Discovering -> StatusLine.Working
    is ConnectState.NeedsServer -> if (step == ConnectStep.MANUAL) StatusLine.ServerNotFound else null
    is ConnectState.Error -> StatusLine.Failed(state.message)
    else -> null
}

/**
 * Whether [line] must be brought into view — i.e. whether it is a SENTENCE.
 *
 * ⛔ THE DEFECT THIS ANSWERS, measured at the bench: at the address step with the keyboard up, the
 * whole of the walk's answer to "Continue" offline is one line at the foot of the column, and the
 * IME covers it. She taps, and nothing moves. The block is inside an `imePadding()` column that
 * scrolls, so nothing overflows — what is missing is that the block never asked to be seen.
 *
 * ⛔ AND NOT THE WHEEL. [StatusLine.Working] is drawn on every connection attempt, from a form the
 * reader may be in the middle of; scrolling to it would pull that form out from under her fingers
 * to show her a spinner she did not ask to read. Null likewise: there is nothing to bring into
 * view, and asking anyway scrolls the column on the way OUT of every error.
 */
internal fun statusMustBeRead(line: StatusLine?): Boolean = when (line) {
    is StatusLine.ServerNotFound, is StatusLine.Failed -> true
    is StatusLine.Working, null -> false
}
