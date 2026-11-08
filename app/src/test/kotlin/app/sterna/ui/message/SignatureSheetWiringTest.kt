package app.sterna.ui.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source rule for what nothing in the JVM can lay out: the header seal and the status card read the
 * same [signatureLook], and both open the one sheet through [SignatureSheetOpen] (see `SignatureSheetHostLintTest`).
 * The decision itself is executed in `SignatureLookTest`; this only pins that the screen asks it.
 */
class SignatureSheetWiringTest {

    @Test fun `the header seal reads the look, says the verdict and opens the sheet`() {
        assertEquals(
            emptyList<String>(),
            missing(
                block("private fun MessageHeader("),
                "val signatureSheet = LocalSignatureSheet.current",
                "val seal = decrypted?.signature?.let(::signatureLook)",
                ".clickable(role = Role.Button) { signatureSheet.open(msg.id, msg.header.accountId) },",
                "contentDescription = stringResource(seal.line, decrypted.signatureUserId ?: \"?\"),",
                "tint = seal.tone.color(),",
                "onExplainSignature = { signatureSheet.open(msg.id, msg.header.accountId) },",
                "SignatureSheet(explained, onDismiss = signatureSheet::close)",
            ),
        )
        assertEquals(
            emptyList<String>(),
            missing(
                block("private fun HeaderCard("),
                "is HeaderCardRow.Crypto -> PgpStatusCard(row.state, onCryptoAction, onExplainSignature)",
            ),
        )
    }

    @Test fun `the card reads the same look and opens the same sheet, only on a verdict`() {
        assertEquals(
            emptyList<String>(),
            missing(
                block("private fun PgpStatusCard("),
                "val verdict = (crypto as? CryptoUiState.Decrypted)?.result?.signature?.let(::signatureLook)",
                "is CryptoUiState.Decrypted -> HeaderCardLine(onClick = if (verdict != null) onExplainSignature else null) {",
            ),
        )
    }

    @Test fun `there is one sheet, opened from one place`() {
        val calls = SOURCE.readLines().map { it.trim() }
            .filter { "SignatureSheet(" in it && !it.startsWith("private fun") && !it.startsWith("*") && !it.startsWith("//") }
        assertEquals(listOf("SignatureSheet(explained, onDismiss = signatureSheet::close)"), calls)
        assertEquals(
            emptyList<String>(),
            missing(
                block("private fun SignatureSheet("),
                "ModalBottomSheet(onDismissRequest = onDismiss) {",
                "val explanation = signatureExplanation(result.signature, result.smime)",
            ),
        )
    }

    @Test fun `the seal no longer says only that there is a signature`() {
        val lines = SOURCE.readLines().map { it.trim() }
        assertEquals(emptyList<String>(), lines.filter { "a11y_pgp_signature" in it })
        assertEquals(
            "the verified seal is drawn for one verdict only, through the look",
            listOf("SignatureIcon.VERIFIED -> Icons.Filled.VerifiedUser"),
            lines.filter { "Icons.Filled.VerifiedUser" in it },
        )
    }

    private fun missing(body: String, vararg lines: String): List<String> {
        val have = body.lines().map { it.trim() }.toSet()
        return lines.filterNot { it in have }
    }

    private fun block(signature: String): String {
        val text = SOURCE.readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature`" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
