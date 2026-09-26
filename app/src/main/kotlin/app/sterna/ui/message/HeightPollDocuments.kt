package app.sterna.ui.message

/**
 * Which document a height poll measures. The reader reloads its body in place (images allowed, a
 * sender rule), and a poll left from the previous document, or its `onPageFinished` still queued,
 * outlives the reload. Its report would reach the fresh [PageScale] before the new document is laid
 * out: the body would count as revealed, and the new document's first `onScaleChanged`, the WebView
 * fitting it, would read as a pinch away from the previous scale and fold the header (#171). Only a
 * poll of the current document may report.
 */
internal class HeightPollDocuments {
    private var current: Any = Any()
    private var started = false

    fun documentLoading() {
        current = Any()
        started = false
    }

    fun pageStarted() {
        started = true
    }

    /**
     * The token a poll opened by this `onPageFinished` measures, or null when it must not poll. An end
     * with no `onPageStarted` since [documentLoading] is the replaced document's, already queued when
     * the reload began. Should a WebView ever skip `onPageStarted`, nothing polls: the fallbacks reveal
     * the body and [PageScale.layoutScale] stays null, a ratio of 1, never a wrong reference.
     */
    fun pageFinished(): Any? = current.takeIf { started }

    fun step(document: Any, px: Int, last: Int, maxSeen: Int, triesLeft: Int, viewHeightPx: Int): HeightPoll =
        if (document !== current) HeightPoll.Superseded
        else BodyReveal.step(px, last, maxSeen, triesLeft, viewHeightPx)
}
