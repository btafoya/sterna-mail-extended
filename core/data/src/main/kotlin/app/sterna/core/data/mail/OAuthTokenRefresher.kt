package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Hands out a valid OAuth access token, refreshing and persisting it within a minute of expiry. */
class OAuthTokenRefresher(
    private val oauthClient: OAuthClient,
    private val accountStore: AccountStorage,
) {
    /** A fresh access token for [credentials], or null when it isn't an OAuth account. */
    suspend fun freshAccessToken(credentials: AccountCredentials): String? {
        if (credentials.oauth == null) return null
        return refreshLock.withLock {
            // One lock per process, as the repository and IMAP each own a refresher. Tokens come from the store:
            // push holds its copy for hours, and a reused refresh token revokes the grant (RFC 9700 §4.14.2).
            val oauth = accountStore.credentials(credentials.id)?.oauth ?: credentials.oauth
            if (oauth.accessToken.isNotBlank() &&
                oauth.accessExpiresAtMillis - System.currentTimeMillis() > 60_000
            ) {
                return@withLock oauth.accessToken
            }
            val tokens = oauthClient.refresh(oauth.tokenEndpoint, oauth.refreshToken, oauth.clientId)
            val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
            accountStore.updateOAuthTokens(
                credentials.id,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
            )
            tokens.accessToken
        }
    }

    private companion object {
        val refreshLock = Mutex()
    }
}
