package app.sterna.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import app.sterna.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The home-screen unread counter: one number and the name of what it counts — or, on a large enough
 * cell, one line per account. Which of the two is the LAUNCHER's pick between the layouts
 * `UnreadWidgetDraw.draw` hands it, so nothing here reads a geometry.
 *
 * It polls nothing, and it never will: `updatePeriodMillis="0"` means the system asks only at
 * placement, after a reboot and after an app update. What keeps the number moving is a push —
 * [UnreadWidgetPush], collected while at least one cell is placed, plus a one-off redraw at the tail
 * of `FetchAndNotify.run`. What a tap does is decided by [UnreadWidgetTap]; the intent behind
 * that PendingIntent carries an action of its own, which is not decoration.
 *
 * The number is stale by exactly as much as the drawer's "All inboxes (N)" is and never more:
 * both come from one derivation (#112). Not to be "optimised" by counting unread mail some other
 * way — a second derivation is the one thing the shared rule exists to prevent.
 */
class UnreadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while reading the
        // count opens the account store and Room. goAsync() holds the process at receiver
        // importance until finish(), which EVERY path below reaches: never finishing leaks the
        // broadcast and the system kills the process after ~10 s, finishing twice throws.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                // One emission, then done — a draw, not a subscription. BOUNDED: on a cold process
                // first() waits for Room to open and any migration to run, and if it never emits
                // the PendingResult is never finished and the system kills the process.
                val unreadByAccount = withTimeout(DRAW_TIMEOUT_MS) {
                    container.mailRepository.observeUnifiedInboxUnreadByAccount().first()
                }
                UnreadWidgetDraw.draw(app, appWidgetIds, unreadByAccount)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down, and a widget that could
                // not be drawn must cost no more than a stale cell. NOTHING is drawn on this
                // path: a cell that keeps its old number is honest about being out of date, a cell
                // reset to 0 says the mailbox is empty. The timeout arrives as a
                // CancellationException and is harmless to swallow — nothing else cancels this scope.
                Log.w(TAG, "Unread widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The FIRST cell was placed — from here on the app has a reason to watch the unread total.
     *
     * Not per instance: the system sends this once for the first widget of this provider, and
     * [onDisabled] once for the last one going, which is the granularity the push wants. No
     * `intent-filter` is needed for either — the widget manager sends both with the component set.
     * Nothing is drawn here: the system follows this with the ordinary `onUpdate`.
     */
    override fun onEnabled(context: Context) {
        UnreadWidgetPresence.set(true)
    }

    /**
     * The LAST cell went. The collection in `AppContainer` stops on this, and the app goes back to
     * costing a user who has no widget exactly nothing.
     */
    override fun onDisabled(context: Context) {
        UnreadWidgetPresence.set(false)
    }

    private companion object {
        const val TAG = "UnreadWidget"

        /**
         * How long the draw may wait for the count before giving the broadcast back undrawn. 5 s,
         * chosen against the only hard number there is: the system allows a `goAsync()` receiver
         * about 10 s before it kills the process, and killing it while it holds a broadcast is worse
         * than a stale cell. It is not a deadline for the number to be FRESH — nothing retries.
         */
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}
