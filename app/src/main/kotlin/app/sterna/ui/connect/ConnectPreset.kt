package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.util.isValidEmail

/**
 * What the add-account form decides on its own, kept out of the composable: which quick-setup
 * provider is selected, the server values that selection fills in, and where the Connect button
 * sends the result.
 *
 * It lives here because the answer used to be spread over four independent pieces of screen state
 * that could disagree with each other (#105): tapping "Outlook" armed a flag nothing could disarm,
 * so the server fields vanished with no way back, and the flag survived a switch to JMAP and sent
 * that form to the Microsoft sign-in.
 */

/** A known mail provider's IMAP/SMTP settings, applied by the quick-setup chips. */
internal data class MailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: ConnectionSecurity,
    /** When true the chip starts the OAuth sign-in flow instead of filling host/port. */
    val oauth: Boolean = false,
    /** Page where the user creates an app-specific password (their normal one is refused). */
    val appPasswordUrl: String? = null,
)

internal val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://myaccount.google.com/apppasswords"),
    // Outlook authenticates over IMAP/SMTP with OAuth (XOAUTH2) — Microsoft has disabled
    // password IMAP — so this chip launches the OAuth flow rather than filling host/port.
    MailProvider("Outlook", "outlook.office365.com", "993", ConnectionSecurity.TLS, "smtp.office365.com", "587", ConnectionSecurity.STARTTLS, oauth = true),
    MailProvider("Yahoo", "imap.mail.yahoo.com", "993", ConnectionSecurity.TLS, "smtp.mail.yahoo.com", "465", ConnectionSecurity.TLS),
    MailProvider("iCloud", "imap.mail.me.com", "993", ConnectionSecurity.TLS, "smtp.mail.me.com", "587", ConnectionSecurity.STARTTLS),
    MailProvider("Fastmail", "imap.fastmail.com", "993", ConnectionSecurity.TLS, "smtp.fastmail.com", "465", ConnectionSecurity.TLS),
    // Yandex and Mail.ru are plain IMAP behind an app-specific password (#105). Values taken from
    // the providers' own documentation, not by analogy with each other:
    //   yandex.com/support/mail/mail-clients/others.html  → imap/smtp.yandex.com, 993/465, SSL
    //   help.mail.ru/mail/login/mailer/ (§ "Указать данные серверов") → imap/smtp.mail.ru, 993/465, SSL/TLS
    MailProvider("Yandex", "imap.yandex.com", "993", ConnectionSecurity.TLS, "smtp.yandex.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://id.yandex.com/security/app-passwords"),
    MailProvider("Mail.ru", "imap.mail.ru", "993", ConnectionSecurity.TLS, "smtp.mail.ru", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://account.mail.ru/user/2-step-auth/passwords/"),
    MailProvider("Proton Bridge", "127.0.0.1", "1143", ConnectionSecurity.STARTTLS, "127.0.0.1", "1025", ConnectionSecurity.STARTTLS),
)

/**
 * The quick-setup state of the form: the selected provider (by name — chips are brand labels, never
 * translated) plus the values that selection put in the server fields. [selected] `null` means no
 * chip is armed and the user is on their own, which is the state a fresh screen starts in and the
 * one a second tap on the armed chip returns to.
 */
internal data class PresetForm(
    val selected: String? = null,
    /** Sign-in goes through the browser (Microsoft device flow) instead of host/port + password. */
    val oauth: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val appPasswordUrl: String? = null,
    /**
     * The address a PROBE filled these host fields for, or null when whatever is in them is the
     * user's own.
     *
     * It is the one thing the fields themselves cannot say. The form survives process death
     * (`rememberSaveable`) and the verdict behind it does not, so on the way back "imap.x.tld is
     * here because a probe put it here" and "…because she typed it" are otherwise the same two
     * non-blank strings — and the cascade's guard refuses to set out over either, which turned a
     * successful discovery into a permanent failure ([presetForAddress]).
     */
    val discoveredFor: String? = null,
) {
    /**
     * Whether the manual server block is on screen. It is hidden under OAuth on purpose — host,
     * port and security mean nothing to XOAUTH2 and the user cannot know them — but hiding it was
     * only ever safe once the chip could be disarmed to bring it back.
     */
    val serverFieldsVisible: Boolean get() = !oauth

    companion object {
        /** No provider selected: empty hosts, the form's default ports, no app-password link. */
        val NONE = PresetForm()
    }
}

