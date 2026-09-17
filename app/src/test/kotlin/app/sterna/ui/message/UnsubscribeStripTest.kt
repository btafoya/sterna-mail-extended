package app.sterna.ui.message

import app.sterna.core.data.mail.MailtoUnsubscribe
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shape the unsubscribe strip takes, and the one property that matters beyond looks: the
 * three ordinary shapes are the same height.
 */
class UnsubscribeStripTest {

    private val oneClick = UnsubscribeOptions(oneClickUrl = "https://l.example.com/u/abc")
    private val mail = UnsubscribeOptions(mailto = MailtoUnsubscribe("leave@l.example.com"))

    private val everyState = listOf(
        UnsubscribeState.Idle,
        UnsubscribeState.Sending,
        UnsubscribeState.Sent,
        UnsubscribeState.Queued,
    ) + UnsubscribeFailure.entries.map { UnsubscribeState.Failed(it) }

    @Test fun `an untouched strip is one button`() {
        assertEquals(UnsubscribeStripBody.ACTION, unsubscribeStripBody(UnsubscribeState.Idle))
        assertTrue(unsubscribeStripBody(UnsubscribeState.Idle).acts)
    }

    @Test fun `a request in flight keeps the button and takes the tap away`() {
        // The spinner goes where the icon was, inside the button: a spinner that replaces the
        // whole button is a strip of another height, and another height is a reload of the body.
        val body = unsubscribeStripBody(UnsubscribeState.Sending)
        assertEquals(UnsubscribeStripBody.SENDING, body)
        assertFalse("in flight, the button may not send again", body.acts)
    }

    @Test fun `a finished unsubscribe draws no button at all`() {
        listOf(UnsubscribeState.Sent, UnsubscribeState.Queued).forEach { state ->
            val body = unsubscribeStripBody(state)
            assertEquals("done is done, for $state", UnsubscribeStripBody.DONE, body)
            assertFalse(body.acts)
        }
    }

    @Test fun `a failure says what happened and offers the gesture again`() {
        UnsubscribeFailure.entries.forEach { reason ->
            val body = unsubscribeStripBody(UnsubscribeState.Failed(reason))
            assertEquals("after $reason", UnsubscribeStripBody.FAILED, body)
            assertTrue("a refusal or a dead network is a retry, not a dead end", body.acts)
        }
    }

    @Test fun `the strip's button and the action itself never disagree`() {
        // The defect this whole feature was audited for, in its last possible hiding place: the
        // strip drawing a live button where the action refuses to run is a button that does
        // nothing, and the reverse is a second POST to a list that already answered.
        everyState.forEach { state ->
            listOf(oneClick, mail).forEach { options ->
                assertEquals(
                    "$state: the strip acts exactly when offeredUnsubscribeAction offers something",
                    offeredUnsubscribeAction(options, state) != null,
                    unsubscribeStripBody(state).acts,
                )
            }
        }
    }

    /**
     * Source lint, not a measurement. Compose is not run here; these rules read the strip as text
     */
    @Test fun `the strip is a button with a height floor, and no sentence at rest`() {
        val strip = stripSource()
        assertTrue(
            "the strip must stand on the button's own minimum height — 'heightIn(min = " +
                "ButtonDefaults.MinHeight)' — so the shapes that draw less do not shrink the " +
                "header and reload the body under it. Strip was:\n$strip",
            "heightIn(min = ButtonDefaults.MinHeight)" in strip,
        )
        assertTrue(
            "…and carry no vertical padding of its own on top of that floor",
            ".padding(horizontal = 16.dp)" in strip && "vertical = 10.dp" !in strip,
        )
        assertTrue(
            "the resting strip is a TextButton — the house vocabulary for a secondary action — " +
                "and no longer an OutlinedButton, which is what the two RARE strips use",
            "TextButton(onClick = onUnsubscribe, enabled = body.acts)" in strip &&
                "OutlinedButton" !in strip,
        )
        assertTrue(
            "R.string.message_unsubscribe_banner may no longer be drawn as a line of text: the " +
                "sentence IS the weight this correction removes. It is recycled as the icon's " +
                "contentDescription, where it costs no height",
            "contentDescription = stringResource(R.string.message_unsubscribe_banner)," in strip,
        )
        assertTrue(
            "the icon must be 18 dp and muted (onSurfaceVariant), not 20 dp and primary: a " +
                "tinted icon is how the two rare strips ask for a decision",
            "tint = MaterialTheme.colorScheme.onSurfaceVariant," in strip &&
                "modifier = Modifier.size(18.dp)," in strip,
        )
        assertTrue(
            "the spinner must sit INSIDE the button, in the icon's place and at the icon's size",
            "CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)" in strip,
        )
        assertTrue(
            "the finished states draw the terminal line in onSurfaceVariant and no button",
            "if (body == UnsubscribeStripBody.DONE) {" in strip &&
                "color = MaterialTheme.colorScheme.onSurfaceVariant," in strip,
        )
        assertTrue(
            "the strip must ask unsubscribeStripBody(state) rather than re-decide from the state",
            "val body = unsubscribeStripBody(state)" in strip,
        )
        // Written after watching its mutation survive the whole campaign: swap the failure's
        // colour for onSurfaceVariant and everything above still passes. The failure is the one
        // shape allowed to take more room and to say more, and the colour is what tells it apart
        // from the terminal line, which is drawn in onSurfaceVariant three lines away.
        assertTrue(
            "a failure must be drawn in the error colour: it is the only thing distinguishing " +
                "'the sender's server refused' from 'the request went out', and the two sit in " +
                "the same place in the same strip. Strip was:\n$strip",
            "color = MaterialTheme.colorScheme.error," in strip,
        )
    }

