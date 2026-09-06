package app.sterna.ui.message

import android.view.KeyEvent

/** What one tick of the body height poll decides (see [BodyReveal.step]). */
internal sealed interface HeightPoll {
    /** The body's laid-out height is [px]: report it and stop polling. [settled] is `true` when two
     *  consecutive readings agreed and `false` when the poll fell back to a guess — only a settled
     *  height may decide the bottom bar (see [BodyReveal.barVisible]). */
    data class Report(val px: Int, val settled: Boolean) : HeightPoll

    /** Nothing conclusive yet — poll again. */
    data object Retry : HeightPoll
}

/**
 * One report of the body's scroll geometry: how far it is scrolled and the RAW numbers it was read
 * from. They are carried instead of [maxScrollPx] alone because a scroll range of zero has TWO
 * meanings, and only these three together tell them apart — see [BodyReveal.rangeMeasured].
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
 * Everything the reader needs to decide the Reply/Forward bar, carried forward one report at a time.
 * The bar is ONE value with TWO writers — the height poll's resting measurement and the live scroll
 * reports — which arrive in no fixed order and know nothing of each other, so their shared state and
 * the ordering rule that keeps a late report from undoing an early one live here.
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
 * What one tick of the post-gesture resize probe decides (see [BodyReveal.resizeStep]). The document
 * carries no script, so it cannot say that it changed size, and there is deliberately no permanent
 * size watcher: a body that relayouts in a loop would blink the bar for as long as it is open. So the
 * probe hangs off the END of a gesture.
 *
 * TWO gestures change this height: the quote fold, and PINCHING TO ZOOM — a zoom out reaches
 * [barAfterResize] as a shrink and throws the whole [BarState] away. Accepted.
 */
internal sealed interface ResizeProbe {
    /** The height changed: report the geometry now and stop. */
    data object Report : ResizeProbe

    /** Unchanged so far — the relayout does not land in the same frame as the tap. Poll again. */
    data object Retry : ResizeProbe

    /** Out of ticks with the height unchanged: stop, and report NOTHING. A tap that opened nothing is
     *  the common case, and reporting "nothing moved" would re-found the bar's remembered range on an
     *  arbitrary instant of a body that may still be laying out. */
    data object Done : ResizeProbe
}

/**
 * The message body is drawn by a WebView the reader keeps INVISIBLE until it has reported a laid-out
 * height, from a single poll started at `onPageFinished` that nothing re-arms.
 *
 * So the poll must never end without reporting: one whose readings were all zero once reported
 * nothing at all, and the body stayed behind the spinner for the rest of that page's life. The
 * decision is pulled out here because it can be tested without a WebView.
 */
internal object BodyReveal {
    /**
     * One tick of the poll. [px] is this tick's reading of the content range, [last] the previous one,
     * [maxSeen] the tallest so far, [viewHeightPx] the WebView's own height — a sound last resort,
     * since the range can never be shorter once laid out.
     *
     * Two agreeing readings settle it; otherwise poll on, some bodies relayouting for a while. On the
     * last tick, report unconditionally and never below 1: the number is only a readiness flag, so an
     * imprecise height costs a slightly-off first frame while no height costs the message.
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
     * `contentRangePx()` is floored at the view's own height, so before layout it returns exactly
     * [viewportPx] — which is also what a short body that fits returns, and reading the floor as "it
     * fits" is what put the bar on screen for the first message opened after process start. Only
     * `WebView.contentHeight`, 0 until there is a laid-out document, tells them apart.
     *
     * The cost: on a body that fits the bar appears once the document has laid out.
     */
    fun rangeMeasured(rangePx: Int, viewportPx: Int, contentHeightPx: Int): Boolean =
        rangePx > viewportPx || (rangePx > 0 && contentHeightPx > 0)

