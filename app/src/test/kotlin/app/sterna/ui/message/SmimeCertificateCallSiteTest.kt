package app.sterna.ui.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, the last resort: `MessageViewModel` and `MessageScreen` are not built by a JVM test, and the
 * system's certificate chooser runs on a device only. [SmimeEnvelopeStripTest] runs the decisions; this pins that
 * they are the ones called, with these arguments.
 */
class SmimeCertificateCallSiteTest {

    @Test fun `the chooser's answer goes through the decision, remembered for the account and decrypted, or cancelled`() {
        val chosen = lines(viewModel, "fun onCertificateChosen(")
        assertTrue("applyCertificateChoice(" in chosen)
        assertTrue("alias," in chosen)
        assertTrue(
            "remember = { chosen -> withContext(Dispatchers.IO) { credentials()?.id?.let { store.setSmimeCertAlias(it, chosen) } } }," in chosen,
        )
        assertTrue("decrypt = { decrypt() }," in chosen)
        assertTrue("stayLocked = ::cancelDecrypt," in chosen)
        assertEquals("nothing else in it writes or decrypts", 1, chosen.count { "setSmimeCertAlias" in it })
    }

    @Test fun `the strip's button opens the chooser on this account's certificate and hands its answer back as it is`() {
        val screen = text(screenFile)
        assertTrue("when (cryptoStripActionOf(c)) {" in screen.lines().map { it.trim() })
        assertTrue(
            "chooseSmimeCertificate(activity, viewModel.chosenCertificate(), viewModel::onCertificateChosen)" in
                screen.lines().map { it.trim() },
        )
        val chooser = text(keyChainFile).lines().map { it.trim() }
        assertTrue("{ alias -> main.execute { onAnswer(alias) } }," in chooser)
    }

    @Test fun `an envelope that did not open puts its strip up without touching the body, and keeps its kind for a cancel`() {
        val check = lines(viewModel, "private fun verifyOverBody(")
        val unopened = check.dropWhile { it != "} else {" }
        assertTrue("if (loadedId != emailId || accountId != forAccount) return@launch" in unopened)
        assertTrue("lockedKind = CryptoKind.SMIME_ENCRYPTED" in unopened)
        assertTrue(unopened.none { "publishBody" in it || "_state.value" in it })
        val decrypt = lines(viewModel, "fun decrypt(")
        assertTrue("_crypto.value = decryptingState(_crypto.value)" in decrypt)
        assertTrue("_crypto.value = cryptoStateAfterDecrypt(result, lockedKind)" in decrypt)
    }

    @Test fun `the app reads keys from Android's keystore`() {
        assertTrue(
            "smimeKeys = KeyChainSmimeKeys(context.applicationContext)," in
                text("app/src/main/kotlin/app/sterna/SternaApplication.kt").lines().map { it.trim() },
        )
    }

    private fun lines(source: String, signature: String): List<String> {
        val start = source.indexOf(signature)
        assertTrue("no $signature any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end).lines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
    }

    private companion object {
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")
        const val screenFile = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val keyChainFile = "app/src/main/kotlin/app/sterna/pgp/KeyChainSmimeKeys.kt"

        fun text(path: String): String = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot locate $path from ${File("").absolutePath}")

        val viewModel: String by lazy { text("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
    }
}
