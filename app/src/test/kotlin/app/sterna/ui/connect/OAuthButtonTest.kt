package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes what the manual form's OAuth button decides from its two issuer fields (#249). */
class OAuthButtonTest {

    @Test fun `no issuer leaves the button on the mail server's own OAuth`() {
        listOf("", "   ", "\t\n").forEach { blank ->
            assertEquals(OAuthButton.ServerOAuth, oauthButtonFor(blank, ""))
        }
    }

    @Test fun `a client id typed without an issuer does not change the route`() {
        assertEquals(OAuthButton.ServerOAuth, oauthButtonFor("  ", "sterna-at-home"))
    }

    @Test fun `a typed issuer takes the button to that issuer, both values trimmed`() {
        assertEquals(
            OAuthButton.TypedIssuer("https://idm.example.org/oauth2/openid/mail", "mail-client"),
            oauthButtonFor("  https://idm.example.org/oauth2/openid/mail\n", " mail-client "),
        )
    }

    @Test fun `a typed issuer without a client id is neither route`() {
        listOf("", "   ", "\t\n").forEach { blank ->
            assertEquals(OAuthButton.NeedsClientId, oauthButtonFor("https://idm.example.org", blank))
        }
    }

    @Test fun `the button is dead while the client id is missing`() {
        assertFalse(oauthButtonEnabled(OAuthButton.NeedsClientId, busy = false, username = "user@example.org"))
    }

    @Test fun `both routes are live once there is an address and nothing in flight`() {
        val routes = listOf(OAuthButton.ServerOAuth, OAuthButton.TypedIssuer("https://idm.example.org", "mail-client"))
        routes.forEach { route ->
            assertTrue("$route", oauthButtonEnabled(route, busy = false, username = "user@example.org"))
            assertFalse("$route, busy", oauthButtonEnabled(route, busy = true, username = "user@example.org"))
            assertFalse("$route, no address", oauthButtonEnabled(route, busy = false, username = "  "))
        }
    }
}
