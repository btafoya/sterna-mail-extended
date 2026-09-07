package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ReadReceiptOfferTest] executes the capture; this pins WHAT IT IS CALLED WITH, and WHERE the
 * subject it is called with comes from — the only two facts that keep a decrypted subject off the
 * wire (issue #128).
 *
 * `readReceiptRequest(email, deliveredTo, outgoingSubject)` says nothing about its third argument.
 * `readReceiptRequest(current, _deliveredTo.value, current.subject)` compiles, keeps every other
 * test in this module green, and sends the PROTECTED subject of an encrypted message, in clear, to
 * an address the sender chose — plus a copy in Sent. So does a single line added inside `decrypt()`
 * putting the decrypted subject back into `coverSubject`: the field would still exist, still be
 * reset, still be passed, and still be wrong.
 *
 * Read out of the shipped source because [MessageViewModel] cannot be built on a JVM bench: it is
 * an `AndroidViewModel` reaching into the application container, and this module has neither
 * Robolectric nor instrumentation (same reason, and the same instrument, as
 * [ReadReceiptTriggerWiringTest] and [ReaderStateResetOnLoadTest]).
 *
 * Both assertions compare WHOLE trimmed lines by list equality. Never `contains` for something
 * expected to be present: a substring check is blind to every mutation that LENGTHENS the line.
 * Comment lines are dropped, so no amount of prose can satisfy or break a rule.
 */
class ReadReceiptCoverSubjectCallSiteTest {

    /** PIN 1 — the call site: the offer is built with the cover subject, and with nothing else. */
    @Test fun `the offer hands the read receipt the cover subject`() {
        assertEquals(
            "maybeOfferReadReceipt() must build the request with the captured cover subject, on " +
                "exactly one line and with exactly these arguments. `current.subject` there is " +
                "the subject on SCREEN, which is the protected one after a decrypt. Found:",
            listOf("request = readReceiptRequest(current, _deliveredTo.value, coverSubject),"),
            codeLines(offerFunction()).filter { "readReceiptRequest(" in it },
        )
    }

    /**
     * PIN 2 — every code line of the file that names `coverSubject`, whole and in order.
     *
     * This is the rule pin 1 cannot state: the capture must happen BEFORE anything is decrypted,
     * from the cached row and from the fetched original, and nowhere else. A fourth assignment —
     * in `decrypt()`, from `_state`, from `display` — is the leak wearing the fix's clothes, and
     * it is invisible to every other test in the repo.
     */
    @Test fun `the cover subject is captured before any decrypt, and never rewritten`() {
        assertEquals(
            "`coverSubject` is what stops a decrypted subject from leaving the device. It may be " +
                "declared once, cleared for the next message in load()'s prologue, written from " +
                "the two pre-decrypt reads (never erased by a null), and read once by the offer " +
                "— and it may appear nowhere else. Lines found:",
            listOf(
                "private var coverSubject: String? = null",
                "coverSubject = null",
                "coverSubject = cached.subject ?: coverSubject",
                "coverSubject = coverSubjectAfterOpen(coverSubject, anchor, opened.crypto)",
                "request = readReceiptRequest(current, _deliveredTo.value, coverSubject),",
            ),
            codeLines(viewModel().readText()).filter { "coverSubject" in it },
        )
    }

    /**
     * The body of `maybeOfferReadReceipt` up to the function that follows it. Both markers are
     * asserted, so a rename fails here loudly instead of handing back an empty slice on which the
     * assertion above would fail for the wrong reason.
     */
    private fun offerFunction(): String {
        val source = viewModel().readText()
        val start = source.indexOf("private fun maybeOfferReadReceipt() {")
        check(start >= 0) { "MessageViewModel no longer declares maybeOfferReadReceipt()" }
        val end = source.indexOf("fun openAttachment(", start)
        check(end > start) { "maybeOfferReadReceipt() is no longer followed by openAttachment()" }
        return source.substring(start, end)
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    /** [relative] resolved from the test's working directory, walking up — as `DecryptedSubjectCallSiteTest` does. */
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
