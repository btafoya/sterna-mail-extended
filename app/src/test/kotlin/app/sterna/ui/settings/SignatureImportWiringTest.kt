package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `SettingsScreen.kt` as text. The decision is run by
 * `StoredIdentitySignatureTest`, which executes `signatureText`; this pins that both "Import HTML"
 * buttons (account override and identity) hand the imported file to it rather than to `htmlToText`,
 * which flattens an image-only signature to nothing (#260).
 *
 * Holes (CONTRIBUTING.md, Tests):
 *  1. `html` is a name: nothing ties it to the file the picker read.
 *  2. Does not apply: whole lines are compared, so no expression around the call can slip in.
 *  3. Nothing says each line sits inside its `pendingImport` lambda, nor which button owns which.
 *  4. Nothing here holds the identity the screen actually saves.
 *
 * Rules compare whole trimmed lines; `contains` only screens for what must be absent.
 */
class SignatureImportWiringTest {

    @Test fun `both html imports derive the text through signatureText`() {
        assertEquals(
            "SettingsScreen.kt must store an imported HTML signature on exactly these two lines, in " +
                "this order (account override, then identity). With `htmlToText(html)` an image-only " +
                "signature gets an empty text, the composer inserts no block, and the HTML is never sent.",
            listOf(
                "it.copy(signature = signatureText(html), signatureHtml = html)",
                "update { it.copy(signature = signatureText(html), signatureHtml = html) }",
            ),
            codeLines(SETTINGS_SCREEN).filter { "signatureHtml = html" in it },
        )
    }

    @Test fun `no flattening of an import goes around signatureText`() {
        assertEquals(
            "SettingsScreen.kt must not call `htmlToText(` at all: every HTML it flattens is a " +
                "signature, and a signature's text comes from `signatureText`.",
            emptyList<String>(),
            codeLines(SETTINGS_SCREEN).filter { "htmlToText(" in it },
        )
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val SETTINGS_SCREEN: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $PATH from ${File("").absolutePath}")
        }
    }
}
