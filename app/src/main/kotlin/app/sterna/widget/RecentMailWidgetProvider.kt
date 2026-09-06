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
 * It polls nothing, exactly like the counter. Nothing is drawn before the setting has
 * answered, and nothing on a read that did not answer in time: the cell then keeps what it was
 * showing, a default being a subject on the home screen of someone who asked for none.
 *
 * What a tap does is not decided here: a row asks for its own message ([RecentMailWidgetTap]).
 */
class RecentMailWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while the read below
        // opens DataStore, the account store and Room. goAsync() holds the process until finish(),
        // which EVERY path here reaches: never finishing leaks the broadcast.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // withTimeoutOrNull, never withTimeout, whose CancellationException a caller
                // that reads cancellation as a sign-out would re-throw. Null means: draw nothing.
                val drawing = withTimeoutOrNull(DRAW_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) }
                if (drawing != null) RecentMailWidgetDraw.draw(app, appWidgetIds, drawing)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down, and a widget that could
                // not be drawn must cost no more than a stale cell.
                Log.w(TAG, "Latest-messages widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** The FIRST cell of THIS widget was placed. Not the counter's — see [RecentMailWidgetPresence]. */
    override fun onEnabled(context: Context) {
        RecentMailWidgetPresence.set(true)
    }

    override fun onDisabled(context: Context) {
        RecentMailWidgetPresence.set(false)
    }

    private companion object {
        const val TAG = "RecentMailWidget"

        /**
         * How long the draw may wait before giving the broadcast back undrawn. The counter's 5 s,
         * for the counter's reason: the system kills a `goAsync()` receiver's process at about 10 s.
         */
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}

/**
 * Is at least one LATEST-MESSAGES cell placed? — its own state, and that is the whole point.
 *
 * It is NOT [UnreadWidgetPresence], whose seed asks the widget manager about the counter alone:
 * removing the last latest-messages cell would close the COUNTER's gate, and a counter cell still
 * on the home screen would quietly stop following the mail.
 *
 * The shape is the counter's, arm for arm: a seed read from the widget manager — the only answer a
 * process that started with a cell already placed would get — plus the two callbacks.
 *
 * That seed is not decorative: `onEnabled` fires once, on a process long dead by the next boot,
 * so without it (or with it read the wrong way round, which is one `!`) a cell sitting there for
 * weeks never opens the gate again. Pinned line by line by `RecentMailWidgetPushWiringLintTest`,
 * no JVM test being able to call it.
 */
internal object RecentMailWidgetPresence {

    private val state = MutableStateFlow(false)

    @Volatile private var seeded = false

    fun placed(context: Context): StateFlow<Boolean> {
        if (!seeded) {
            seeded = true
            set(anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context)))
        }
        return state
    }

    /**
     * The same flow WITHOUT the seed — what [set] writes into, and the handle a JVM test can hold.
     */
    val live: StateFlow<Boolean> get() = state

    fun set(placed: Boolean) {
        state.value = placed
    }
}
