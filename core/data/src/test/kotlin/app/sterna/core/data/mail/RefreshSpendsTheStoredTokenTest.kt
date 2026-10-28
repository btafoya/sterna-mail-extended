package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * A server that rotates refresh tokens revokes the grant when a superseded one comes back, so a
 * refresh must spend the refresh token the store holds now, never the one in the credentials a
 * long-lived caller (the push service) was handed hours ago, and two refreshes must not race.
 */
class RefreshSpendsTheStoredTokenTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** Holds one OAuth login the way AccountStore does: the latest tokens, read back on demand. */
    private inner class TokenStore(var oauth: OAuthCredentials) : RecordingAccountStorage() {
        override fun credentials(id: String): AccountCredentials? =
            if (id == ID) credentialsWith(oauth) else null

        override fun updateOAuthTokens(id: String, accessToken: String, refreshToken: String, accessExpiresAtMillis: Long) {
            oauth = oauth.copy(
                accessToken = accessToken,
                refreshToken = refreshToken.ifBlank { oauth.refreshToken },
                accessExpiresAtMillis = accessExpiresAtMillis,
            )
        }
    }

    private fun oauth(access: String, refresh: String, expiresAt: Long) = OAuthCredentials(
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAtMillis = expiresAt,
        tokenEndpoint = server.url("/auth/token").toString(),
        clientId = "sterna",
    )

    private fun credentialsWith(oauth: OAuthCredentials) =
        AccountCredentials(server = "https://mail.example", username = "me@example", password = "", id = ID, oauth = oauth)

    private fun enqueueTokens(access: String, refresh: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":3600}"""),
        )
    }

    private fun spentRefreshToken(): String {
        val body = server.takeRequest().body.readUtf8()
        val pair = body.split("&").first { it.startsWith("refresh_token=") }
        return URLDecoder.decode(pair.substringAfter("="), "UTF-8")
    }

    private val expired get() = System.currentTimeMillis() - 1_000
    private val valid get() = System.currentTimeMillis() + 3_600_000

    @Test fun anExpiredSnapshotSpendsTheStoredRefreshTokenNotItsOwn() = runBlocking {
        // The push service armed with RT0; the screen has since rotated the login to RT1.
        val snapshot = credentialsWith(oauth("AT0", "RT0", expired))
        val store = TokenStore(oauth("AT1", "RT1", expired))
        enqueueTokens("AT2", "RT2")

        val token = OAuthTokenRefresher(OAuthClient(), store).freshAccessToken(snapshot)

        assertEquals("RT1", spentRefreshToken())
        assertEquals("AT2", token)
        assertEquals("RT2", store.oauth.refreshToken)
    }

    @Test fun anExpiredSnapshotUsesTheStoredAccessTokenWhileItIsValid() = runBlocking {
        val snapshot = credentialsWith(oauth("AT0", "RT0", expired))
        val store = TokenStore(oauth("AT1", "RT1", valid))
        enqueueTokens("AT9", "RT9")

        val token = OAuthTokenRefresher(OAuthClient(), store).freshAccessToken(snapshot)

        assertEquals("AT1", token)
        assertEquals(0, server.requestCount)
    }

    @Test fun concurrentRefreshesSpendTheRefreshTokenOnce() = runBlocking {
        val store = TokenStore(oauth("AT0", "RT0", expired))
        val snapshot = credentialsWith(store.oauth)
        enqueueTokens("AT1", "RT1")
        enqueueTokens("AT2", "RT2")

        // Two refreshers, as the app builds: the repository's and the IMAP service's.
        val tokens = listOf(
            OAuthTokenRefresher(OAuthClient(), store),
            OAuthTokenRefresher(OAuthClient(), store),
        ).map { refresher -> async(Dispatchers.IO) { refresher.freshAccessToken(snapshot) } }
            .awaitAll()

        assertEquals(listOf("AT1", "AT1"), tokens)
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val ID = "acct"
    }
}