/** The values [provider] puts in the form, built fresh so no field survives from a previous pick. */
private fun MailProvider.asForm(): PresetForm = if (oauth) {
    // The host/port fields stay empty and hidden, and no app-password link is offered: Microsoft
    // refuses password IMAP, so such a link would send the user to a dead end.
    PresetForm(selected = name, oauth = true)
} else {
    PresetForm(
        selected = name,
        oauth = false,
        imapHost = imapHost,
        imapPort = imapPort,
        imapSecurity = imapSecurity,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpSecurity = smtpSecurity,
        appPasswordUrl = appPasswordUrl,
    )
}

/**
 * What a tap on a quick-setup chip yields. Tapping the chip that is already selected clears the
 * selection, which is the whole fix for #105: the chips carry a visible selected state and the
 * OAuth one is no longer a one-way door.
 */
internal fun presetChipTapped(current: PresetForm, tapped: MailProvider): PresetForm =
    if (current.selected == tapped.name) PresetForm.NONE else tapped.asForm()

/**
 * Choosing a protocol disarms an OAuth preset. The Microsoft device flow is an IMAP-side path, so a
 * preset left armed while JMAP is selected describes nothing the JMAP form can do (#105: it hid the
 * API-token field and sent Connect to the browser while the screen said JMAP).
 *
 * Non-OAuth presets survive, so a round trip through the protocol chips does not wipe the host and
 * port a user just filled in.
 */
internal fun presetForProtocol(preset: PresetForm, protocol: MailProtocol): PresetForm =
    if (protocol == MailProtocol.JMAP && preset.oauth) PresetForm.NONE else preset

/** Where the Connect button sends the form. */
internal enum class ConnectRoute {
    /** Microsoft device flow (XOAUTH2 over IMAP/SMTP), reachable only from the Outlook chip. */
    OUTLOOK_OAUTH,

    /** JMAP with a server-issued API token (Bearer) typed in the secret field. */
    JMAP_TOKEN,

    /** JMAP password sign-in; the server is discovered from the address' domain. */
    JMAP_AUTODISCOVER,

    /** JMAP password sign-in against the server the user typed under Advanced. */
    JMAP_SERVER,

    /** IMAP + SMTP password sign-in against the host/port fields. */
    IMAP_PASSWORD,
}

/**
 * Which sign-in the Connect button runs, given the chip state and the chosen protocol.
 *
 * The OAuth branch is gated on the protocol as well as the flag, so even a preset that somehow
 * stayed armed cannot claim a JMAP form — the belt to [presetForProtocol]'s braces.
 */
internal fun connectRoute(
    preset: PresetForm,
    protocol: MailProtocol,
    useApiToken: Boolean,
    server: String,
): ConnectRoute = when {
    protocol == MailProtocol.IMAP && preset.oauth -> ConnectRoute.OUTLOOK_OAUTH
    protocol == MailProtocol.IMAP -> ConnectRoute.IMAP_PASSWORD
    useApiToken -> ConnectRoute.JMAP_TOKEN
    server.isBlank() -> ConnectRoute.JMAP_AUTODISCOVER
    else -> ConnectRoute.JMAP_SERVER
}

/**
 * Whether Connect has enough to run the [route] it would take. OAuth needs only the address (the
 * browser collects the rest); IMAP needs the four server values it will dial; the JMAP routes need
 * the secret typed in the password/token field.
 *
 * Ports come straight from the text fields, so a non-numeric one counts as missing.
 */
