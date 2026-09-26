package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A body reloaded in place (images allowed) while the previous document's height poll is still
 * ticking, or its `onPageFinished` still queued: only a poll of the current document may report,
 * executed through [HeightPollDocuments].
 */
class HeightPollDocumentsTest {

    private fun HeightPollDocuments.started(): Any {
        pageStarted()
        return checkNotNull(pageFinished()) { "a started document's onPageFinished opened no poll" }
    }

    @Test fun `a poll of a replaced document reports nothing, even on agreeing readings or at the cap`() {
        val documents = HeightPollDocuments()
        val old = documents.started()
        documents.documentLoading()
        assertEquals(
            "the previous document's poll read two agreeing heights after a reload and reported: that " +
                "report reveals the new document before it is laid out and folds its header",
            HeightPoll.Superseded,
            documents.step(old, px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800),
        )
        assertEquals(
            "the previous document's poll reported at its cap after a reload",
            HeightPoll.Superseded,
            documents.step(old, px = 700, last = 1200, maxSeen = 1600, triesLeft = 0, viewHeightPx = 800),
        )
        assertEquals(
            HeightPoll.Superseded,
            documents.step(old, px = 700, last = 1200, maxSeen = 1600, triesLeft = 10, viewHeightPx = 800),
        )
    }

    @Test fun `the current document's poll still settles, retries and always reports at its cap`() {
        val documents = HeightPollDocuments()
        documents.documentLoading()
        val current = documents.started()
        assertEquals(
            HeightPoll.Report(900, settled = true),
            documents.step(current, px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800),
        )
        assertEquals(
            HeightPoll.Retry,
            documents.step(current, px = 700, last = 1200, maxSeen = 1600, triesLeft = 10, viewHeightPx = 800),
        )
        assertEquals(
            "the cap is the only thing that reveals a body that never settles, it must still report",
            HeightPoll.Report(1920, settled = false),
            documents.step(current, px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 1920),
        )
    }

    @Test fun `a document loaded without documentLoading, as the print path's, polls once started`() {
        val documents = HeightPollDocuments()
        val token = documents.started()
        assertEquals(
            HeightPoll.Report(900, settled = true),
            documents.step(token, px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800),
        )
    }

    @Test fun `every reload replaces the document, not only the first`() {
        val documents = HeightPollDocuments()
        documents.documentLoading()
        val first = documents.started()
        documents.documentLoading()
        documents.pageStarted()
        assertEquals(
            HeightPoll.Superseded,
            documents.step(first, px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800),
        )
    }

    @Test fun `the end of a document that was not started opens no poll`() {
        val documents = HeightPollDocuments()
        documents.started()
        documents.documentLoading()
        assertNull(
            "an onPageFinished handled after documentLoading and before the new document's onPageStarted is " +
                "the replaced document's, and it opened a poll on the new document's token",
            documents.pageFinished(),
        )
    }

    @Test fun `the replaced document's queued end opens no poll, the current one's does`() {
        val documents = HeightPollDocuments()
        documents.started()

        documents.documentLoading()
        val queuedEnd = documents.pageFinished()
        documents.pageStarted()
        val currentEnd = documents.pageFinished()

        assertNull("the replaced document's queued onPageFinished opened a poll", queuedEnd)
        assertNotNull("the current document's onPageFinished opened no poll", currentEnd)
    }

    @Test fun `a PageScale revealed only by the polls it lets through opens a reloaded header whole`() {
        // initial-scale=0.6 on a 2.625 device: Chromium fits the reloaded document to 1.575 before it is
        // laid out. The replaced document's onPageFinished is handled after documentLoading.
        val documents = HeightPollDocuments()
        documents.started()
        var pageScale = PageScale().scaled(2.625f, 1.575f).bodyRevealed()

        fun report(token: Any?, px: Int) {
            if (token == null) return
            val step = documents.step(token, px = px, last = px, maxSeen = px, triesLeft = 20, viewHeightPx = 1920)
            if (step is HeightPoll.Report) pageScale = pageScale.bodyRevealed()
        }

        documents.documentLoading()
        pageScale = PageScale()
        report(documents.pageFinished(), 2400)
        documents.pageStarted()
        pageScale = pageScale.scaled(2.625f, 1.575f)
        report(documents.pageFinished(), 3100)

        val folded = headerCollapsePx(0, 538, pageScale.scale, pageScale.layoutScale)
        assertEquals(
            "the reloaded message opened with its header folded by $folded px " +
                "(layoutScale ${pageScale.layoutScale}, scale ${pageScale.scale}) with nobody pinching",
            0, folded,
        )
        assertEquals(1.575f, pageScale.layoutScale)
    }
}
