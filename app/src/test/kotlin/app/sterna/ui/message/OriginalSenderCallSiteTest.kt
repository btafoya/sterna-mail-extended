package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [OriginalSenderToShowTest] executes the decision; this pins that the decision is still WIRED to
 * the screen, and what the screen calls the line (#160).
 *
 * The gap it closes, found by an independent review: delete `OriginalSenderGroup(originalSender)`
 * and the line vanishes from every phone while the whole suite stays green. So does passing
 * `emptyList()` instead of the message's `from` — the "same address as the sender" refusal then
 * never fires and the panel names the alias twice.
 *
 * Read out of the shipped source because a `@Composable` cannot be built on a JVM bench: this
 * module has neither Robolectric nor instrumentation (same instrument, and the same last-resort
 * reasoning, as [ReadReceiptCoverSubjectCallSiteTest]).
 *
 * ⛔ Whole trimmed lines compared by list equality — never `contains`, which is blind to every
 * mutation that LENGTHENS the line. Comment lines are dropped, so prose can neither satisfy a rule
 * nor break one.
 */
class OriginalSenderCallSiteTest {

    /**
     * Every code line of the reader that names `originalSender`, whole and in order: the decision
     * called with BOTH its arguments, the panel's parameter, the group that paints it, and the
     * declaration itself. A fifth line, or any of these four rewritten, fails here.
     */
    @Test fun `the reader decides the origin line once, and hands it to the panel`() {
        assertEquals(
            "the panel's origin line must be decided by originalSenderToShow(full?.originalSender, " +
                "msg.header.from) — `emptyList()` for the second argument silently disables the " +
                "refusal that keeps the alias from being named twice. Lines found:",
            listOf(
                "originalSender = originalSenderToShow(full?.originalSender, msg.header.from),",
                "originalSender: EmailAddress?,",
                "OriginalSenderGroup(originalSender)",
                "internal fun originalSenderToShow(headerValue: String?, from: List<EmailAddress>): EmailAddress? {",
            ),
            codeLines(screen().readText()).filter { "originalSender" in it },
        )
    }

    /**
     * ⛔ And the group is still CALLED, from inside the panel. Every pure-function test in this
     * module survives its deletion; nothing else in the repository would notice.
     */
    @Test fun `the participants panel still paints the origin group`() {
        assertEquals(
            "ParticipantsSheet must call OriginalSenderGroup, and the group must still exist",
            listOf(
                "OriginalSenderGroup(originalSender)",
                "private fun OriginalSenderGroup(address: EmailAddress?) {",
            ),
            codeLines(screen().readText()).filter { "OriginalSenderGroup" in it },
        )
    }

    /**
     * ⭐ The LABEL, whole. Nothing verifies that a message was ever relayed: the header is a plain
     * text field any sender can put on any message, and the one refusal in
     * [originalSenderToShow] — "same address as the From" — is precisely the one a forgery does not
     * trip (`From: security@paypa1.example` with `X-Original-From: PayPal <service@paypal.com>`).
     *
     * So the label says so. Repository rule 3: between lowering the wording and raising the code,
     * the wording comes down — it is honest, and it costs one translation string. A bare "Original
     * sender" is an assertion this code cannot keep, on the one screen written to help spot a
     * forgery.
     */
    @Test fun `the label does not claim the origin was verified`() {
        assertEquals(
            "the English base string must not present a sender-controlled header as an established " +
                "fact — nothing here checks that the message was relayed at all",
            listOf("""<string name="participants_original_sender">Original sender (unverified)</string>"""),
            baseStrings().readText().lines().map { it.trim() }
                .filter { "participants_original_sender" in it },
        )
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    private fun baseStrings(): File = locate("app/src/main/res/values/strings.xml")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
