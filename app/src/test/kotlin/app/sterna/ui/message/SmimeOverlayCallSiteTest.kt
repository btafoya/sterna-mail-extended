package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: `MessageViewModel` is an `AndroidViewModel` no JVM test builds.
 * [SignatureOverlayTest] runs the decision; this pins that the ViewModel asks for it, with these
 * arguments, once per opened message, and that nothing in the check can raise a card.
 */
class SmimeOverlayCallSiteTest {

    @Test fun `an unlocked message is checked only when its parts say S-MIME, once, when the page settles`() {
        assertEquals(
            "load() must read the signal off the opened message and go through the one-shot gate. Lines found:",
            listOf("overlaidKind = smimeSignalOf(anchor)", "maybeAutoDecrypt()"),
            blockAfter(body("fun load("), "null -> {"),
        )
        assertEquals(
            "maybeAutoDecrypt must spend the one attempt before the check. Lines found:",
            listOf("overlaidKind?.let { kind ->", "autoDecryptTried = true", "verifyOverBody(kind)", "return"),
            body("private fun maybeAutoDecrypt(").lines().map { it.trim() }
                .dropWhile { it != "overlaidKind?.let { kind ->" }.take(4),
        )
    }

    @Test fun `the check asks the repository for this kind and changes the page only on a verdict`() {
        val check = body("private fun verifyOverBody(").lines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
        assertTrue("repo.decryptMessage(credentials, emailId, lockedKind = kind)" in check)
        assertTrue("val crypto = overlayCrypto(result) ?: return@launch" in check)
        assertTrue("val forAccount = accountId" in check)
        assertTrue(
            "the body lands only on the page still showing this message, of this account",
            "if (!publishBody(emailId, forAccount, message) { loadedId == emailId && accountId == forAccount }) return@launch" in check,
        )
        assertTrue("_state.value = MessageState.Loaded(display)" in check)
        assertEquals(
            "the check may write the crypto state on this line only, after the body. Lines found:",
            listOf("_crypto.value = crypto"),
            check.filter { "_crypto" in it },
        )
        listOf("CryptoUiState.Failed", "CryptoUiState.NeedsInteraction", "CryptoUiState.Locked").forEach { card ->
            assertTrue("$card in verifyOverBody", check.none { card in it })
        }
        assertTrue("the seal follows the body", check.indexOf("_crypto.value = crypto") > check.indexOfFirst { "publishBody(" in it })
    }

    @Test fun `the S-MIME kind of the previous message is forgotten on load`() {
        val load = body("fun load(")
        assertTrue(load.substring(0, load.indexOf("viewModelScope.launch")).lines().any { it.trim() == "overlaidKind = null" })
    }

    private fun blockAfter(text: String, opener: String): List<String> = text.lines().map { it.trim() }
        .dropWhile { it != opener }.drop(1).takeWhile { it != "}" }

    /** The text of the member opened by [signature], up to the next member at class level. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("MessageViewModel has no $signature any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        val source: String by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
                .firstOrNull { it.isFile }
                ?.readText()
                ?: error("cannot locate MessageViewModel.kt from ${File("").absolutePath}")
        }
    }
}