    /**
     * Whether the Reply/Forward bar belongs on screen for a body scrolled to [scrollY] out of
     * [maxScrollPx], with [thresholdPx] of tolerance. The bar is the end-of-message affordance: it
     * shows when the body fits or when the reader has reached the bottom.
     *
     * Deliberately a pure function of numbers the reader already has when the height poll settles —
     * evaluated on the first scroll report instead, the reader appeared in two steps (#63).
     */
    fun barVisible(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean =
        maxScrollPx <= thresholdPx || scrollY >= maxScrollPx - thresholdPx

    /**
     * Fold one report — a resting measurement or a live scroll — into the bar's [BarState].
     *
     * [barVisible] is the right question only if the geometry is final, and it is not: a body grows
     * while it lays out, and several reports arrive for one open, from two writers, in no guaranteed
     * order — answering each independently made the bar appear and go away again (#63).
     *
     * So the decision is an ORDERING rule: a measured range only GROWS, a shorter report being a body
     * caught mid-layout; and a bar already shown is taken back only by the reader MOVING.
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

    /**
     * Fold in a report about a body that changed size UNDER THE READER'S OWN GESTURE — the same
     * document, grown or shrunk by the quote fold.
     *
     * [barAfterReport] cannot take it: its growth rule tells a body caught mid-layout from a final
     * measurement, and a folded quote is neither, so the unfolded range would survive the fold and
     * Reply and Forward leave the reader for the life of the page. A SHRINK therefore starts over from
     * a fresh [BarState], which is safe because the caller only asks after a gesture and [resizeStep]
     * only lets through a height that has stopped moving. A GROWTH is [barAfterReport] word for word.
     */
    fun barAfterResize(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val shrank = maxScrollPx < prev.maxScrollPx
        val from = if (shrank) BarState() else prev
        return barAfterReport(from, scrollY, maxScrollPx, thresholdPx)
    }

    /**
     * One tick of the resize probe: [now] is this tick's reading, [atGesture] the reading taken when
     * the gesture ended, [last] the previous tick's.
     *
     * Both conditions are needed: the height must have LEFT the value it had at the gesture, and it
     * must have STOPPED moving. The plateau is not caution but the defect — a body relayouts in steps
     * (7800 → 3000 → 900), and reported, [barAfterResize] re-founds on 3000 while the body settles at
     * 900 producing no further event. Direction is [barAfterResize]'s question.
     */
    fun resizeStep(now: Int, atGesture: Int, last: Int, triesLeft: Int): ResizeProbe {
        if (now != atGesture && now == last) return ResizeProbe.Report
        if (triesLeft <= 0) return ResizeProbe.Done
        return ResizeProbe.Retry
    }

    /**
     * Whether a key event should arm the resize probe: [action], [keyCode] and [repeatCount] are raw.
     * A `<details>` is keyboard-operable, so the keyboard needs the same arming as the finger.
     * THE PRESS AND THE RELEASE, BOTH: `atGesture` must be read BEFORE the document acts on the key,
     * and WHICH of the two it acts on is not knowable here. REPEATS ARE OUT, or holding a key is a
     * permanent size watcher through the back door.
     *
     * The window stays bounded and nothing re-arms it (#63). Whether the platform hands the event
     * to the door that calls this is not decided here; TalkBack is a third path, still open.
     */
    fun keyArmsResizeProbe(action: Int, keyCode: Int, repeatCount: Int): Boolean =
        (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) &&
            repeatCount == 0 &&
            keyCode in ACTIVATION_KEYS

    /** The keys that activate a focused `<summary>`: what a hardware keyboard or a D-pad uses to
     *  open (or close) the fold. Run key by key by `KeyArmsResizeProbeTest`. */
    private val ACTIVATION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
    )
}

/**
 * The reader's running [BarState] for one body, and the events that feed it.
 *
 * A PLAIN holder, deliberately not Compose state: the fold needs the last scroll offset, which changes
 * every scroll frame, so a `mutableStateOf` would invalidate the composition sixty times a second on
 * the very path #5/#6 were about. Only [shown] is worth observing.
 *
 * The methods are the writers, so the rule about which may decide what lives here (#63).
 */
internal class BarReveal {
    /** Whether the bar belongs on screen after every report folded in so far. */
    var shown: Boolean = false
        private set

    /** Everything folded in so far. Exposed for tests; the reader only ever reads [shown]. */
    var state: BarState = BarState()
        private set

    /** The body's height poll finished. [resting] is the geometry it measured, null when it capped out
     *  and fell back to a guess: a fallback is not a measurement, so it decides nothing and the settle
     *  poll has the last word. A SETTLED height can be just as empty — two agreeing readings of the
     *  pre-layout floor — and is inert for the same reason. */
    fun bodyReady(resting: BodyMetrics?, thresholdPx: Int): Boolean {
        if (resting == null || !resting.measured) return shown
        return fold(resting.scrollY, resting.maxScrollPx, thresholdPx)
    }

    /**
     * A live scroll report: the reader moved, or the load's settle poll fired its one terminal report.
     * A report that measured nothing is inert here too.
     *
     * [lastWord] is the ONE exception, because discarding a report is only safe while another can
     * still arrive: the settle poll is capped and its terminal report is the only resting geometry
     * guaranteed, a body that FITS producing no scroll of its own. Withholding it too would trade a
     * bar that did not belong to the message for a bar that never comes.
     */
    fun scrolled(m: BodyMetrics, thresholdPx: Int, lastWord: Boolean = false): Boolean {
        if (!m.measured && !lastWord) return shown
        return fold(m.scrollY, m.maxScrollPx, thresholdPx)
    }

    /**
     * The document was REPLACED — the reader switched between the message's HTML and its text (#149) —
     * so everything folded in so far measured a body no longer on screen. Start over.
     *
     * Both rules in [BodyReveal.barAfterReport] compare reports about ONE body: an 8000 px newsletter
     * flattened to 900 px keeps the old maximum and the bar never comes back in either mode, while a
     * bar earned by a body that fitted would carry over onto a long one. NOT the load-time reset of
     * a body that is still the same document, which must keep its geometry (#63).
     */
    fun documentReplaced() {
        state = BarState()
        shown = false
    }

    /**
     * The body RESIZED under the reader's gesture and the resize probe caught the new geometry. The
     * third writer, and the only one allowed through [BodyReveal.barAfterResize] — the two others
     * report a body they believe unchanged and must keep the mid-layout rule this one sets aside.
     *
     * Do not read the [BodyMetrics.measured] guard as the protection against a mid-layout reading:
     * this door only fires on a document the reader is LOOKING AT, so it passes on every reading. What
     * keeps an intermediate height out is [BodyReveal.resizeStep]'s plateau.
     */
    fun resized(m: BodyMetrics, thresholdPx: Int): Boolean {
        if (!m.measured) return shown
        state = BodyReveal.barAfterResize(state, m.scrollY, m.maxScrollPx, thresholdPx)
        shown = state.shown
        return shown
    }

    private fun fold(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean {
        state = BodyReveal.barAfterReport(state, scrollY, maxScrollPx, thresholdPx)
        shown = state.shown
        return shown
    }
}
