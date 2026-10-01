package app.sterna.ui.message

import android.app.PendingIntent
import app.sterna.R
import app.sterna.core.data.mail.MessageBody
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader changes once the S/MIME check laid over a body it already shows answers
 * ([overlayCrypto]), and the words its signature sheet uses for an S/MIME verdict ([signatureExplanation]).
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
        val smimeStates = listOf(
            PgpSignatureState.VALID_CONFIRMED,
            PgpSignatureState.VALID_UNCONFIRMED,
            PgpSignatureState.SENDER_MISMATCH,
            PgpSignatureState.KEY_MISSING,
            PgpSignatureState.INVALID,
            PgpSignatureState.KEY_EXPIRED,
        )
        val found = smimeStates.flatMap { state ->
            val ex = signatureExplanation(state, smime = true)
            assertNotNull("$state", ex)
            listOf(ex!!.checked, ex.unchecked, ex.meaning, ex.technical).map(::englishOf)
                .filter { Regex("(?i)\\bkeys?\\b|OpenPGP").containsMatchIn(it) }
                .map { "$state: $it" }
        }
        assertEquals(emptyList<String>(), found)
        assertNull(signatureExplanation(PgpSignatureState.NONE, smime = true))
    }

    @Test fun `an OpenPGP verdict keeps its own lines`() {
        val states = PgpSignatureState.entries.filter { it != PgpSignatureState.NONE }
        val technical = states.map { state ->
            val ex = signatureExplanation(state, smime = false)
            assertNotNull("$state", ex)
            ex!!.technical
        }
        assertEquals("each OpenPGP verdict has its own technical line", states.size, technical.toSet().size)
        val smimeTechnical = PgpSignatureState.entries.mapNotNull { signatureExplanation(it, smime = true)?.technical }
        assertEquals(emptyList<Int>(), technical.filter { it in smimeTechnical })
        technical.map(::englishOf).forEach { assertTrue(it, it.startsWith("OpenPGP")) }
        assertNull(signatureExplanation(PgpSignatureState.NONE, smime = false))
    }

    private fun englishOf(id: Int): String {
        val name = R.string::class.java.fields.first { it.type == Int::class.java && it.getInt(null) == id }.name
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }
        return Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File(root, "app/src/main/res/values/strings.xml").readText())
            ?.groupValues?.get(1) ?: error("$name is not in values/strings.xml")
    }
}
