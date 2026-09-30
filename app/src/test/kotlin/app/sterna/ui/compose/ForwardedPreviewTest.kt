package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the composer shows of a forward's carried original, executed: `forwardedPreview` is the one
 * decision every write of the ViewModel's flow goes through (`ForwardedPreviewWiringTest`).
 */
class ForwardedPreviewTest {

    // Built at run time, so the identity check below cannot be satisfied by an interned constant.
    private val original = ForwardedBlocks(
        text = buildString { append("---------- Forwarded message ----------\n"); append("From: a@example.test\n\nHello") },
        html = "<p>Hello</p>",
    )

    @Test fun `a forward waiting for its original shows the pending block`() {
        assertEquals(ForwardedPreview.Pending, forwardedPreview(ComposeOpening.FORWARD, forwarded = null, failed = false))
    }

    @Test fun `a forward whose original is built shows that original's text, the very string`() {
        val preview = forwardedPreview(ComposeOpening.FORWARD, original, failed = false)
        assertTrue("expected Ready, got $preview", preview is ForwardedPreview.Ready)
        assertSame(
            "the block must show `forwarded.text` itself, the string `bodiesForSend` appends: a " +
                "text rebuilt from the original's fields can drift from what is sent",
            original.text, (preview as ForwardedPreview.Ready).text,
        )
    }

    @Test fun `a forward whose original failed to load shows nothing`() {
        assertEquals(ForwardedPreview.None, forwardedPreview(ComposeOpening.FORWARD, forwarded = null, failed = true))
    }

    @Test fun `a forward as attachment never shows the block, the original is its attachment chip`() {
        assertEquals(ForwardedPreview.None, forwardedPreview(ComposeOpening.FORWARD_ATTACHMENT, forwarded = null, failed = false))
        assertEquals(ForwardedPreview.None, forwardedPreview(ComposeOpening.FORWARD_ATTACHMENT, forwarded = null, failed = true))
        assertEquals(ForwardedPreview.None, forwardedPreview(ComposeOpening.FORWARD_ATTACHMENT, original, failed = false))
    }

    @Test fun `a reply never shows the block, waiting or not`() {
        for (opening in listOf(ComposeOpening.REPLY, ComposeOpening.REPLY_ALL)) {
            assertEquals(ForwardedPreview.None, forwardedPreview(opening, forwarded = null, failed = false))
            assertEquals(ForwardedPreview.None, forwardedPreview(opening, forwarded = null, failed = true))
            assertEquals(ForwardedPreview.None, forwardedPreview(opening, original, failed = false))
        }
    }

    @Test fun `a queued row reopened with its original shows it, the very string`() {
        val preview = forwardedPreview(opening = null, original, failed = false)
        assertTrue("expected Ready, got $preview", preview is ForwardedPreview.Ready)
        assertSame(original.text, (preview as ForwardedPreview.Ready).text)
    }

    @Test fun `a queued row reopened without an original shows nothing, never a wait that cannot end`() {
        assertEquals(ForwardedPreview.None, forwardedPreview(opening = null, forwarded = null, failed = false))
    }
}
