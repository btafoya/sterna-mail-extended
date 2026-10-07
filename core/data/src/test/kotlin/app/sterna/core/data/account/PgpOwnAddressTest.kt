package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The address handed to the OpenPGP provider when a login is not an address (#246). */
class PgpOwnAddressTest {

    private fun account(
        username: String,
        identities: List<StoredIdentity> = emptyList(),
        defaultIdentityId: String? = null,
    ) = StoredAccount(
        id = "acc",
        server = "example.test",
        username = username,
        identities = identities,
        defaultIdentityId = defaultIdentityId,
    )

    private fun identity(id: String, email: String) = StoredIdentity(id = id, name = id, email = email)

    @Test fun `a bare login with an identity gives the identity's address`() {
        val acc = account("john.doe", listOf(identity("main", "john.doe@example.test")))

        assertEquals("john.doe@example.test", pgpOwnAddress(acc))
    }

    @Test fun `a login that is an address, with no identity, gives the login`() {
        assertEquals("john.doe@example.test", pgpOwnAddress(account("john.doe@example.test")))
    }

    @Test fun `a bare login with no identity gives nothing`() {
        assertNull(pgpOwnAddress(account("john.doe")))
    }

    @Test fun `the chosen default identity wins over the others`() {
        val acc = account(
            "john.doe",
            listOf(identity("work", "work@example.test"), identity("personal", "me@example.test")),
            defaultIdentityId = "personal",
        )

        assertEquals("me@example.test", pgpOwnAddress(acc))
    }

    @Test fun `a login with whitespace or two at-signs, with no identity, gives nothing`() {
        assertNull(pgpOwnAddress(account("john doe@example.test")))
        assertNull(pgpOwnAddress(account("john@doe@example.test")))
    }

    @Test fun `an implausible default identity falls back to a login that is an address`() {
        val acc = account("john.doe@example.test", listOf(identity("broken", "john.doe")))

        assertEquals("john.doe@example.test", pgpOwnAddress(acc))
    }

    @Test fun `the address is trimmed`() {
        val acc = account("john.doe", listOf(identity("main", "  john.doe@example.test ")))

        assertEquals("john.doe@example.test", pgpOwnAddress(acc))
        assertEquals("john.doe@example.test", pgpOwnAddress(account(" john.doe@example.test ")))
    }
}
