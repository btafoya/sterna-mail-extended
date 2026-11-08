package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, beside [SignatureSheetOpenTest] which executes the holder. The reader is composed by
 * two hosts, the `message/…` route and the reading pane, and a width change swaps one for the
 * other; no JVM test can compose either (`InboxViewModel` needs the repository). What this pins is
 * that the sheet's open state lives in the one holder both hosts hand over, never in the header's
 * own composition, which dies with the host. Whole trimmed lines, never `contains`: a fragment rule
 * is blind to anything appended to a line.
 */
class SignatureSheetHostLintTest {

    @Test fun `the header opens, shows and closes the sheet through the holder only`() {
        val lines = codeLines(read(SCREEN))
        assertEquals(
            "MessageHeader must not keep the signature sheet's open state in its own composition " +
                "(`remember`/`rememberSaveable`): the route-to-pane swap on a rotation drops it. " +
                "Lines found:",
            emptyList<String>(),
            lines.filter { "showSignature" in it },
        )
        assertEquals(
            "the seal and the card must both open the sheet for THIS message through the holder. " +
                "Lines found:",
            listOf(
                ".clickable(role = Role.Button) { signatureSheet.open(msg.id, msg.header.accountId) },",
                "onExplainSignature = { signatureSheet.open(msg.id, msg.header.accountId) },",
            ),
            lines.filter { "signatureSheet.open(" in it },
        )
        assertEquals(
            "the sheet must be shown by the holder's decision for this message, and dismissed " +
                "through the holder. Lines found:",
            listOf(
                "if (SignatureSheetOpen.shows(signatureOpenFor, msg.id, msg.header.accountId) && explained != null && signatureLook(explained.signature) != null) {",
                "SignatureSheet(explained, onDismiss = signatureSheet::close)",
            ),
            lines.filter { "SignatureSheetOpen.shows(" in it || "SignatureSheet(explained" in it },
        )
        assertEquals(
            "MessageScreen must take the holder as a parameter WITHOUT a default: a default would " +
                "let a host forget to hand the shared one over. Lines found:",
            listOf("signatureSheet: SignatureSheetOpen,"),
            lines.filter { it.startsWith("signatureSheet: ") },
        )
    }

    @Test fun `both hosts hand over the inbox ViewModel's holder`() {
        for (host in listOf(STERNA_APP, READING_PANE)) {
            assertEquals(
                "$host must hand MessageScreen the holder of the shared inbox ViewModel, the one " +
                    "owner that outlives the swap between the route and the pane. Lines found:",
                listOf("signatureSheet = inboxViewModel.signatureSheet,"),
                codeLines(read(host)).filter { "signatureSheet" in it },
            )
        }
        assertEquals(
            "InboxViewModel must build the holder on its SavedStateHandle, so the open sheet also " +
                "comes back after a process death. Lines found:",
            listOf("val signatureSheet = SignatureSheetOpen(savedState[SIGNATURE_SHEET]) { savedState[SIGNATURE_SHEET] = it }"),
            codeLines(read(INBOX_VM)).filter { "SignatureSheetOpen(" in it },
        )
    }

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun read(relative: String): String {
        val fromModule = relative.removePrefix("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val READING_PANE = "app/src/main/kotlin/app/sterna/ui/inbox/ReadingPane.kt"
        const val INBOX_VM = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
    }
}
