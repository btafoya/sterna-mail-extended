package app.sterna.ui.message

import android.app.PendingIntent
import app.sterna.R
import app.sterna.core.data.mail.MessageBody
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the reader changes once the S/MIME check laid over a body it already shows answers
 * ([overlayCrypto]), and the words it puts under an S/MIME verdict ([signatureLine]).
 * `SmimeReadPathTest` in `core/data` produces the results fed here from real messages; this runs the
 * decision the ViewModel takes on them, and `SmimeOverlayCallSiteTest` pins that it takes it.
 */
class SignatureOverlayTest {

    private val pendingIntent: PendingIntent = run {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PendingIntent::class.java) as PendingIntent
    }

    private val verdict = MessageCrypto.Decrypted(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", null, false, smime = true)

    @Test fun `a check that came back puts its verdict in the strip`() {
        val result = PgpResult.Success(MessageBody(Email(id = "1"), emptyMap(), verdict))
        assertEquals(CryptoUiState.Decrypted(verdict), overlayCrypto(result))
    }

    @Test fun `a source out of reach, or any failure to check, changes nothing and shows no card`() {
        listOf(
            PgpResult.Error("Unable to connect to imap.example.test"),
            PgpResult.Error("Cannot fetch message source"),
            PgpResult.NotAvailable,
        ).forEach { failure ->
            assertNull("$failure", overlayCrypto(failure))
        }
        // Named by class: the stubbed `PendingIntent.toString` throws.
        assertNull("UserInteractionRequired", overlayCrypto(PgpResult.UserInteractionRequired(pendingIntent)))
    }

    @Test fun `a message with nothing to check, an encrypted one among them, keeps the body it shows`() {
        assertNull(overlayCrypto(PgpResult.Success(MessageBody(Email(id = "1"), emptyMap(), crypto = null))))
    }

    @Test fun `an S-MIME verdict is never worded with an OpenPGP key`() {
        val keyLines = setOf(
            R.string.message_pgp_sig_valid,
            R.string.message_pgp_sig_unconfirmed,
            R.string.message_pgp_sig_missing_key,
            R.string.message_pgp_sig_expired,
            R.string.message_pgp_sig_mismatch,
        )
        mapOf(
            PgpSignatureState.VALID_CONFIRMED to R.string.message_smime_sig_valid,
            PgpSignatureState.VALID_UNCONFIRMED to R.string.message_smime_sig_unconfirmed,
            PgpSignatureState.KEY_MISSING to R.string.message_smime_sig_missing_certificate,
            PgpSignatureState.KEY_EXPIRED to R.string.message_smime_sig_expired,
            PgpSignatureState.SENDER_MISMATCH to R.string.message_smime_sig_mismatch,
        ).forEach { (state, line) ->
            assertEquals("$state", line, signatureLine(state, smime = true))
            assertFalse("$state names a key", (signatureLine(state, smime = true) ?: 0) in keyLines)
        }
        assertEquals(R.string.message_pgp_sig_invalid, signatureLine(PgpSignatureState.INVALID, smime = true))
        assertNull(signatureLine(PgpSignatureState.NONE, smime = true))
    }

    @Test fun `an OpenPGP verdict keeps its own lines`() {
        mapOf(
            PgpSignatureState.VALID_CONFIRMED to R.string.message_pgp_sig_valid,
            PgpSignatureState.VALID_UNCONFIRMED to R.string.message_pgp_sig_unconfirmed,
            PgpSignatureState.KEY_MISSING to R.string.message_pgp_sig_missing_key,
            PgpSignatureState.INVALID to R.string.message_pgp_sig_invalid,
            PgpSignatureState.KEY_REVOKED to R.string.message_pgp_sig_revoked,
            PgpSignatureState.KEY_EXPIRED to R.string.message_pgp_sig_expired,
            PgpSignatureState.INSECURE to R.string.message_pgp_sig_insecure,
            PgpSignatureState.SENDER_MISMATCH to R.string.message_pgp_sig_mismatch,
        ).forEach { (state, line) ->
            assertEquals("$state", line, signatureLine(state, smime = false))
        }
        assertNull(signatureLine(PgpSignatureState.NONE, smime = false))
    }
}
