package app.sterna.ui.message

import android.view.KeyEvent

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
 * What one tick of the post-gesture resize probe decides (see [BodyReveal.resizeStep]).
 *
 * The body's document carries no script (`javaScriptEnabled = false`, CSP `default-src 'none'`), so
 * it cannot tell the reader that it changed size, and there is deliberately no permanent size
 * watcher either: a body that relayouts in a loop exists (it is why the load's settle poll is
 * capped), and a watcher would blink the bar on it for as long as it is open. So the probe hangs
 * off the END of a gesture — once per gesture, never per frame.
 *
 * TWO gestures change this document's height, not one. Tapping the `<summary>` of the `<details>`
 * that folds a reply's quoted history is the one this door was written for; PINCHING TO ZOOM is the
 * other, and it is supported here (`builtInZoomControls`, and the ACTION_POINTER_DOWN branch of the
 * body's touch handler exists because of it). A zoom therefore ends in an ACTION_UP, arms this
 * probe, and — if it zoomed OUT — reaches [barAfterResize] as a shrink, which throws the whole
 * [BarState] away, [BarState.shown] included. That is ACCEPTED, and it is the same reasoning as
 * the fold: the reader deliberately changed the geometry the bar was decided on, so deciding again
 * from the new geometry is what she asked for. It is not a case this probe can, or should, refuse.
 */
internal sealed interface ResizeProbe {
    /** The height changed: report the geometry now and stop. */
    data object Report : ResizeProbe

    /** Unchanged so far — the relayout does not land in the same frame as the tap. Poll again. */
    data object Retry : ResizeProbe

    /**
     * Out of ticks with the height unchanged: stop, and report NOTHING.
     *
     * A tap that opened nothing (a paragraph, a link, a scroll that ended in a tap) is the common
     * case. Reporting "nothing moved" would re-found the bar's remembered range on an arbitrary
     * instant of a body that may still be laying out, at every one of those taps.
     */
    data object Done : ResizeProbe
}

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

    /**
     * Fold in a report about a body that changed size UNDER THE READER'S OWN GESTURE — the same
     * document, still on screen, that grew or shrank because she opened or closed the `<details>`
     * holding a reply's quoted history.
     *
     * [barAfterReport] cannot take this report. Its first rule — a measured range only grows —
     * exists to tell a body caught mid-layout from a final measurement, and a folded quote is
     * neither: the document really is two screens shorter than it was. Fed through it, the range of
     * the UNFOLDED body survives the fold, the short body can never reach it again, and
     * [barVisible] answers "there is more to read" for ever: Reply and Forward leave the reader for
     * the life of the page, with nothing but reopening the message to bring them back (measured on
     * the bench: 60 flings down did not).
     *
     * So a SHRINK starts over from a fresh [BarState] — range forgotten, bar not shown — and then
     * folds this report into it, exactly as the first report of a newly opened body would be. It is
     * the same reasoning as [BarReveal.documentReplaced], applied to a document that was not
     * replaced. Two things, TOGETHER, are what make it safe: the caller only asks after a gesture
     * of the reader's, and [resizeStep] only lets through a height that has stopped moving. Neither
     * suffices alone — a gesture is followed by a relayout in steps, and a plateau reached without
     * a gesture is just a body that finished laying out, which the growth rule already handles.
     *
     * A GROWTH is [barAfterReport], word for word, deliberately. Unfolding is measured green: the
     * bar stays where it is until the reader moves. Re-founding on a growth would send a bar that is
     * already on screen away without a gesture — which is the retraction the reader sees as a blink
     * (Codeberg #63), reintroduced through a new door.
     */
    fun barAfterResize(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val shrank = maxScrollPx < prev.maxScrollPx
        val from = if (shrank) BarState() else prev
        return barAfterReport(from, scrollY, maxScrollPx, thresholdPx)
    }

    /**
     * One tick of the resize probe: [now] is this tick's reading of the content range, [atGesture]
     * the reading taken when the reader's gesture ended, [last] the previous tick's reading, and
     * [triesLeft] how many ticks remain.
     *
     * Two conditions, and BOTH are needed. The height must have LEFT the value it had at the
     * gesture — otherwise nothing happened, which is what most taps do — and it must have STOPPED
     * moving, i.e. this reading repeats the previous one.
     *
     * The plateau is not caution, it is the defect: a body relayouts in steps (a forty-message
     * thread with inline images goes 7800 → 3000 → 900), so the first reading that DIFFERS is a
     * number in transit. Reported, [barAfterResize] re-founds the bar's range on 3000, the body
     * then settles at 900 without producing a single event — it fits, so it never scrolls, so
     * nothing reports again — and the bar stays away until the message is reopened: the very
     * symptom this door exists to fix, one step further along. Both other readers of the content
     * range in this reader take the same precaution for the same reason ([step] wants two agreeing
     * readings, the load's settle poll three).
     *
     * Which DIRECTION it moved is not asked here: a fold and an unfold settle the same way, and
     * what to do about each is [barAfterResize]'s question. An expiry reports nothing at all —
     * including an expiry on a body still in transit, whose height is not an answer either; see
     * [ResizeProbe.Done] for why that silence is the point.
     */
    fun resizeStep(now: Int, atGesture: Int, last: Int, triesLeft: Int): ResizeProbe {
        if (now != atGesture && now == last) return ResizeProbe.Report
        if (triesLeft <= 0) return ResizeProbe.Done
        return ResizeProbe.Retry
    }

    /**
     * Whether a key event should arm the resize probe: [action] and [repeatCount] are the raw
     * `KeyEvent.getAction()` / `getRepeatCount()`, [keyCode] the raw code.
     *
     * A `<details>` is keyboard-operable: with a hardware keyboard or a D-pad the quoted history
     * folds without a single touch event, and folding it really SHORTENS a document that announces
     * nothing (no script, no size callback, and a body at the top does not scroll as it shrinks).
     * So the keyboard needs the same arming as the finger.
     *
     * THE PRESS AND THE RELEASE, BOTH. The probe's `atGesture` reading has to be taken BEFORE the
     * document acts on the key, or [resizeStep] compares the folded height with itself, never sees
     * `now != atGesture`, and expires in silence: Reply/Forward stay gone until the message is
     * reopened. WHICH of the two events the document acts on is NOT knowable from this repo, and
     * there is reason to think it differs between keys — the usual reading of Blink is that a
     * keydown on SPACE only marks the `<summary>` active and the synthesised click leaves on the
     * KEYUP, while ENTER and DPAD_CENTER act on the press. So this does not bet on it.
     *
     * The costs are asymmetric, which is what decides it. Arming on the instant the document did
     * NOT use costs one bounded probe that reads the same height twice and reports nothing
     * ([ResizeProbe.Done], silent). Arming on only one of them, if the guess is wrong, loses the
     * bar exactly as the shipped defect did — and a held SPACE would lose it even under the other
     * guess, since the probe's twenty ticks expire long before the release arrives.
     *
     * REPEATS ARE OUT. Holding a key produces DOWN after DOWN with a growing [repeatCount] (and
     * batched `ACTION_MULTIPLE` events). Arming on those would hang a fresh probe off the UI thread
     * several times a second for as long as the key is held — a permanent size watcher through the
     * back door, and the bar-blink of Codeberg #63 that the probe's bounded budget exists to keep
     * shut. The release that ends the burst carries `repeatCount` 0, so the state she stopped on is
     * still measured, once.
     *
     * The probe's window stays BOUNDED (20 ticks of 50ms, ~1s from the event that armed it) and
     * nothing re-arms it: a fold that somehow reached its plateau later than that is not caught,
     * and the bar would stay as it was until her next gesture. That limit is deliberate (#63) and
     * this door does not lift it.
     *
     * WHAT THIS DOES NOT DECIDE, and what no JVM test in this repo can reach: whether the
     * platform ever hands the event to the door that calls this. It must be `dispatchKeyEvent` —
     * `onKeyUp` is only reached for a key nobody consumed, and Blink consumes the one that works
     * the fold. That half has one falsifier only, a bench pass. TalkBack is a third path and is
     * covered by neither door: it activates the `<summary>` through an accessibility action on a
     * virtual Chromium node, which produces no key event and no touch event. Still open.
     *
     * Runs in a JVM test with no `android.jar`: every `KeyEvent` constant here is a Java
     * `static final int`, inlined by the compiler, so nothing loads the class at run time.
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
 * The methods are the writers — the height poll, the live scroll, and the reader's own gesture
 * resizing the document ([resized]) — so the rule about which of them may decide what lives here
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

    /**
     * The body RESIZED under the reader's gesture: she opened or closed the `<details>` that folds
     * the quoted history, and the resize probe caught the new geometry. Returns [shown].
     *
     * The third writer, and the only one allowed through [BodyReveal.barAfterResize] — the two
     * others report a body they believe is unchanged, and must keep the mid-layout rule that this
     * one is allowed to set aside.
     *
     * A report that measured nothing ([BodyMetrics.measured]) is inert here as everywhere else, but
     * do not read that guard as the protection against a mid-layout reading: it is not, and it
     * cannot be. This door only fires on a document the reader is LOOKING AT, so `contentHeight` is
     * long since positive and [BodyReveal.rangeMeasured] passes on every reading, transient ones
     * included. What keeps an intermediate height out is the plateau in [BodyReveal.resizeStep].
     * The guard is kept for what it does do — a view detached or reloaded under the probe reads the
     * floor, and that reading decides nothing — and it costs one comparison.
     *
     * There is no `lastWord` escape here: that escape exists because a body that fits never scrolls
     * again, and this door has no such dead end — it is armed by a gesture, and a gesture is always
     * followed by another one.
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
