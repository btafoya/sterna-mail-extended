package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.pgp.PgpMode
import app.sterna.pgp.SmimeRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock with S/MIME beside OpenPGP: what a tap walks, what the menu lists, what a From change keeps. */
class SmimeLockTest {

    private val openPgpModes = listOf(PgpMode.OFF, PgpMode.SIGN, PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED)
    private val both = listOf(PgpMode.SMIME_SIGN, PgpMode.SMIME_ENCRYPT)
    private val signOnly = listOf(PgpMode.SMIME_SIGN)
    private val none = emptyList<PgpMode>()

    @Test fun `an account with S-MIME alone taps from nothing to signing, to encrypting, and back`() {
        assertEquals(PgpMode.SMIME_SIGN, nextLockMode(PgpMode.OFF, openPgp = false, smime = both))
        assertEquals(PgpMode.SMIME_ENCRYPT, nextLockMode(PgpMode.SMIME_SIGN, openPgp = false, smime = both))
        assertEquals(PgpMode.OFF, nextLockMode(PgpMode.SMIME_ENCRYPT, openPgp = false, smime = both))
        assertEquals(PgpMode.OFF, nextLockMode(PgpMode.SMIME_SIGN, openPgp = false, smime = signOnly))
    }

    @Test fun `an account with both keeps the OpenPGP tap it had, and a tap gets out of S-MIME`() {
        for (mode in openPgpModes) {
            assertEquals("from $mode", nextPgpMode(mode), nextLockMode(mode, openPgp = true, smime = both))
            assertEquals("from $mode", nextPgpMode(mode), nextLockMode(mode, openPgp = true, smime = none))
        }
        assertEquals(PgpMode.OFF, nextLockMode(PgpMode.SMIME_SIGN, openPgp = true, smime = both))
        assertEquals(PgpMode.OFF, nextLockMode(PgpMode.SMIME_ENCRYPT, openPgp = true, smime = both))
    }

    @Test fun `the menu lists OpenPGP's modes as before, and S-MIME's offered ones`() {
        assertEquals(openPgpModes, lockMenuModes(openPgp = true, smime = none))
        assertEquals(listOf(PgpMode.OFF) + both, lockMenuModes(openPgp = false, smime = both))
        assertEquals(openPgpModes + both, lockMenuModes(openPgp = true, smime = both))
    }

    @Test fun `encrypting with S-MIME is offered by neither gesture when the certificate may only sign`() {
        for (openPgp in listOf(true, false)) for (smime in listOf(signOnly, none)) {
            assertFalse(PgpMode.SMIME_ENCRYPT in lockMenuModes(openPgp, smime))
            assertTrue(PgpMode.entries.none { nextLockMode(it, openPgp, smime) == PgpMode.SMIME_ENCRYPT })
        }
    }

    @Test fun `a recipient is marked when no valid certificate is kept for them`() {
        assertEquals(
            mapOf("bob@example.test" to true, "carol@example.test" to false),
            smimeCertificatesHeld(listOf("bob@example.test", "carol@example.test"), mapOf("bob@example.test" to byteArrayOf(1)), "alice@example.test"),
        )
        assertEquals(
            "the sender, in To or Cc, is marked as lacking the certificate the envelope already carries",
            mapOf("Alice@Example.test" to true),
            smimeCertificatesHeld(listOf("Alice@Example.test"), emptyMap(), "alice@example.test"),
        )
    }

    @Test fun `encrypting with S-MIME keeps nothing in the clear and gives up what OpenPGP encryption gives up`() {
        assertFalse(draftSaveAllowed(PgpMode.SMIME_ENCRYPT))
        assertFalse(scheduleSendAllowed(PgpMode.SMIME_ENCRYPT))
        assertEquals(R.string.compose_smime_no_schedule, scheduleRefusal("a@example.test", "", "", PgpMode.SMIME_ENCRYPT))
        assertTrue(stagesAttachmentLocally(MailProtocol.JMAP, PgpMode.SMIME_ENCRYPT))
    }

