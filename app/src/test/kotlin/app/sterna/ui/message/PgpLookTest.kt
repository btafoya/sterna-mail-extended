package app.sterna.ui.message

import android.app.PendingIntent
import app.sterna.R
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OpenPGP strip and the header lock follow the kind of message, not how far the provider got:
 * a clear-signed message waiting on the provider is a signed message to verify, never an encrypted one
 * to unlock, and it never wears the lock.
 */
class PgpLookTest {

    /** The unit-test `android.jar` throws from every constructor and factory of [PendingIntent]; the
     *  look never reads it, so an instance allocated without a constructor is enough to carry it. */
    private val pendingIntent: PendingIntent = run {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PendingIntent::class.java) as PendingIntent
    }

    @Test fun `a signed message waiting on the provider asks to verify, without a lock`() {
        val look = pgpLook(CryptoUiState.NeedsInteraction(pendingIntent, CryptoKind.PGP_SIGNED))
        assertEquals("title", R.string.message_pgp_signed_title, look.title)
        assertEquals("button", R.string.message_pgp_verify, look.action)
        assertFalse("a clear-signed message is not encrypted: no header lock", look.headerLock)
    }

    @Test fun `a locked signed message asks to verify, without a lock`() {
        listOf(false, true).forEach { decrypting ->
            val look = pgpLook(CryptoUiState.Locked(CryptoKind.PGP_SIGNED, decrypting))
            assertEquals("title, decrypting=$decrypting", R.string.message_pgp_signed_title, look.title)
            assertEquals("button, decrypting=$decrypting", R.string.message_pgp_verify, look.action)
            assertFalse("header lock, decrypting=$decrypting", look.headerLock)
        }
    }

    @Test fun `a signed message whose verification failed wears no lock and keeps its own strip`() {
        listOf("provider said no", null).forEach { message ->
            val look = pgpLook(CryptoUiState.Failed(message, CryptoKind.PGP_SIGNED))
            assertFalse("header lock, message=$message", look.headerLock)
            assertNull("the failure strip writes the error, not a title", look.title)
            assertNull("the failure strip offers Retry, not a prompt", look.action)
        }
    }

    @Test fun `an encrypted or inline message still asks to unlock and wears the lock`() {
        listOf(CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_INLINE).forEach { kind ->
            listOf(
                CryptoUiState.Locked(kind),
                CryptoUiState.NeedsInteraction(pendingIntent, kind),
            ).forEach { state ->
                // Named by class: the data class toString reaches the stubbed `PendingIntent.toString`.
                val name = "${state::class.simpleName}($kind)"
                val look = pgpLook(state)
                assertEquals("title for $name", R.string.message_pgp_encrypted_title, look.title)
                assertEquals("button for $name", R.string.message_pgp_unlock, look.action)
                assertTrue("header lock for $name", look.headerLock)
            }
            assertTrue("header lock for Failed($kind)", pgpLook(CryptoUiState.Failed("x", kind)).headerLock)
        }
    }

    @Test fun `once opened, the lock says whether it was encrypted`() {
        listOf(true, false).forEach { wasEncrypted ->
            val look = pgpLook(
                CryptoUiState.Decrypted(
                    MessageCrypto.Decrypted(PgpSignatureState.VALID_CONFIRMED, null, 0L, wasEncrypted),
                ),
            )
            assertEquals("header lock, wasEncrypted=$wasEncrypted", wasEncrypted, look.headerLock)
            assertNull(look.title)
        }
        assertFalse(pgpLook(CryptoUiState.None).headerLock)
    }
}
