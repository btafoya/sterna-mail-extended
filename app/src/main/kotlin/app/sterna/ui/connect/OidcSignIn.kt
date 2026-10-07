package app.sterna.ui.connect

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.OAuthMetadata

// The decisions of a sign-in at an OpenID Connect issuer the user typed (#249). Nothing here
// touches Android: the screen's ViewModel and the code-grant driver run them.

/** What a typed issuer and a typed client id are worth, before the browser is involved. */
internal sealed interface IssuerSignIn {
    /** No client id was typed. Nothing was asked of anyone. */
    data object NoClientId : IssuerSignIn

    /** The issuer gave no configuration a sign-in can be driven from. Which reason is not known. */
    data object NoConfiguration : IssuerSignIn

    data class Ready(val metadata: OAuthMetadata, val clientId: String) : IssuerSignIn
}

/**
 * The client id is judged before [discover] runs, so a sign-in that cannot be asked for sends no
 * request at all. There is no falling back on Sterna's own id: an identity provider knows the
 * clients registered with it and no other, and would refuse it on a page the user cannot act on.
 */
internal suspend fun issuerSignInFor(
    issuer: String,
    clientId: String,
    discover: suspend (String) -> OAuthMetadata?,
): IssuerSignIn {
    val typedClientId = clientId.trim()
    if (typedClientId.isEmpty()) return IssuerSignIn.NoClientId
    val metadata = discover(issuer) ?: return IssuerSignIn.NoConfiguration
    return IssuerSignIn.Ready(metadata, typedClientId)
}

/** Where the JMAP session is asked once the tokens are in hand, or why it cannot be. */
internal sealed interface JmapHostChoice {
    data class Found(val host: String) : JmapHostChoice

    /** The sign-in itself succeeded and no account can be added. */
    sealed interface Missing : JmapHostChoice

    /** No server was typed and none answered for the address. */
    data object NoServer : Missing

    /** The address' own domain answered, and refused the token with a 401 or a 403. */
    data object TokenRefused : Missing
}

/**
 * [requested] is [PendingAuthorization.host]: the host the OAuth document came from when the
 * server was discovered, the JMAP server field when the issuer was typed. Blank only happens on
 * the second, and means the server is to be found from the address, by [discover].
 *
 * The issuer is not a parameter, on purpose. Whatever this finds is sent the access token as a
 * JMAP credential, and the issuer is another host than the mail server: it must never be handed
 * its own token that way, nor end up as the server the account is stored under.
 *
 * A refusal is kept apart from "no server": the mail server exists and does not accept this
 * issuer's tokens, which typing a server would not change.
 */
internal suspend fun jmapHostAfterSignIn(
    requested: String,
    discover: suspend () -> MailRepository.DiscoveryResult,
): JmapHostChoice {
    val typed = requested.trim()
    if (typed.isNotEmpty()) return JmapHostChoice.Found(typed)
    return when (val discovered = discover()) {
        is MailRepository.DiscoveryResult.Found ->
            if (discovered.server.isBlank()) JmapHostChoice.NoServer else JmapHostChoice.Found(discovered.server)
        MailRepository.DiscoveryResult.BadCredentials -> JmapHostChoice.TokenRefused
        MailRepository.DiscoveryResult.NotFound -> JmapHostChoice.NoServer
    }
}

/** What the user is told when the browser trip succeeded and no account follows. */
@StringRes
internal fun noJmapHostSentence(missing: JmapHostChoice.Missing): Int = when (missing) {
    JmapHostChoice.NoServer -> R.string.connect_oidc_no_server
    JmapHostChoice.TokenRefused -> R.string.connect_oidc_token_refused
}

/** What the manual form's OAuth button does, given what its two issuer fields hold. */
internal sealed interface OAuthButton {
    /** No issuer was typed: the mail server is asked for its own OAuth. */
    data object ServerOAuth : OAuthButton

    /** Both values, trimmed. */
    data class TypedIssuer(val issuer: String, val clientId: String) : OAuthButton

    /** An issuer and no client id: the button is dead rather than a tap that asks nothing. */
    data object NeedsClientId : OAuthButton
}

/**
 * The issuer alone picks the route. A client id typed without an issuer changes nothing: sent to
 * a mail server's own OAuth it would replace the one id that server knows Sterna by.
 */
internal fun oauthButtonFor(issuer: String, clientId: String): OAuthButton {
    val typedIssuer = issuer.trim()
    if (typedIssuer.isEmpty()) return OAuthButton.ServerOAuth
    val typedClientId = clientId.trim()
    if (typedClientId.isEmpty()) return OAuthButton.NeedsClientId
    return OAuthButton.TypedIssuer(typedIssuer, typedClientId)
}

internal fun oauthButtonEnabled(button: OAuthButton, busy: Boolean, username: String): Boolean =
    button != OAuthButton.NeedsClientId && !busy && username.isNotBlank()
