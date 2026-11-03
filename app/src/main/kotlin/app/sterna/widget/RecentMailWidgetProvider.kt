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
import java.util.concurrent.ConcurrentHashMap

/**
 * The home-screen list of the latest messages of every inbox, the second widget, and a separate
 */
class RecentMailWidgetProvider : RecentListWidgetProvider(RecentWidgetKind.Inboxes)

class UnreadListWidgetProvider : RecentListWidgetProvider(RecentWidgetKind.Unread)

abstract class RecentListWidgetProvider internal constructor(
    private val kind: RecentWidgetKind,
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while the read below
        // opens DataStore, the account store and Room. goAsync() holds the process until finish(),
        // which every path here reaches: never finishing leaks the broadcast.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // withTimeoutOrNull, never withTimeout, whose CancellationException a caller
                // that reads cancellation as a sign-out would re-throw. Null means: draw nothing.
                val drawing = withTimeoutOrNull(DRAW_TIMEOUT_MS) { RecentMailWidgetDraw.read(app, kind) }
                if (drawing != null) RecentMailWidgetDraw.draw(app, kind, appWidgetIds, drawing)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down, and a widget that could
                // not be drawn must cost no more than a stale cell.
                Log.w(TAG, "Latest-messages widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** The first cell of this kind was placed. Not the other's, see [RecentMailWidgetPresence]. */
    override fun onEnabled(context: Context) {
        RecentMailWidgetPresence.set(kind, true)
    }

    override fun onDisabled(context: Context) {
        RecentMailWidgetPresence.set(kind, false)
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
 * Is a cell of each list placed?, one state per kind: shared, one list's last cell would stop the other's push.
 */
internal object RecentMailWidgetPresence {

    private val states = RecentWidgetKind.entries.associateWith { MutableStateFlow(false) }

    private val seeded: MutableSet<RecentWidgetKind> = ConcurrentHashMap.newKeySet<RecentWidgetKind>()

    fun placed(context: Context, kind: RecentWidgetKind): StateFlow<Boolean> {
        if (seeded.add(kind)) {
            set(kind, anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context, kind)))
        }
        return states.getValue(kind)
    }

    /**
     * The same flow without the seed, what [set] writes into, and the handle a JVM test can hold.
     */
    fun live(kind: RecentWidgetKind): StateFlow<Boolean> = states.getValue(kind)

    fun set(kind: RecentWidgetKind, placed: Boolean) {
        states.getValue(kind).value = placed
    }
}
