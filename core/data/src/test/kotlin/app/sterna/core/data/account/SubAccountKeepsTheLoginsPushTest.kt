package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one predicate both ends of a shared subscription read (#31): the teardown that refuses to
 * hand a login's subscription back, and the fallback cycle that owes that same login a renewal.
 * Run here rather than read: [AccountStore] needs shared preferences and cannot be built off a
 * device, so the decision hangs off [AccountStorage] and a recording double answers for storage.
 */
class SubAccountKeepsTheLoginsPushTest {

    private val login = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun sub(id: String, on: Boolean, loginId: String = "login-uuid") = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        accountName = "$id@example.org",
        loginId = loginId,
        jmapAccountId = id,
        notificationsEnabled = on,
    )

    /** Storage holding [all]; every other answer is the recording double's. */
    private class Storage(private val all: List<StoredAccount>) : RecordingAccountStorage() {
        override fun linkedAccounts(loginId: String): List<StoredAccount> {
            super.linkedAccounts(loginId)
            return all.filter { it.loginId == loginId }
        }
    }

    /**
     * The case the volet exists for: the user switched the LOGIN off and left the shared mailbox
     * on. The subscription lives on for the sub-account, so it is owed a renewal, and the login's
     * own switch is not part of the answer.
     */
    @Test fun aSubAccountLeftOnKeepsThePushOfALoginSwitchedOff() {
        val storage = Storage(listOf(login.copy(notificationsEnabled = false), sub("jordan", on = true)))

        assertTrue(storage.keepsPushForLinkedAccount("login-uuid"))
    }

    /** Same answer with the login's own switch on: the predicate never reads the login's record. */
    @Test fun theLoginsOwnSwitchChangesNothing() {
        val on = Storage(listOf(login.copy(notificationsEnabled = true), sub("jordan", on = true)))
        val off = Storage(listOf(login.copy(notificationsEnabled = false), sub("jordan", on = true)))

        assertEquals(on.keepsPushForLinkedAccount("login-uuid"), off.keepsPushForLinkedAccount("login-uuid"))
        assertEquals(
            "the login's own id is the only argument the storage may be asked about",
            mapOf("loginId" to "login-uuid"),
            off.args("linkedAccounts"),
        )
    }

    /** One sub-account still on is enough: the subscription is a single one for the whole set. */
    @Test fun oneSubAccountOnAmongSeveralIsEnough() {
        val storage = Storage(listOf(login, sub("jordan", on = false), sub("team", on = true)))

        assertTrue(storage.keepsPushForLinkedAccount("login-uuid"))
    }

    /** Every sub-account switched off: nothing is left for the subscription to serve. */
    @Test fun allSubAccountsSwitchedOffKeepNothing() {
        val storage = Storage(listOf(login, sub("jordan", on = false), sub("team", on = false)))

        assertFalse(storage.keepsPushForLinkedAccount("login-uuid"))
    }

    /** A standalone login has nobody to keep its subscription for; it answers for itself. */
    @Test fun aLoginWithNoSubAccountKeepsNothing() {
        assertFalse(Storage(listOf(login)).keepsPushForLinkedAccount("login-uuid"))
    }

    /**
     * The set is the login's own sub-accounts, never every account on the device: another login's
     * shared mailbox left on would otherwise keep a subscription that carries none of its mail.
     */
    @Test fun anotherLoginsSubAccountKeepsNothingHere() {
        val other = login.copy(id = "sam-uuid", username = "sam@example.org")
        val storage = Storage(listOf(login, other, sub("ops", on = true, loginId = "sam-uuid")))

        assertFalse(storage.keepsPushForLinkedAccount("login-uuid"))
        assertTrue(storage.keepsPushForLinkedAccount("sam-uuid"))
    }
}