internal fun connectReady(
    route: ConnectRoute,
    username: String,
    password: String,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
): Boolean = when (route) {
    ConnectRoute.OUTLOOK_OAUTH -> username.isNotBlank()
    ConnectRoute.IMAP_PASSWORD -> username.isNotBlank() && password.isNotBlank() &&
        imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
    else -> username.isNotBlank() && password.isNotBlank()
}

// ---- The three steps of adding an account ------------------------------------------------------

/**
 * Which page of the add-account walk is on screen. The screen used to open on the protocol
 * question (JMAP / IMAP-SMTP) — knowledge the user does not have, and which the probes can settle
 * without her — so the walk is: the address, then what discovery decided, and today's form only
 * when discovery came back empty.
 */
internal enum class ConnectStep {
    /** The address, alone. Nothing else is asked until it is a well-formed one. */
    ADDRESS,

    /** What discovery decided, plus the secret it needs. Renders ONLY with a verdict in hand. */
    CREDENTIALS,

    /** Today's form, prefilled — the fallback, reached only when discovery found nothing. */
    MANUAL,
}

/**
 * Whether the address step may be left: the app's shared [isValidEmail] rule and nothing else. A
 * second address rule here would drift from the one the address field itself flags errors with,
 * and from the one [shouldDiscoverImapSettings] gates the cascade on.
 */
internal fun canLeaveAddressStep(email: String): Boolean = isValidEmail(email)

/**
 * Where the address step lands once discovery has RENDERED its verdict: on the credentials when it
 * learned something, on the manual form when it did not. "Configure manually" is what a failure
 * yields — and only a failure: offered beside a working verdict it would put the protocol question
 * back on screen, which is the whole thing this walk removes.
 *
 * IT TAKES BOTH VERDICTS, because the address step obtains both: one pass over the address runs
 * the autoconfig cascade and the OAuth search together ([AddressProbe]), and the ViewModel
 * publishes both. A domain served over JMAP + OAuth that publishes no Thunderbird document
 * (#55) has a perfectly good answer in [oauth] — the credentials step can hand it straight over —
 * and reading [discovery] alone threw that answer away and dropped the reader on the manual form,
 * where she must know her own hostnames for a server the app had already found.
 *
 * [oauthOpensCredentials], not "an OAuth answer exists": a server advertising a grant this app
 * cannot drive leads to a password field with no endpoints behind it here. See that function.
 *
 * Reached from [addressAdvance], which is what the address step's button and its wait both call.
 */
internal fun stepAfterAddress(
    discovery: MailAutoconfigResult,
    oauth: OAuthDiscovery?,
    email: String,
): ConnectStep =
    if (discovery is MailAutoconfigResult.Found || oauthOpensCredentials(oauth, email)) {
        ConnectStep.CREDENTIALS
    } else {
        ConnectStep.MANUAL
    }

/**
 * The step actually RENDERED, given the [remembered] one and the verdict currently in hand.
 *
 * The step index survives process death (`rememberSaveable`); the answers do not — they live in a
 * ViewModel with no `SavedStateHandle`, so they come back null. The credentials step therefore
 * renders only while ONE OF THEM is still there: a password field for a server nobody discovered,
 * or a hand-over for a server nobody found, shows a state that is not true (WYSIWYG) and its
 * button leads nowhere. With neither answer we fall back to the address step, from which both
 * probes run again.
 *
 * AN OAUTH ANSWER COUNTS AS MUCH AS A [MailAutoconfigResult.Found] HERE, and it must, or the
 * walk loops. Once [stepAfterAddress] sends a domain served over OAuth alone to the credentials
 * step (#55), reading [discovered] on its own sends it straight back to the address step the very
 * frame it arrives — the ViewModel holds no settings verdict for it and never will.
 *
 * The distinction this function is FOR is unchanged, and it is between a rotation and a process
 * death: a rotation keeps the ViewModel, so the answers ([discovered], [oauth]) are still there,
 * the credentials step holds and nothing typed is lost; a process death empties it (no
 * `SavedStateHandle`) while the step index survives (`rememberSaveable`), so both come back null
 * and the walk falls back to the address step, from which both probes run again. What must never
 * render is a password field for a server nobody discovered — a state that is not true (WYSIWYG)
 * whose button leads nowhere.
 *
 * The manual step needs no verdict: everything it shows is the form's own saved state.
 */
