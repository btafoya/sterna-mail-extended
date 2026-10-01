package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RFC 9661 § 1.3: the Sieve extensions a JMAP server runs are an account capability, read from the session as sent. */
class JmapSieveExtensionsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun session(account: String, top: String = "{}") = json.decodeFromString(
        JmapSession.serializer(),
        """
        {
          "apiUrl": "https://mail.example.com/jmap/api/",
          "capabilities": { "${Jmap.SIEVE_CAPABILITY}": $top },
          "primaryAccounts": { "${Jmap.MAIL_CAPABILITY}": "a" },
          "accounts": { "a": { "name": "me@example.com", "accountCapabilities": { "${Jmap.SIEVE_CAPABILITY}": $account } } }
        }
        """.trimIndent(),
    )

    @Test fun `the account's sieveExtensions are read as listed`() {
        val s = session("""{ "maxNumberRedirects": 1, "sieveExtensions": ["fileinto", "imap4flags", "body", "copy"] }""")
        assertEquals(listOf("fileinto", "imap4flags", "body", "copy"), s.sieveExtensions("a"))
    }

    @Test fun `a server that lists nothing announced nothing`() {
        assertNull(session("{}").sieveExtensions("a"))
        assertNull(session("{}").sieveExtensions("unknown"))
    }

    @Test fun `an empty list announces nothing, as silence does`() {
        assertNull(session("""{ "sieveExtensions": [] }""").sieveExtensions("a"))
    }

    @Test fun `without the account's object, the session-level one is read`() {
        val s = json.decodeFromString(
            JmapSession.serializer(),
            """{ "apiUrl": "x", "capabilities": { "${Jmap.SIEVE_CAPABILITY}": { "sieveExtensions": ["body"] } }, "accounts": { "a": { "name": "n" } } }""",
        )
        assertEquals(listOf("body"), s.sieveExtensions("a"))
    }
}
