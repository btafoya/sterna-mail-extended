package app.sterna.push

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [rowArrivalAddress], executed: the address a list row names (#243), and every case it names none. */
class RowArrivalAddressTest {

    private fun identity(address: String) = StoredIdentity(id = address, name = "", email = address)

    private fun mail(to: List<String> = emptyList(), cc: List<String> = emptyList()) = Email(
        id = "m",
        from = listOf(EmailAddress(email = "tiers@ailleurs.test")),
        to = to.map { EmailAddress(email = it) },
        cc = cc.map { EmailAddress(email = it) },
    )

    private val two = listOf(identity(ME), identity(ALIAS))

    @Test fun `two identities and the alias in To name the alias, spelled as the message spells it`() {
        assertEquals("Alias@Ex.Test", rowArrivalAddress(mail(to = listOf("Alias@Ex.Test")), two, LOGIN, showRecipients = false))
        assertEquals(ME, rowArrivalAddress(mail(to = listOf(ME)), two, LOGIN, showRecipients = false))
    }

    @Test fun `the alias in the Cc alone is named`() {
        assertEquals(
            ALIAS,
            rowArrivalAddress(mail(to = listOf("liste@listes.test"), cc = listOf(ALIAS)), two, LOGIN, showRecipients = false),
        )
    }

    @Test fun `To is read before Cc`() {
        assertEquals(ME, rowArrivalAddress(mail(to = listOf(ME), cc = listOf(ALIAS)), two, LOGIN, showRecipients = false))
    }

    @Test fun `a mail to a list names nothing`() {
        assertNull(
            rowArrivalAddress(mail(to = listOf("liste@listes.test"), cc = listOf("autre@x.test")), two, LOGIN, showRecipients = false),
        )
    }

    @Test fun `one identity and a different login name nothing, whichever of the two is written to`() {
        val one = listOf(identity(ME))
        assertNull(rowArrivalAddress(mail(to = listOf(ME)), one, LOGIN, showRecipients = false))
        assertNull(rowArrivalAddress(mail(to = listOf(LOGIN)), one, LOGIN, showRecipients = false))
    }

    @Test fun `the login written to is not an arrival, even beside two identities`() {
        assertNull(rowArrivalAddress(mail(to = listOf(LOGIN)), two, LOGIN, showRecipients = false))
    }

    @Test fun `a row shown by its recipients names nothing`() {
        assertNull(rowArrivalAddress(mail(to = listOf(ALIAS)), two, LOGIN, showRecipients = true))
    }

    @Test fun `a row cached without To and Cc names nothing`() {
        assertNull(rowArrivalAddress(mail(), two, LOGIN, showRecipients = false))
    }

    private companion object {
        const val ME = "moi@ex.test"
        const val ALIAS = "alias@ex.test"
        const val LOGIN = "identifiant@ex.test"
    }
}
