package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hand-over half of a widget tap, RUN — [WidgetTapRelay.step] is executed and every expectation
 * is a literal [WidgetTapStep], never the rule recomputed here. A shipped rule whose arms were
 * swapped or narrowed disagrees with this file instead of agreeing with it.
 *
 * A COLD START IS PLAYED AS A SEQUENCE OF FRAMES, not as one call. That is the whole defect
 * (bench cell C1, 2026-08-17): a tap that STARTS the process arrives in a composition where the
 * NavHost has not composed its start destination, so `listViewModel` is still null. A single call
 * asked in that frame answers honestly "no list"; what matters is what the NEXT frame is told, and
 * whether the order is still alive to be told it. The frames below are therefore written as the
 * sequence `MainNavHost` walks — `Hold` consumes the activity's order and raises the local flag,
 * `Deliver` consumes and lowers it — and each frame's state is asked of the rule in turn.
 *
 * WHAT THIS FILE CANNOT PROVE, and must not be read as proving: the frames are supplied here,
 * not produced. That consuming the activity's flag is what BRINGS the composition where the list
 * exists is read in `MainNavHost`, and no line of production code is traversed by this file — the
 * rule is a truth table over three booleans and nothing else. Bench cell C1 is what settles it.
 */
class WidgetTapRelayTest {

    /**
     * THE TEST THIS FILE EXISTS FOR — the tap that starts the process, frame by frame.
     *
     * Shipped, the effect returned on the null list and never ran again: its keys
     * (`pendingUnifiedOpen`, `listViewModel`) both changed in the SAME later composition only if
     * something else recomposed this host, and `listViewModel` is looked up imperatively, so
     * nothing did. The user tapped the counter on a cold app and got the current account's Inbox.
     */
    @Test fun `a tap that starts the process is kept until the list exists, then delivered`() {
        assertEquals(
            "on a cold start the list does not exist in the composition that receives the tap. " +
                "Dropping the order there is bench cell C1: the user taps the counter, the app " +
                "starts, and they land on the current account's Inbox instead of All inboxes — " +
                "with nothing left to replay, because the activity's order was never consumed " +
                "either. The order must be TAKEN and HELD in this frame.",
            WidgetTapStep.Hold,
            WidgetTapRelay.step(ordered = true, held = false, listReady = false),
        )
        assertEquals(
            "the frame after: the activity's order is gone (Hold consumed it, which is the write " +
                "that forces the recomposition) and the NavHost has composed its start " +
                "destination. The held order must now be delivered — held and forgotten is the " +
                "same wrong screen as never held.",
            WidgetTapStep.Deliver,
            WidgetTapRelay.step(ordered = false, held = true, listReady = true),
        )
        assertEquals(
            "once delivered, nothing is pending. Anything but Ignore here re-navigates to All " +
                "inboxes on the next recomposition, over whatever the user has since opened.",
            WidgetTapStep.Ignore,
            WidgetTapRelay.step(ordered = false, held = false, listReady = true),
        )
    }

    /**
     * The usual path: the app is already running, `singleTask` routes the intent to `onNewIntent`,
     * and the list has existed for a long time. One frame, no holding.
     */
    @Test fun `a tap on a running app is delivered in the frame it arrives`() {
        assertEquals(
            "with the list already composed there is nothing to wait for. Holding here would put " +
                "All inboxes one recomposition late — the counter would look dead to the touch.",
            WidgetTapStep.Deliver,
            WidgetTapRelay.step(ordered = true, held = false, listReady = true),
        )
    }

    /** No order at all, in every list state: the widget was not touched, nothing may move. */
    @Test fun `no order and nothing held moves nothing, list or no list`() {
        assertEquals(
            "without an order the tap effect must not touch the list. Acting here would switch " +
                "the user to All inboxes on a plain rotation or a locale change.",
            mapOf(false to WidgetTapStep.Ignore, true to WidgetTapStep.Ignore),
            mapOf(
                false to WidgetTapRelay.step(ordered = false, held = false, listReady = false),
                true to WidgetTapRelay.step(ordered = false, held = false, listReady = true),
            ),
        )
    }

    /**
     * The cold start that takes more than one frame to compose its start destination: the order is
     * already held and the list is still missing. Keeping it is the only answer that neither loses
     * the tap nor delivers it to a list that does not exist.
     */
    @Test fun `an order already held keeps waiting while the list is still missing`() {
        assertEquals(
            "the list can take more than one composition to appear. Forgetting the held order " +
                "here loses the tap for good — the activity's flag was already consumed.",
            WidgetTapStep.Hold,
            WidgetTapRelay.step(ordered = false, held = true, listReady = false),
        )
    }

    /**
     * Both flags up at once — a second tap landing while the first is still held, or the very frame
     * `onNewIntent` re-raises the activity's flag. With the list there, it is one delivery.
     */
    @Test fun `an order raised again over a held one is delivered once, not queued`() {
        assertEquals(
            "a fresh order arriving on top of a held one is still one visit to All inboxes.",
            WidgetTapStep.Deliver,
            WidgetTapRelay.step(ordered = true, held = true, listReady = true),
        )
        assertEquals(
            "and with the list still missing it stays held, rather than being dropped because " +
                "the activity's flag happened to be up as well.",
            WidgetTapStep.Hold,
            WidgetTapRelay.step(ordered = true, held = true, listReady = false),
        )
    }
}
