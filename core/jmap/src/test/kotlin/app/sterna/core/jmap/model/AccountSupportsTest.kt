package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JmapSession.accountSupports] (#286): what an account can do is read in its own
 * `accountCapabilities` (RFC 8620 §2). Stalwart keeps a capability in the session-wide map after an
 * admin withdraws it from one user, and only that user's account map drops it.
 */
class AccountSupportsTest {

    private val empty = buildJsonObject { }

    private fun account(vararg capabilities: String) =
        JmapAccount(name = "acc", accountCapabilities = capabilities.associateWith { empty })

    private fun session(accounts: Map<String, JmapAccount>, vararg global: String) = JmapSession(
        apiUrl = "https://mail.example.com/jmap/api/",
        capabilities = global.associateWith { empty },
        accounts = accounts,
    )

    @Test fun capabilityOnTheAccountIsSupported() {
        val s = session(mapOf("a" to account(Jmap.MAIL_CAPABILITY, Jmap.SIEVE_CAPABILITY)), Jmap.MAIL_CAPABILITY)

        assertTrue(s.accountSupports("a", Jmap.SIEVE_CAPABILITY))
    }

    @Test fun globalOnlyWithAnAccountMapWithoutItIsNotSupported() {
        val s = session(
            mapOf("a" to account(Jmap.MAIL_CAPABILITY)),
            Jmap.MAIL_CAPABILITY, Jmap.SIEVE_CAPABILITY, Jmap.VACATION_CAPABILITY,
        )

        assertFalse(s.accountSupports("a", Jmap.SIEVE_CAPABILITY))
        assertFalse(s.accountSupports("a", Jmap.VACATION_CAPABILITY))
    }

    @Test fun globalOnlyWithAnAccountSilentOnCapabilitiesIsSupported() {
        val s = session(mapOf("a" to JmapAccount(name = "acc")), Jmap.SIEVE_CAPABILITY)

        assertTrue(s.accountSupports("a", Jmap.SIEVE_CAPABILITY))
    }

    @Test fun globalOnlyWithAnUnknownAccountIsSupported() {
        val s = session(mapOf("a" to account(Jmap.MAIL_CAPABILITY)), Jmap.SIEVE_CAPABILITY)

        assertTrue(s.accountSupports("elsewhere", Jmap.SIEVE_CAPABILITY))
    }

    @Test fun absentEverywhereIsNotSupported() {
        val s = session(mapOf("a" to JmapAccount(name = "acc")), Jmap.MAIL_CAPABILITY)

        assertFalse(s.accountSupports("a", Jmap.SIEVE_CAPABILITY))
        assertFalse(s.accountSupports("elsewhere", Jmap.SIEVE_CAPABILITY))
    }

    @Test fun aSharedAccountIsJudgedApartFromThePrimary() {
        val s = session(
            mapOf(
                "own" to account(Jmap.MAIL_CAPABILITY, Jmap.QUOTA_CAPABILITY),
                "shared" to account(Jmap.MAIL_CAPABILITY),
            ),
            Jmap.MAIL_CAPABILITY, Jmap.QUOTA_CAPABILITY,
        )

        assertTrue(s.accountSupports("own", Jmap.QUOTA_CAPABILITY))
        assertFalse(s.accountSupports("shared", Jmap.QUOTA_CAPABILITY))
    }
}