    @Test fun `an S-MIME mode stands on S-MIME being offered, not on OpenPGP`() {
        val kept = lockRefresh(PgpMode.SMIME_SIGN, available = false, accountConfigured = false, encryptByDefault = false, userSet = true, smimeOffered = signOnly)
        assertEquals(PgpRefreshOutcome(PgpMode.SMIME_SIGN, PgpDropReason.NONE), kept)
        val lost = lockRefresh(PgpMode.SMIME_SIGN, available = true, accountConfigured = true, encryptByDefault = true, userSet = true, smimeOffered = none)
        assertEquals(PgpRefreshOutcome(PgpMode.OFF, PgpDropReason.SMIME_UNAVAILABLE), lost)
    }

    @Test fun `encrypting falls back to signing when only signing is offered, and goes off when nothing is`() {
        fun refresh(offered: List<PgpMode>) =
            lockRefresh(PgpMode.SMIME_ENCRYPT, available = false, accountConfigured = false, encryptByDefault = false, userSet = true, smimeOffered = offered)
        assertEquals(PgpRefreshOutcome(PgpMode.SMIME_ENCRYPT, PgpDropReason.NONE), refresh(both))
        assertEquals(
            "a From whose certificate signs but cannot encrypt turned the lock off, as though it could not sign",
            PgpRefreshOutcome(PgpMode.SMIME_SIGN, PgpDropReason.SMIME_NO_LONGER_ENCRYPTED),
            refresh(signOnly),
        )
        assertEquals(PgpRefreshOutcome(PgpMode.OFF, PgpDropReason.SMIME_ENCRYPT_UNAVAILABLE), refresh(none))
    }

    @Test fun `each S-MIME drop says what was given up`() {
        assertEquals(R.string.compose_smime_encrypt_to_sign, lockDropNotice(PgpDropReason.SMIME_NO_LONGER_ENCRYPTED))
        assertEquals(R.string.compose_smime_encrypt_dropped, lockDropNotice(PgpDropReason.SMIME_ENCRYPT_UNAVAILABLE))
        assertEquals(R.string.compose_smime_unavailable, lockDropNotice(PgpDropReason.SMIME_UNAVAILABLE))
        assertEquals(R.string.compose_pgp_not_configured, lockDropNotice(PgpDropReason.NOT_CONFIGURED))
        assertEquals(R.string.message_pgp_no_provider, lockDropNotice(PgpDropReason.NO_PROVIDER))
        assertEquals(null, lockDropNotice(PgpDropReason.NONE))
    }

    @Test fun `an OpenPGP mode is refreshed as it was, whatever S-MIME says`() {
        for (mode in openPgpModes) for (available in listOf(true, false)) for (smime in listOf(both, signOnly, none)) {
            assertEquals(
                pgpRefresh(mode, available, accountConfigured = true, encryptByDefault = false, userSet = true),
                lockRefresh(mode, available, accountConfigured = true, encryptByDefault = false, userSet = true, smimeOffered = smime),
            )
        }
    }

    @Test fun `the rules of a signed message hold in S-MIME, and the refusal does not name OpenPGP`() {
        assertFalse(scheduleSendAllowed(PgpMode.SMIME_SIGN))
        assertEquals(R.string.compose_smime_no_schedule, scheduleRefusal("a@example.test", "", "", PgpMode.SMIME_SIGN))
        assertEquals(R.string.compose_pgp_no_schedule, scheduleRefusal("a@example.test", "", "", PgpMode.SIGN))
        assertTrue(stagesAttachmentLocally(MailProtocol.JMAP, PgpMode.SMIME_SIGN))
        assertTrue(draftSaveAllowed(PgpMode.SMIME_SIGN))
        assertFalse(draftSaveAllowed(PgpMode.SMIME_ENCRYPT))
        assertEquals(null, protectedSubject(PgpMode.SMIME_SIGN, "Lunch"))
    }

    @Test fun `the address judged is the one the message goes out from`() {
        assertEquals("alias@example.test", sentFromAddress(" alias@example.test ", "login@example.test"))
        assertEquals("login@example.test", sentFromAddress(null, "login@example.test"))
        assertEquals("login@example.test", sentFromAddress("  ", "login@example.test"))
    }

    @Test fun `every refusal has its own words`() {
        val words = SmimeRefusal.entries.map(::smimeRefusalWording)
        assertEquals(words.size, words.toSet().size)
    }
}
