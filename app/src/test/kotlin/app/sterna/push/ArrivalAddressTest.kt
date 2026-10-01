package app.sterna.push

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [arrivalAddress], executed: the address a notification names in place of the account (#243) is
 * the one the reader's header would name (#180), and nothing on an account that has only one.
 */
class ArrivalAddressTest {

    private fun identity(address: String) = StoredIdentity(id = address, name = "", email = address)

    private fun mail(
        to: List<String> = emptyList(),
        cc: List<String> = emptyList(),
        from: String = "tiers@ailleurs.test",
    ) = Email(
        id = "m",
        from = listOf(EmailAddress(email = from)),
        to = to.map { EmailAddress(email = it) },
        cc = cc.map { EmailAddress(email = it) },
    )

    private val two = listOf(identity(ME), identity(ALIAS))

    @Test fun `one identity and a different login name nothing, whichever of the two is written to`() {
        val one = listOf(identity(ME))
        assertNull(arrivalAddress(mail(to = listOf(LOGIN)), one, LOGIN, folderName = null))
        assertNull(arrivalAddress(mail(to = listOf(ME)), one, LOGIN, folderName = null))
    }

    @Test fun `two identities, a mail to a list names nothing`() {
        assertNull(arrivalAddress(mail(to = listOf("liste@listes.test"), cc = listOf("autre@x.test")), two, LOGIN, folderName = null))
    }

    @Test fun `an alias in the Cc alone is named, spelled as the message spells it`() {
        assertEquals(
            "Alias@Ex.Test",
            arrivalAddress(mail(to = listOf("liste@listes.test"), cc = listOf("Alias@Ex.Test")), two, LOGIN, folderName = null),
        )
    }

    @Test fun `a message of one's own landing in the inbox names where it arrived, as the reader does`() {
        assertEquals(ME, arrivalAddress(mail(to = listOf(ME), from = ALIAS), two, LOGIN, folderName = null))
    }

    @Test fun `a message of one's own outside the inbox names nothing`() {
        assertNull(arrivalAddress(mail(to = listOf(ME), from = ALIAS), two, LOGIN, folderName = ARCHIVE))
    }

    @Test fun `the alias written to is named, not the default identity`() {
        assertEquals(ALIAS, arrivalAddress(mail(to = listOf(ALIAS)), two, LOGIN, folderName = null))
        assertEquals(ME, arrivalAddress(mail(to = listOf(ME)), two, LOGIN, folderName = null))
    }

    @Test fun `the login named first is searched as the reader searches it, and then refused`() {
        assertNull(arrivalAddress(mail(to = listOf(LOGIN), cc = listOf(ALIAS)), two, LOGIN, folderName = null))
    }

    private companion object {
        const val ME = "moi@ex.test"
        const val ALIAS = "alias@ex.test"
        const val LOGIN = "identifiant@ex.test"
        const val ARCHIVE = "Archive"
    }
}
