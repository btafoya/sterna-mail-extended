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
 * The home-screen unread counter: one number and the name of what it counts — or, on a cell the
 * user has dragged large enough, one line per account. Which of the two a cell shows is the
 * LAUNCHER's pick between the layouts `UnreadWidgetDraw.draw` hands it, so nothing here reads a
 * geometry and there is deliberately no `onAppWidgetOptionsChanged`.
 *
 * ⛔ It polls nothing, and it never will. `updatePeriodMillis="0"` means the system asks only when
 * the widget is placed, after a reboot and after an app update; there is no timer and no alarm.
 * What DOES keep the number moving is a push, not a poll: [UnreadWidgetPush], collected in
 * `AppContainer` while at least one cell is placed, and a one-off redraw at the tail of
 * `FetchAndNotify.run` for the process that a delivery woke from the dead. This class only owns
 * the draws the SYSTEM asks for, plus the two callbacks that say whether any cell exists at all.
 *
 * What a tap does is decided by [UnreadWidgetTap] and spent in `UnreadWidgetDraw.tapIntent`: the
 * unified inbox when the app has one, the app plainly when it does not. ⛔ The intent behind that
 * [android.app.PendingIntent] carries an action of its own, and that is not decoration — read the
 * note on `tapIntent` before touching it.
 *
 * ⚠ The number is stale by exactly as much as the drawer's "All inboxes (N)" is, never by more:
 * both come from `MailRepository.observeUnifiedInboxUnreadByAccount()`, one derivation, one answer
 * — the drawer and the small cell showing it totalled, the enlarged cell showing its entries (#112).
 *
 * The cost, known and accepted: reaching the container on a cold process runs `AppContainer.init`,
 * which opens Room and books background work. ⛔ Not to be "optimised" by counting unread mail some
 * other way — a second derivation is the one thing the shared rule exists to prevent — and with no
 * periodic update that path is paid at placement, at boot and at update, and at no other time.
 */
class UnreadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while reading the
        // count means opening the account store and Room. goAsync() holds the process at receiver
        // importance until finish(), which EVERY path below reaches: never finishing leaks the
        // broadcast and the system kills the process after ~10 s, finishing twice throws.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                // One emission, then done — this is a draw, not a subscription. BOUNDED: first()
                // suspends until the count arrives, and on a cold process that means opening Room
                // and running any pending migration first. If that never emits, the finally below
                // is never reached, the PendingResult is never finished, and the system kills the
                // process for holding a broadcast open — the very thing a reboot is likely to hit.
                val unreadByAccount = withTimeout(DRAW_TIMEOUT_MS) {
                    container.mailRepository.observeUnifiedInboxUnreadByAccount().first()
                }
                UnreadWidgetDraw.draw(app, appWidgetIds, unreadByAccount)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down with it, and a widget
                // that could not be drawn must cost no more than a stale cell. ⛔ NOTHING is drawn
                // on this path, deliberately: a cell that keeps its old number is honest about
                // being out of date, a cell reset to 0 says the mailbox is empty.
                // The timeout arrives here as TimeoutCancellationException, a CancellationException
                // — caught like any other Throwable, and harmless to swallow because nothing else
                // ever cancels this scope: there is no cancellation here to mistake for success.
                Log.w(TAG, "Unread widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The FIRST cell was placed — from here on the app has a reason to watch the unread total.
     *
     * ⚠ Not per instance: the system sends this once, for the first widget of this provider, and
     * [onDisabled] once for the last one going. That is the granularity the push wants — whether
     * any cell exists, never how many.
     *
     * ⚠ No `intent-filter` is needed for either: the widget manager sends ENABLED and DISABLED
     * with the component set, i.e. as explicit broadcasts to this declared receiver, the way the
     * platform's own sample manifests declare only APPWIDGET_UPDATE.
     *
     * Nothing is drawn here. The system follows this with the ordinary `onUpdate` for the new
     * cell, and drawing twice would only mean counting twice.
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
         * How long the draw may wait for the count before giving the broadcast back undrawn.
         *
         * 5 s, chosen against the only hard number there is: the system allows a `goAsync()`
         * receiver about 10 s before it kills the process, and killing the process while it holds
         * a broadcast is worse than a stale cell — it takes down whatever else the app was doing.
         * Half of that budget leaves room for the container's own start-up (Room opening, a
         * pending migration) on the slowest cold start, and still returns well inside the limit.
         * It is not a deadline for the number to be FRESH: nothing here retries, and the next
         * redraw is the system's next update.
         */
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}
