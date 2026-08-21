package app.sterna.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen list of the latest messages of every inbox — the second widget, and a separate
 * provider from [UnreadWidgetProvider] in every respect the system can see.
 *
 * ⛔ It polls nothing (`updatePeriodMillis="0"`), exactly like the counter: the system asks for a
 * draw when a cell is placed, after a reboot and after an app update, and the launcher asks the row
 * factory to refetch. There is no timer, no alarm, and a widget nobody placed costs nothing.
 *
 * ⛔ NOTHING IS DRAWN BEFORE THE SETTING HAS ANSWERED, and nothing is drawn on a read that did not
 * answer in time. The cell then keeps what it was showing — the layout's own "Sterna Mail" on a
 * cell just placed, its previous list otherwise. A default would be a subject on the home screen of
 * someone who asked for none.
 *
 * What a tap does is NOT decided here: a row asks for its own message ([RecentMailWidgetTap]), and
 * the platform's template-plus-fill-in plumbing that carries the request lives in
 * [RecentMailWidgetDraw] and [RecentMailWidgetService].
 */
class RecentMailWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while the read below
        // opens DataStore, the account store and Room. goAsync() holds the process at receiver
        // importance until finish(), which EVERY path here reaches: never finishing leaks the
        // broadcast and the system kills the process after ~10 s, finishing twice throws.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // ⛔ withTimeoutOrNull, never withTimeout: the second reports a slow read as a
                // CancellationException, which a caller that treats cancellation as a sign-out
                // would re-throw. Null here means one thing only — draw nothing.
                val drawing = withTimeoutOrNull(DRAW_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) }
                if (drawing != null) RecentMailWidgetDraw.draw(app, appWidgetIds, drawing)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down with it, and a widget
                // that could not be drawn must cost no more than a stale cell.
                Log.w(TAG, "Latest-messages widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** The FIRST cell of THIS widget was placed. ⛔ Not the counter's — see [RecentMailWidgetPresence]. */
    override fun onEnabled(context: Context) {
        RecentMailWidgetPresence.set(true)
    }

    /** The LAST cell of THIS widget went. */
    override fun onDisabled(context: Context) {
        RecentMailWidgetPresence.set(false)
    }

    private companion object {
        const val TAG = "RecentMailWidget"

        /**
         * How long the draw may wait before giving the broadcast back undrawn. The counter's 5 s,
         * for the counter's reason: the system allows a `goAsync()` receiver about 10 s before it
         * kills the process, and killing the process while it holds a broadcast is worse than a
         * stale cell. Nothing retries; the next redraw is the system's next update.
         */
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}

/**
 * Is at least one LATEST-MESSAGES cell placed? — its own state, and that is the whole point.
 *
 * ⛔ IT IS NOT [UnreadWidgetPresence], and reusing that one would be a silent defect in the OTHER
 * widget: presence is a process-global boolean seeded from `UnreadWidgetDraw.placedIds`, which asks
 * the widget manager about `UnreadWidgetProvider` alone. Removing the last latest-messages cell
 * would then call `set(false)` on the counter's gate, `UnreadWidgetPush` would drop its
 * subscription, and a counter cell still sitting on the home screen would quietly stop following
 * the mail — with nothing on screen to say so, and no way for the user to connect the two.
 *
 * The shape is the counter's, arm for arm: a seed read from the widget manager (the only answer a
 * process that started with a cell already placed would ever get, since `onEnabled` fired on a
 * process that has since died) and the two callbacks for the answer changing under a running
 * process.
 *
 * [RecentMailWidgetPush] is what collects it: `AppContainer.init` observes the unified inbox only
 * while this answer is true, so the gate below is what keeps a widget nobody placed from costing a
 * Room subscription woken by every write to `emails` and to `snoozed`.
 *
 * ⛔ The SEED is half of that answer and it is not decorative: `onEnabled` fires once, on the
 * process that was running when the first cell was placed, and that process is long dead by the
 * next boot. Without the seed — or with it read the wrong way round, which is one `!` — a cell that
 * has been sitting on the home screen for weeks never opens the gate again, and the cell stops
 * following the mail exactly as it did before the push pane existed. It is pinned line by line by
 * `RecentMailWidgetPushWiringLintTest`, because no JVM test can call it: it needs a `Context` and
 * the widget manager.
 */
internal object RecentMailWidgetPresence {

    private val state = MutableStateFlow(false)

    @Volatile private var seeded = false

    /** The live answer, seeded from the widget manager on the first call. */
    fun placed(context: Context): StateFlow<Boolean> {
        if (!seeded) {
            seeded = true
            set(anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context)))
        }
        return state
    }

    /**
     * The same flow, WITHOUT the seed — what [set] writes into, and the handle a JVM test can hold:
     * seeding needs a [Context] and the widget manager, neither of which exists in that suite.
     */
    val live: StateFlow<Boolean> get() = state

    /** The system said the first cell appeared, or the last one went. */
    fun set(placed: Boolean) {
        state.value = placed
    }
}
