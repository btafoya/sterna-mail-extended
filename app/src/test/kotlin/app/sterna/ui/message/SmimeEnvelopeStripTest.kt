package app.sterna.ui.message

import android.app.PendingIntent
import app.sterna.R
import app.sterna.core.data.mail.MessageBody
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader shows and does for an S/MIME envelope: the strip each answer of the repository gives, what its
 * button does, and what the certificate chooser's answer does. `SmimeCertificateCallSiteTest` pins that the
 * ViewModel and the screen call these with the right arguments.
 */
class SmimeEnvelopeStripTest {

    private val pendingIntent: PendingIntent = run {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PendingIntent::class.java) as PendingIntent
    }

    private val answers = listOf(
        PgpResult.CertificateToChoose,
        PgpResult.NotEncryptedForCertificate,
        PgpResult.DecryptFailed("KeyChainException"),
    )

    @Test fun `each envelope that did not open gets its own strip, laid over the body or after a button`() {
        val expected = listOf(
            CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED),
            CryptoUiState.SmimeNotForCertificate,
            CryptoUiState.SmimeFailed,
        )
        assertEquals(expected, answers.map { overlayCrypto(it) })
        assertEquals(expected, answers.map { cryptoStateAfterDecrypt(it, CryptoKind.SMIME_ENCRYPTED) })
    }

    @Test fun `an envelope whose source cannot be fetched keeps the body it shows, with no card`() {
        assertNull(overlayCrypto(PgpResult.Error("Unable to connect to imap.example.test")))
    }

    @Test fun `an S-MIME message never reads as a missing OpenPGP app`() {
        val results = answers + listOf(PgpResult.NotAvailable, PgpResult.Error("Cannot fetch message source"))
        CryptoKind.entries.filter { it.name.startsWith("SMIME") }.forEach { kind ->
            results.forEach { result ->
                val state = cryptoStateAfterDecrypt(result, kind)
                assertTrue("$result for $kind gave $state", !(state is CryptoUiState.Failed && state.message == null))
            }
        }
        assertEquals(
            "OpenPGP keeps its own answer",
            CryptoUiState.Failed(null, CryptoKind.PGP_ENCRYPTED),
            cryptoStateAfterDecrypt(PgpResult.NotAvailable, CryptoKind.PGP_ENCRYPTED),
        )
    }

    @Test fun `the strip words each state, and every one of them wears the lock`() {
        val waiting = pgpLook(CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED))
        assertEquals(R.string.message_pgp_encrypted_title, waiting.title)
        assertEquals(R.string.message_pgp_unlock, waiting.action)
        val other = pgpLook(CryptoUiState.SmimeNotForCertificate)
        assertEquals(R.string.message_smime_not_for_certificate, other.title)
        assertEquals(R.string.message_smime_choose_other, other.action)
        val failed = pgpLook(CryptoUiState.SmimeFailed)
        assertEquals(R.string.message_smime_not_decrypted, failed.title)
        assertEquals(R.string.message_retry, failed.action)
        listOf(waiting, other, failed).forEach { assertTrue("$it", it.headerLock) }
    }

    @Test fun `the button opens the chooser while a certificate is wanted, and retries otherwise`() {
        assertEquals(CryptoStripAction.CHOOSE_CERTIFICATE, cryptoStripActionOf(CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED)))
        assertEquals(CryptoStripAction.CHOOSE_CERTIFICATE, cryptoStripActionOf(CryptoUiState.SmimeNotForCertificate))
        assertEquals(CryptoStripAction.DECRYPT, cryptoStripActionOf(CryptoUiState.SmimeFailed))
        assertEquals("OpenPGP is untouched", CryptoStripAction.DECRYPT, cryptoStripActionOf(CryptoUiState.Locked(CryptoKind.PGP_ENCRYPTED)))
        assertEquals(CryptoStripAction.DECRYPT, cryptoStripActionOf(CryptoUiState.Failed("x", CryptoKind.PGP_ENCRYPTED)))
        assertEquals(
            CryptoStripAction.RUN_PROVIDER_INTENT,
            cryptoStripActionOf(CryptoUiState.NeedsInteraction(pendingIntent, CryptoKind.PGP_ENCRYPTED)),
        )
    }

    @Test fun `a chosen certificate is remembered, then the message decrypted with it`() {
        val done = mutableListOf<String>()

        runBlocking {
            applyCertificateChoice(
                "keychain-alias",
                remember = { done += "remember $it" },
                decrypt = { done += "decrypt" },
                stayLocked = { done += "stayLocked" },
            )
        }

        assertEquals(listOf("remember keychain-alias", "decrypt"), done)
    }

    @Test fun `a cancelled chooser leaves the strip locked, and remembers and starts nothing`() {
        listOf(null, "").forEach { alias ->
            val done = mutableListOf<String>()

            runBlocking {
                applyCertificateChoice(
                    alias,
                    remember = { done += "remember $it" },
                    decrypt = { done += "decrypt" },
                    stayLocked = { done += "stayLocked" },
                )
            }

            assertEquals("alias=$alias", listOf("stayLocked"), done)
        }
    }

    @Test fun `a decryption asked for from any S-MIME strip shows progress in place of its button`() {
        listOf(CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED), CryptoUiState.SmimeNotForCertificate, CryptoUiState.SmimeFailed)
            .forEach { assertEquals("$it", CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED, decrypting = true), decryptingState(it)) }
        val decrypted = CryptoUiState.Decrypted(MessageCrypto.Decrypted(PgpSignatureState.NONE, null, null, true, smime = true))
        assertEquals(decrypted, decryptingState(decrypted))
    }

    @Test fun `an opened envelope shows as decrypted`() {
        val crypto = MessageCrypto.Decrypted(PgpSignatureState.VALID_UNCONFIRMED, "alice@example.test", null, true, smime = true)
        val result = PgpResult.Success(MessageBody(Email(id = "1"), emptyMap(), crypto))
        assertEquals(CryptoUiState.Decrypted(crypto), overlayCrypto(result))
        assertEquals(CryptoUiState.Decrypted(crypto), cryptoStateAfterDecrypt(result, CryptoKind.SMIME_ENCRYPTED))
    }
}
