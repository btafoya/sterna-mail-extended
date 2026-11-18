package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SendKeysReadyTest {

    private val recipients = listOf("a@x.com", "b@x.com")
    private val bMissing = mapOf("a@x.com" to true, "b@x.com" to false)

    @Test fun `an OpenPGP encrypting mode never holds Send back for a missing key`() {
        for (mode in listOf(PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED)) {
            assertTrue(
                "$mode greys Send for a recipient with no known key: the send never reaches the " +
                    "provider, so its key selector is never offered",
                sendKeysReady(mode, recipients, bMissing),
            )
        }
    }

    @Test fun `S-MIME encryption still holds Send back for a recipient without a certificate`() {
        assertFalse(
            "SMIME_ENCRYPT lets Send through with a recipient missing a certificate: there is no " +
                "certificate selector to fall back on, the send can only fail",
            sendKeysReady(PgpMode.SMIME_ENCRYPT, recipients, bMissing),
        )
        assertTrue(sendKeysReady(PgpMode.SMIME_ENCRYPT, recipients, mapOf("a@x.com" to true, "b@x.com" to true)))
        assertTrue(
            "an unchecked recipient (absent from the map) blocks S/MIME Send",
            sendKeysReady(PgpMode.SMIME_ENCRYPT, recipients, mapOf("a@x.com" to true)),
        )
    }

    @Test fun `modes that do not encrypt never look at keys`() {
        for (mode in listOf(PgpMode.OFF, PgpMode.SIGN, PgpMode.SMIME_SIGN)) {
            assertTrue("$mode", sendKeysReady(mode, recipients, bMissing))
        }
    }

    @Test fun `the Send gate is the rule above, fed the mode in force and every recipient`() {
        // Source lint, the complement of the cases above: ComposeScreen cannot run in a JVM test.
        val code = File(root, SCREEN).readLines().map { it.trim() }
        assertEquals(
            "the Send gate no longer goes through sendKeysReady with the mode, all recipients and their keys",
            listOf(
                "val keysReady = sendKeysReady(pgpMode, allRecipients, recipientKeys)",
                "val canSend = recipientTokens(to).isNotEmpty() && allRecipients.all(::isValidEmail) && keysReady",
            ),
            code.indexOfFirst { it.startsWith("val keysReady = ") }.let { i ->
                if (i < 0) emptyList() else code.subList(i, i + 2)
            },
        )
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