internal fun stepToRender(
    remembered: ConnectStep,
    discovered: MailAutoconfigResult.Found?,
    oauth: OAuthDiscovery?,
    email: String,
): ConnectStep =
    if (remembered == ConnectStep.CREDENTIALS &&
        discovered == null &&
        !oauthOpensCredentials(oauth, email)
    ) {
        ConnectStep.ADDRESS
    } else {
        remembered
    }

/**
 * Where Back goes from [current]: the two later steps return to the address, and Back from the
 * address leaves the screen — `null`, said here rather than guessed at the call site.
 */
internal fun stepBack(current: ConnectStep): ConnectStep? =
    if (current == ConnectStep.ADDRESS) null else ConnectStep.ADDRESS

/**
 * Whether the autoconfig cascade may set out for [email] at all. True only when the form on screen
 * is one the verdict can serve — the [ConnectStep.ADDRESS] step, where the cascade IS the point, or
 * the IMAP password form of the manual fallback — with no quick-setup chip armed (the hard-coded
 * providers outrank the cascade — and an OAuth chip has no host fields to fill), BOTH host fields
 * untouched, an address the app's shared [isValidEmail] rule accepts, and the device [online]:
 * offline the cascade must not set out at all, silently — no error state for a probe nobody asked
 * for.
 *
 * The route condition is lifted at the address step ONLY, because that is the step where no
 * protocol has been chosen yet — requiring one first is exactly what this walk removes. It stays
 * for every other step: under the manual form with JMAP chosen there are no IMAP fields for the
 * verdict to land in.
 *
 * A pure function, so a test executes the decision instead of re-reading the code around it.
 */
internal fun shouldDiscoverImapSettings(
    step: ConnectStep,
    route: ConnectRoute,
    preset: PresetForm,
    email: String,
    online: Boolean,
): Boolean = (step == ConnectStep.ADDRESS || route == ConnectRoute.IMAP_PASSWORD) &&
    preset.selected == null &&
    !preset.oauth &&
    preset.imapHost.isBlank() &&
    preset.smtpHost.isBlank() &&
    isValidEmail(email) &&
    online

/**
 * The discovered settings written into the form — per SIDE, atomically: the incoming trio
 * (host + port + security, together) lands only while [PresetForm.imapHost] is untouched, the
 * outgoing trio likewise on [PresetForm.smtpHost]. A hand-typed host is never overwritten, and
 * never has a discovered port or security slid under it: half the cascade's verdict under half
 * the user's is a configuration nobody wrote. `selected`/`oauth`/`appPasswordUrl` are never
 * touched. The verdict's username is deliberately NOT applied anywhere: the address field keeps
 * what the user typed (WYSIWYG — no value may be used without being shown).
 */
internal fun presetFilledWith(
    preset: PresetForm,
    found: MailAutoconfigResult.Found,
    email: String,
): PresetForm {
    var filled = preset
    // [PresetForm.discoveredFor] is stamped by the ONE function that writes discovered values,
    // and ONLY on a side it really wrote: provenance cannot be forgotten at a call site, and a
    // form this fills nothing into is never marked as the cascade's work.
    if (preset.imapHost.isBlank()) {
        filled = filled.copy(
            imapHost = found.incoming.host,
            imapPort = found.incoming.port.toString(),
            imapSecurity = found.incoming.security,
            discoveredFor = email.trim(),
        )
    }
    if (preset.smtpHost.isBlank()) {
        filled = filled.copy(
            smtpHost = found.outgoing.host,
            smtpPort = found.outgoing.port.toString(),
            smtpSecurity = found.outgoing.security,
            discoveredFor = email.trim(),
        )
    }
    return filled
}
