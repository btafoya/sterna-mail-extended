package app.sterna.ui.message

/** What one tick of the body height poll decides (see [BodyReveal.step]). */
internal sealed interface HeightPoll {
    /**
     * The body's laid-out height is [px]: report it and stop polling.
     *
     * [settled] tells the two ways a height can be reported apart: `true` when two consecutive
     * readings of the laid-out content range agreed (a real measurement), `false` when the poll
     * ran out of ticks and fell back to the tallest reading / the view's own height (a guess that
     * keeps the body from staying invisible). Only a settled height is trustworthy enough to
     * decide the bottom bar with — see [BodyReveal.barVisible].
     */
    data class Report(val px: Int, val settled: Boolean) : HeightPoll

    /** Nothing conclusive yet — poll again. */
    data object Retry : HeightPoll
}

/**
 * One report of the body's scroll geometry: how far it is scrolled ([scrollY], 0 on a fresh open)
 * and the RAW numbers it was read from — the WebView's content range ([rangePx]), its visible
 * extent ([viewportPx]) and the document's own laid-out height ([contentHeightPx], `WebView
 * .contentHeight`, in CSS px and used here only as "is there a laid-out document at all").
 *
 * The raw numbers are carried instead of the derived [maxScrollPx] alone because a scroll range of
 * zero has TWO meanings, and only these three numbers together tell them apart — see
 * [BodyReveal.rangeMeasured].
 */
internal data class BodyMetrics(
    val scrollY: Int,
    val rangePx: Int,
    val viewportPx: Int,
    val contentHeightPx: Int,
) {
    /** How far this body can scroll: see [BodyReveal.maxScroll]. */
    val maxScrollPx: Int get() = BodyReveal.maxScroll(rangePx, viewportPx)

    /** Whether this report measured anything at all: see [BodyReveal.rangeMeasured]. */
    val measured: Boolean get() = BodyReveal.rangeMeasured(rangePx, viewportPx, contentHeightPx)
}

/**
 * Everything the reader needs to decide the Reply/Forward bar, carried forward one report at a
 * time by [BodyReveal.barAfterReport].
 *
 * The bar is ONE value with TWO writers: the height poll's resting measurement (which reveals the
 * bar in the same frame as the body) and the live scroll reports (which reveal it at the end of a
 * long body, and which the load's settle poll also fires once). They do not arrive in a fixed
 * order, and neither knows what the other decided — so the state they share lives here, and the
 * ordering rule that keeps a late report from undoing an early one is written once, in one place.
 */
internal data class BarState(
    /** Whether the bar belongs on screen. */
    val shown: Boolean = false,
    /** The tallest scroll range reported for this body so far. A measured range only ever grows. */
    val maxScrollPx: Int = 0,
    /** The scroll offset the last report carried: a report that repeats it moved nothing. */
    val scrollY: Int = 0,
)

/**
 * The message body is drawn by a WebView that the reader keeps INVISIBLE (alpha 0, spinner on
 * top) until it has reported a laid-out height, so a half-laid-out body is never shown. That
 * report is produced by a single poll started from `onPageFinished`, and it is the ONLY thing
 * that reveals the body — nothing else re-arms it.
 *
 * So the poll must never end without reporting. It used to: on the cap it reported the tallest
 * reading only `if (maxSeen > 0)`, and a poll whose readings were ALL zero (the WebView had no
 * size for the whole window) therefore reported nothing at all. The body then stayed at alpha 0
 * behind the spinner for the rest of that page's life, with no recovery at all — closing and
 * reopening the message was the only way out.
 *
 * The decision is pulled out here because it is the part that can be reasoned about — and
 * tested — without a WebView, a window or a layout pass.
 */
internal object BodyReveal {
    /**
     * One tick of the poll.
     *
     * [px] is this tick's reading of the content range, [last] the previous one, [maxSeen] the
     * tallest seen so far, [triesLeft] how many ticks remain, [viewHeightPx] the WebView's own
     * height (its content range can never be shorter than that once it is laid out, so it is a
     * sound last resort).
     *
     * Two readings that agree settle it. Otherwise, while ticks remain, poll again — some bodies
     * (nested tables + inline images) relayout for a while. On the last tick, report
     * unconditionally and never below 1: the number is only a readiness flag for the reader (it
     * sizes nothing), so an imprecise height costs a slightly-off first frame, while no height at
     * all costs the whole message.
     */
    fun step(px: Int, last: Int, maxSeen: Int, triesLeft: Int, viewHeightPx: Int): HeightPoll {
        if (px > 0 && px == last) return HeightPoll.Report(px, settled = true)
        if (triesLeft <= 0) return HeightPoll.Report(maxOf(maxSeen, viewHeightPx, 1), settled = false)
        return HeightPoll.Retry
    }

