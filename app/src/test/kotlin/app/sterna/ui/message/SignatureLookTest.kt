package app.sterna.ui.message

import app.sterna.R
import app.sterna.core.data.pgp.PgpSignatureState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader draws for a signature verdict ([signatureLook]) and what its sheet explains
 * ([signatureExplanation]). The words are checked on the English resource text itself, because the
 * promise is about what a reader sees: the first line and the three paragraphs speak plainly, and
 * only the technical line names a key, a certificate or an authority.
 */
class SignatureLookTest {

    private val reachableSmime = listOf(
        PgpSignatureState.VALID_CONFIRMED,
        PgpSignatureState.VALID_UNCONFIRMED,
        PgpSignatureState.SENDER_MISMATCH,
        PgpSignatureState.KEY_MISSING,
        PgpSignatureState.INVALID,
        PgpSignatureState.KEY_EXPIRED,
    )
    private val reachableOpenPgp = PgpSignatureState.entries.filter { it != PgpSignatureState.NONE }

    @Test fun `every verdict has its line, its icon and its tone`() {
        val expected = mapOf(
            PgpSignatureState.VALID_CONFIRMED to Triple("message_sig_verified", SignatureIcon.VERIFIED, SignatureTone.PRIMARY),
            PgpSignatureState.VALID_UNCONFIRMED to Triple("message_sig_unconfirmed", SignatureIcon.MAYBE, SignatureTone.TERTIARY),
            PgpSignatureState.SENDER_MISMATCH to Triple("message_sig_mismatch", SignatureIcon.MAYBE, SignatureTone.TERTIARY),
            PgpSignatureState.KEY_MISSING to Triple("message_sig_missing", SignatureIcon.MAYBE, SignatureTone.MUTED),
            PgpSignatureState.INVALID to Triple("message_sig_invalid", SignatureIcon.BAD, SignatureTone.ERROR),
            PgpSignatureState.KEY_EXPIRED to Triple("message_sig_expired", SignatureIcon.BAD, SignatureTone.ERROR),
            PgpSignatureState.KEY_REVOKED to Triple("message_sig_revoked", SignatureIcon.BAD, SignatureTone.ERROR),
            PgpSignatureState.INSECURE to Triple("message_sig_insecure", SignatureIcon.BAD, SignatureTone.ERROR),
        )
        val actual = expected.keys.associateWith { state ->
            signatureLook(state)?.let { Triple(nameOf(it.line), it.icon, it.tone) }
        }
        assertEquals(expected, actual)
        assertNull("no signature, no seal", signatureLook(PgpSignatureState.NONE))
    }

    @Test fun `only a confirmed sender wears the verified seal and the primary tone`() {
        val verified = PgpSignatureState.entries.filter {
            val look = signatureLook(it)
            look?.icon == SignatureIcon.VERIFIED || look?.tone == SignatureTone.PRIMARY
        }
        assertEquals(listOf(PgpSignatureState.VALID_CONFIRMED), verified)
    }

    @Test fun `the verdict lines speak plainly`() {
        val lines = english.filterKeys { it.startsWith("message_sig_") }
        assertTrue("no message_sig_ line in values/strings.xml", lines.isNotEmpty())
        val technical = lines.filterValues { TECHNICAL.containsMatchIn(it) }
        assertEquals("a first line names a key, a certificate or an authority", emptyMap<String, String>(), technical)
    }

    @Test fun `every reachable verdict is explained in full`() {
        val cases = reachableSmime.map { it to true } + reachableOpenPgp.map { it to false }
        cases.forEach { (state, smime) ->
            val ex = signatureExplanation(state, smime)
            assertNotNull("$state smime=$smime has no explanation", ex)
            listOf(ex!!.checked, ex.unchecked, ex.meaning, ex.technical).forEach { id ->
                assertFalse("$state smime=$smime: ${nameOf(id)} is empty", textOf(id).isBlank())
            }
        }
        assertNull(signatureExplanation(PgpSignatureState.NONE, smime = true))
        assertNull(signatureExplanation(PgpSignatureState.NONE, smime = false))
    }

    @Test fun `only the technical line names the mechanism, and each system names its own`() {
        val found = mutableListOf<String>()
        reachableSmime.forEach { state ->
            val ex = signatureExplanation(state, smime = true) ?: return@forEach
            paragraphs(ex).filter { TECHNICAL.containsMatchIn(textOf(it)) }.forEach { found += "S/MIME $state ${nameOf(it)}" }
            if (KEY.containsMatchIn(textOf(ex.technical))) found += "S/MIME $state names a key: ${textOf(ex.technical)}"
            if (!textOf(ex.technical).startsWith("S/MIME")) found += "S/MIME $state: ${textOf(ex.technical)}"
        }
        reachableOpenPgp.forEach { state ->
            val ex = signatureExplanation(state, smime = false) ?: return@forEach
            paragraphs(ex).filter { TECHNICAL.containsMatchIn(textOf(it)) }.forEach { found += "OpenPGP $state ${nameOf(it)}" }
            if (CERTIFICATE.containsMatchIn(textOf(ex.technical))) found += "OpenPGP $state names a certificate: ${textOf(ex.technical)}"
            if (!textOf(ex.technical).startsWith("OpenPGP")) found += "OpenPGP $state: ${textOf(ex.technical)}"
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun `a signature that is not confirmed is never called verified`() {
        val found = (reachableSmime.map { it to true } + reachableOpenPgp.map { it to false })
            .filter { (state, _) -> state != PgpSignatureState.VALID_CONFIRMED }
            .flatMap { (state, smime) ->
                val ex = signatureExplanation(state, smime) ?: return@flatMap emptyList()
                (paragraphs(ex) + ex.technical).map(::nameOf).filter { Regex("(?i)verif").containsMatchIn(english.getValue(it)) }
            }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun `an expired signature is not an altered message`() {
        listOf(true, false).forEach { smime ->
            val ex = signatureExplanation(PgpSignatureState.KEY_EXPIRED, smime)!!
            val said = (paragraphs(ex) + signatureLook(PgpSignatureState.KEY_EXPIRED)!!.line).map(::textOf)
            assertTrue("smime=$smime: $said", said.none { "don't trust" in it.lowercase() })
            assertTrue("smime=$smime: expired must say the message is unchanged", "hasn't been changed" in textOf(ex.checked))
        }
    }

    @Test fun `S-MIME never claims to have checked revocation`() {
        val found = reachableSmime.flatMap { state ->
            val ex = signatureExplanation(state, smime = true)!!
            (paragraphs(ex) + ex.technical).map(::textOf)
                .filter { Regex("(?i)revo|withdrawn").containsMatchIn(it) && !Regex("(?i)\\bnot\\b|n't").containsMatchIn(it) }
        }
        assertEquals(emptyList<String>(), found)
    }

    private fun paragraphs(ex: SignatureExplanation): List<Int> = listOf(ex.checked, ex.unchecked, ex.meaning)

    private fun textOf(id: Int): String = english[nameOf(id)] ?: error("${nameOf(id)} is not in values/strings.xml")

    private fun nameOf(id: Int): String = R.string::class.java.fields
        .firstOrNull { it.type == Int::class.java && it.getInt(null) == id }
        ?.name
        ?: error("no name in R.string for id $id")

    private companion object {
        val TECHNICAL = Regex("(?i)\\bkeys?\\b|certificat|authorit")
        val KEY = Regex("(?i)\\bkeys?\\b")
        val CERTIFICATE = Regex("(?i)certificat")

        val english: Map<String, String> by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(File(root, "app/src/main/res/values/strings.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
        }
    }
}