    @Test fun `nothing else in the reader was moved`() {
        // The lot's scope, held by a rule rather than by good intentions: the overflow entry, the
        // confirmation dialog (decision D7) and the strip's place in the header, last, so it
        // never pushes a crypto verdict or a meeting invitation below the fold, are all
        // deliberate and none of them is this correction's business.
        val screen = SOURCE.readText()
        assertTrue(
            "the strip must stay the LAST thing in the header, after the attachments and the " +
                "calendar card",
            "UnsubscribeStrip(options, unsubscribeState, onUnsubscribe)" in screen,
        )
        assertTrue(
            "the overflow entry must still gate on offeredUnsubscribeAction",
            "offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->" in screen,
        )
        assertTrue(
            "and the confirmation must still stand between the entry and the request",
            "R.string.message_unsubscribe_confirm_title" in screen,
        )
    }

    @Test fun `the fallback label names its subject in both places, and cannot grow the strip`() {
        val strip = stripSource()
        val button = stripButtonSource()
        val buttonLines = button.lines().map { it.trim() }
        assertTrue(
            "the clamp must sit on the BUTTON's own label, which is why it is read on the slice " +
                "starting at TextButton(onClick = onUnsubscribe) and not on the whole strip: the " +
                "failure line above the button is the one shape allowed to wrap, and read as part " +
                "of the strip it would answer this rule for a button that grows freely. Checked " +
                "as a whole line rather than with a contains, since 'maxLines = 1, softWrap = " +
                "false' also contains 'maxLines = 1,' and is another rule entirely. Button was:" +
                "\n$button",
            buttonLines.any { it == "maxLines = 1," },
        )
        assertTrue(
            "…and that same button must overflow to an ellipsis rather than to a second line: " +
                "heightIn is a floor, so a label that wraps grows the strip, grows the header, and " +
                "the header's height keys the remember of the body document underneath",
            buttonLines.any { it == "overflow = TextOverflow.Ellipsis," },
        )
        val openPage = strip.lines()
            .map { it.trim() }
            .filter { "UnsubscribeAction.OPEN_PAGE" in it && "R.string." in it }
        assertEquals("exactly one OPEN_PAGE label branch in the strip", 1, openPage.size)
        assertTrue(
            "the button must name what the gesture is ABOUT and not only what it does: " +
                "message_unsubscribe_via_page puts the subject first and the page in brackets, so " +
                "an ellipsis eats the gesture and never the word 'unsubscribe' — which today is " +
                "nowhere on the screen for the commonest sender of all, the one publishing no " +
                "List-Unsubscribe-Post. Branch read:\n${openPage.single()}",
            "R.string.message_unsubscribe_via_page" in openPage.single(),
        )
        val entry = overflowEntrySource()
        assertTrue(
            "the overflow entry must take the same label as the strip for OPEN_PAGE: both read " +
                "the one action offeredUnsubscribeAction returned, so naming it two ways would be " +
                "two names for one gesture. Entry was:\n$entry",
            "R.string.message_unsubscribe_via_page" in entry &&
                "R.string.message_unsubscribe_open_page" !in entry,
        )
    }

    @Test fun `only the confirmation is still allowed to say just 'open page'`() {
        val screen = SOURCE.readText()
        assertEquals(
            "R.string.message_unsubscribe_open_page must be left with exactly one use, the " +
                "confirmation's own button: this count holds both halves of the label change at " +
                "once, that the strip and the overflow entry left the bare gesture behind, and " +
                "that the dialog did not. Under the sentence naming the whole URL and what a page " +
                "load shows the sender, \"Open page\" is the exact consent being asked for, and a " +
                "wider word there would consent to something else",
            1,
            Regex("R\\.string\\.message_unsubscribe_open_page").findAll(screen).count(),
        )
        val confirm = confirmDialogSource()
        assertTrue(
            "…and that single use is the confirmation's button. Confirmation was:\n$confirm",
            "R.string.message_unsubscribe_open_page" in confirm,
        )
    }

    private fun stripButtonSource(): String {
        val strip = stripSource()
        val at = strip.indexOf("TextButton(onClick = onUnsubscribe")
        check(at >= 0) {
            "UnsubscribeStrip no longer draws its gesture as a TextButton(onClick = onUnsubscribe)"
        }
        return strip.substring(at)
    }

    private fun overflowEntrySource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->")
        check(at >= 0) { "MessageScreen.kt no longer gates an overflow entry on offeredUnsubscribeAction" }
        val end = text.indexOf("R.string.message_view_headers", at)
        check(end > at) { "the raw-headers entry no longer follows the unsubscribe entry" }
        return text.substring(at, end)
    }

    private fun confirmDialogSource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("R.string.message_unsubscribe_confirm_title")
        check(at >= 0) { "MessageScreen.kt no longer draws the unsubscribe confirmation" }
        val end = text.indexOf("R.string.settings_cancel", at)
        check(end > at) { "the confirmation no longer ends on its cancel button" }
        return text.substring(at, end)
    }

    private fun stripSource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("private fun UnsubscribeStrip(")
        check(at >= 0) { "MessageScreen.kt no longer declares UnsubscribeStrip — was it renamed?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