    /**
     * How far a body of [contentRangePx] can scroll inside a [viewportPx]-tall window. Zero when it
     * fits: there is nothing to scroll, so the body is already at its end.
     */
    fun maxScroll(contentRangePx: Int, viewportPx: Int): Int =
        (contentRangePx - viewportPx).coerceAtLeast(0)

    /**
     * Whether a reading of the content range MEASURED anything, or merely read the floor.
     *
     * `BodyWebView.contentRangePx()` is `computeVerticalScrollRange()` floored at the view's own
     * height, so before the document is laid out it returns exactly [viewportPx] — which is also
     * what a short body that genuinely fits returns. The two are indistinguishable from the range
     * alone, and reading the floor as "it fits" is what put the Reply/Forward bar on screen for the
     * first message opened after process start (the process's first WebView boots its renderer while
     * the settle poll is already reading, so the poll reads the floor three times, concludes, and
     * the ordering rule of [barAfterReport] then makes that verdict permanent).
     *
     * A range TALLER than the viewport is certainly a measurement: the floor cannot produce it.
     * Otherwise only the document itself can tell the two apart — `WebView.contentHeight` is 0
     * until there is a laid-out document, and the reader already treats it as credible (it is the
     * fallback the height poll measures with when the view is not a [BodyMetrics] source).
     *
     * The cost of this choice, paid deliberately: on a body that fits, the bar now appears when the
     * document has laid out rather than on the first reading of the floor. Erring this way costs a
     * slightly later bar; erring the other way costs a bar that does not belong to the message.
     */
    fun rangeMeasured(rangePx: Int, viewportPx: Int, contentHeightPx: Int): Boolean =
        rangePx > viewportPx || (rangePx > 0 && contentHeightPx > 0)

    /**
     * Whether the Reply/Forward bar belongs on screen for a body scrolled to [scrollY] out of
     * [maxScrollPx], with [thresholdPx] of tolerance (a few dp: a body one pixel short of its end,
     * or a scroll range one pixel taller than the viewport, must not count as "there is more").
     *
     * The bar is the end-of-message affordance: it shows when the body fits the screen (nothing to
     * scroll) or when the reader has reached the bottom.
     *
     * This is deliberately a pure function of numbers the reader ALREADY has when the body's height
     * poll settles. It used to be evaluated only on the first scroll report, which arrives a few
     * hundred milliseconds after the body is revealed — so the reader appeared in two steps, and
     * with the system animations turned off the second step was a blink (Codeberg #63).
     */
    fun barVisible(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean =
        maxScrollPx <= thresholdPx || scrollY >= maxScrollPx - thresholdPx

    /**
     * Fold one report — a resting measurement or a live scroll — into the bar's [BarState].
     *
     * [barVisible] answers "does this geometry want the bar", which is the right question only if
     * the geometry is final. It is not: a body grows for as long as it is laying out (remote images
     * decoding, a newsletter's tables reflowing), and the reader receives SEVERAL reports for one
     * open, from two independent writers, in no guaranteed order. Answering each of them
     * independently is what makes the bar appear and then go away again (Codeberg #63): the height
     * poll settles on a still-short body, the bar comes up with the body, and the load's settle poll
     * then reports the grown range and takes it straight back.
     *
     * So the decision is an ORDERING rule over the reports, not a verdict on the latest one:
     *
     *  - **A measured range only grows.** A report shorter than one already seen is a body caught
     *    mid-layout (or a scroll range reset at load), never a body that shrank; taking the smaller
     *    number would let a long body claim it fits. This is the same reasoning as the tallest-seen
     *    reading the height poll reports at its cap, applied ACROSS the two writers rather than
     *    inside one of them.
     *  - **A bar already shown is taken back only by the reader moving.** Wanting it and then not
     *    wanting it, at an unchanged scroll offset, can only mean a measurement landed late — and
     *    that is exactly the retraction the reader sees as a blink. Scrolling away from the end of a
     *    message still hides it, because that report moves [scrollY]; it is the reader's own gesture,
     *    not a spontaneous change.
     *
     * Nothing here can show the bar on its own: it still takes a report, and the caller only reports
     * geometry it actually measured (a settled height, or a scroll seen after the load settled). An
     * unsettled height decides nothing, here as before.
     */
    fun barAfterReport(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val range = maxOf(prev.maxScrollPx, maxScrollPx)
        val readerMoved = scrollY != prev.scrollY
        val wants = barVisible(scrollY, range, thresholdPx)
        return BarState(
            shown = if (prev.shown && !readerMoved) true else wants,
            maxScrollPx = range,
            scrollY = scrollY,
        )
    }
}

/**
 * The reader's running [BarState] for one body, and the two events that feed it.
 *
 * A PLAIN holder, deliberately not Compose state. The fold needs the last scroll offset to tell a
 * reader who moved from a measurement that landed late, and that offset changes on every scroll
 * frame — so holding the whole [BarState] in a `mutableStateOf` would invalidate the reader's
 * composition sixty times a second, on the very scroll path Codeberg #5/#6 were about. The live
 * offset is kept out of composition on purpose everywhere else in the reader (the header's
 * translate reads it in the layout phase only); this keeps the bar's bookkeeping out of it too.
 * Only [shown] — a Boolean that changes a handful of times per message — is worth observing, and
 * the caller mirrors it into a Compose state where writing the same value again costs nothing.
 *
 * The two methods are the two writers, so the rule about which of them may decide what lives here
 * rather than at the call sites: they fold into ONE state, which is the whole point (#63).
 */
internal class BarReveal {
    /** Whether the bar belongs on screen after every report folded in so far. */
    var shown: Boolean = false
        private set

