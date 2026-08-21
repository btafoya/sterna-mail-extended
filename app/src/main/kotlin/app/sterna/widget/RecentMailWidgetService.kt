package app.sterna.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.sterna.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The rows of the latest-messages widget, served to the launcher.
 *
 * ⛔ A LIST IN A WIDGET NEEDS THIS SERVICE at `minSdk 26`: `RemoteViews.RemoteCollectionItems`, the
 * modern way of shipping items inside the `RemoteViews` itself, is API 31+. Below that the launcher
 * binds a [RemoteViewsService] in this app's process and pulls each row from it.
 *
 * ⛔ AND THE MANIFEST GUARDS IT WITH `android:permission="android.permission.BIND_REMOTEVIEWS"`.
 * That permission is held by the system alone, so only the widget host reaches this factory.
 * Without it any installed app could bind this service and read, on demand, the sender and subject
 * of every message in every inbox — whatever the notification-content setting says, and with no
 * permission of its own to declare. It is the single most important line of this pane.
 */
class RecentMailWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        RecentMailWidgetFactory(applicationContext)
}

/**
 * ⛔ PLUMBING ONLY. Every decision this draws was taken in [RecentMailWidgetContent] and can be run
 * in a JVM test; nothing below chooses a word. A `RemoteViewsFactory` cannot be executed in that
 * suite, so anything it decided would be decided where nothing can check it.
 */
internal class RecentMailWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    /** What [getViewAt] serves, replaced whole by [onDataSetChanged] and never edited in place. */
    @Volatile
    private var rows: List<RecentWidgetItem> = emptyList()

    override fun onCreate() = Unit

    /**
     * The launcher's "refetch" — called on a binder thread, and allowed to block, which is what
     * makes a database read legal here at all.
     *
     * ⛔ [withTimeoutOrNull] and not `withTimeout`: the second reports a slow read as a
     * `CancellationException`, and this runs on a thread the launcher is waiting on. On a read that
     * does not answer, the rows already held are KEPT — the cell goes on showing what it showed,
     * which is honest about being out of date. ⛔ Never an empty list on failure: that is the cell
     * saying the mail is gone.
     *
     * ⛔ Nothing escapes. An exception thrown from a binder callback takes the process with it.
     */
    override fun onDataSetChanged() {
        val app = context.applicationContext as? Application ?: return
        val read = try {
            runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) } }
        } catch (t: Throwable) {
            Log.w(TAG, "Latest-messages widget not refreshed; the cell keeps what it last showed", t)
            null
        }
        // Failing that, whatever the last read left behind — the provider may have filled it a
        // moment ago on a process the launcher has only just bound. Failing THAT, nothing is drawn.
        rows = (read ?: RecentMailWidgetDraw.snapshot ?: return).rows
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    /**
     * One row, already decided. The second line is GONE and not blank when there is nothing to
     * write there: an empty `TextView` still takes its line's height, so a locked home screen would
     * show a column of rows each with a hole under it.
     */
    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recent_row)
        // The launcher can ask for a position from a list we have since replaced; that is a normal
        // race, not a fault, and an out-of-range read here would throw on a binder thread.
        //
        // ⛔ The blank row is written blank, rather than returned untouched. A RemoteViews with no
        // setTextViewText leaves the launcher's recycled view showing the row it last drew there —
        // so a list that shrank between getCount() and here would keep a sender and a subject on
        // screen under a row that no longer exists, and there is no setting under which that is
        // allowed. It carries no fill-in either: nothing to open, nothing offered.
        val item = rows.getOrNull(position)
        if (item == null) {
            views.setTextViewText(R.id.widget_recent_row_primary, "")
            views.setTextViewText(R.id.widget_recent_row_secondary, "")
            views.setTextViewText(R.id.widget_recent_row_date, "")
            views.setViewVisibility(R.id.widget_recent_row_unread, View.INVISIBLE)
            return views
        }
        val row = item.drawn
        views.setTextViewText(R.id.widget_recent_row_primary, row.primary)
        views.setTextViewText(R.id.widget_recent_row_secondary, row.secondary.orEmpty())
        views.setViewVisibility(
            R.id.widget_recent_row_secondary,
            if (row.secondary == null) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(R.id.widget_recent_row_date, row.date)
        // INVISIBLE and not GONE: the dot holds the text's left edge in one column, read or unread.
        views.setViewVisibility(
            R.id.widget_recent_row_unread,
            if (row.unread) View.VISIBLE else View.INVISIBLE,
        )
        // What a tap on THIS row asks the app for, as extras the launcher merges into the list's
        // template (RecentMailWidgetDraw.rowTapTemplate). On the row's ROOT: on one of the two
        // TextViews the cell would only react on the sender's name and read as broken.
        views.setOnClickFillInIntent(R.id.widget_recent_row_root, RecentMailWidgetDraw.rowFillIn(item.tap))
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    private companion object {
        const val TAG = "RecentMailWidget"

        /**
         * How long a refetch may wait for the read. Shorter than the provider's own budget: this
         * one blocks a binder thread the launcher is holding, and a home screen that stops
         * scrolling is worse than a list one refresh behind.
         */
        const val READ_TIMEOUT_MS = 4_000L
    }
}