    /** Everything folded in so far. Exposed for tests; the reader only ever reads [shown]. */
    var state: BarState = BarState()
        private set

    /**
     * The body's height poll finished. [resting] is the scroll geometry it measured, and is null
     * when it did not actually measure — it capped out and fell back to the tallest reading or the
     * view's own height. A fallback height is not a measurement, so it decides nothing at all here:
     * it neither reveals the bar nor takes one back, and the settle poll below has the last word,
     * exactly as before #63. Returns [shown].
     *
     * A SETTLED height can be just as empty: two agreeing readings of the pre-layout floor settle
     * the poll perfectly well, and the geometry they carry measured nothing
     * ([BodyMetrics.measured]). Such a report is inert for the same reason as a null one.
     */
    fun bodyReady(resting: BodyMetrics?, thresholdPx: Int): Boolean {
        if (resting == null || !resting.measured) return shown
        return fold(resting.scrollY, resting.maxScrollPx, thresholdPx)
    }

    /**
     * A live scroll report: the reader moved, or the load's settle poll fired its one terminal
     * report now that the scroll range is final. Returns [shown].
     *
     * A report that measured nothing ([BodyMetrics.measured]) is inert here too, for the same
     * reason as a fallback height above: it neither reveals the bar nor takes one back.
     *
     * [lastWord] is the ONE exception, and it exists because discarding a report is only safe while
     * another one can still arrive. The load's settle poll keeps polling until it reads a laid-out
     * document, but it is capped (~2.5s) and its terminal report is the only report of the resting
     * geometry the reader is guaranteed: a body that FITS produces no scroll of its own, so there is
     * no later gesture to fall back on. Withholding the capped report as well would trade the bar
     * that did not belong to the message for a bar that never comes at all — the worse of the two,
     * because nothing recovers from it. So at the cap the reader takes what it has: after 2.5s of
     * reading the floor, the floor is the best answer anyone is going to get.
     */
    fun scrolled(m: BodyMetrics, thresholdPx: Int, lastWord: Boolean = false): Boolean {
        if (!m.measured && !lastWord) return shown
        return fold(m.scrollY, m.maxScrollPx, thresholdPx)
    }

    /**
     * The document was REPLACED — the reader switched between the message's HTML and its text
     * (#149) — so everything folded in so far measured a body that is no longer on screen. Start
     * over from a fresh [BarState]: range forgotten, bar not shown.
     *
     * Both rules in [BodyReveal.barAfterReport] compare reports about ONE body. "A measured range
     * only grows" tells a body caught mid-layout from a body that shrank, which two DIFFERENT
     * bodies have nothing to say about: an 8000 px newsletter flattened to 900 px of text keeps the
     * old maximum, the shorter body can never reach it, and the Reply/Forward bar never comes back
     * — in EITHER mode — until the message is closed, because the state is remembered on the
     * message id. "A shown bar is only taken back by the reader moving" is the same mistake seen
     * from the other side: a bar earned by a body that fitted would carry over onto a long one.
     *
     * This is NOT the load-time reset of a body that is still the same document: a body that
     * relayouts, decodes its remote images or gets rethemed must keep its geometry, or the bar
     * disappears mid-read (Codeberg #63). Only a change of reading mode may call this.
     */
    fun documentReplaced() {
        state = BarState()
        shown = false
    }

    private fun fold(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean {
        state = BodyReveal.barAfterReport(state, scrollY, maxScrollPx, thresholdPx)
        shown = state.shown
        return shown
    }
}
