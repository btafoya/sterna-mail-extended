package app.sterna.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
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

    /**
     * The colours [getViewAt] paints those rows in — set from the SAME [RecentWidgetDrawing] as
     * [rows], never fetched here, and dropped by [onDestroy] exactly as [rows] is.
     *
     * ⚠ NULL IS A REAL STATE and it is left uncoloured on purpose. [onDataSetChanged] returns early
     * when neither its own read nor the held drawing answers, so the launcher can ask for rows
     * before anything has been read. A row drawn with no `setTextColor` keeps the colour its layout
     * froze in (`@color/widget_row_primary` / `@color/widget_unread_label`, resolved through
     * `values-night`): the widget's behaviour before this change, i.e. stale rather than wrong, and
     * not a regression. ⛔ A hard-coded default palette here would be the opposite — this file
     * deciding a colour, on the one path where nothing is known.
     *
     * ⭐ THAT PARAGRAPH IS NOW THE RULE FOR THE WHOLE PANE, not this file's own arrangement.
     * [WidgetThemeReader.palette] itself returns null, on two paths — a read that timed out, and
     * an app that does not diverge from the system ([widgetFollowsResources]) — and the counter's
     * `render` and the frame's `draw` skip their colour lines on it exactly as [getViewAt] does
     * here. On the second path it is more than stale-rather-than-wrong: it is the identical
     * drawing, and it is what keeps the cell following a system that switches to dark at dusk
     * while nothing in this process is awake to notice.
     */
    @Volatile
    private var palette: WidgetPalette? = null

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
        //
        // ⛔ ROWS AND COLOURS COME OUT OF THE SAME DRAWING, in one expression. Reading the palette
        // on its own line from RecentMailWidgetDraw.drawing would let a read that landed in between
        // hand this factory the colours of one moment and the rows of another.
        val drawing = read ?: RecentMailWidgetDraw.drawing ?: return
        rows = drawing.snapshot.rows
        palette = drawing.palette
    }

    override fun onDestroy() {
        rows = emptyList()
        palette = null
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
            // Nothing has to be said about the WEIGHT here. A bare String replaces a
            // SpannableString whole, so the bold a recycled view was last drawn with goes with the
            // text that carried it — the same guard the unread dot needed a setViewVisibility for.
            // The account dot too, and for the same reason as the blank text above: a recycled
            // view keeps the colour it was last given, so a list that shrank would leave a mailbox
            // marked on a row that no longer exists.
            views.setViewVisibility(R.id.widget_recent_row_account, View.INVISIBLE)
            return views
        }
        val row = item.drawn
        // The app's own theme, from the same read as the rows (#112, #117). ⛔ The sender takes
        // `rowPrimary` (Material onSurface, what the message list draws a sender in) and the two
        // quieter lines take `label`: swapped, the date shouts and the name recedes. ⛔ And the
        // account dot below is NOT painted from here — its colour says which mailbox the message
        // came from, which no theme role may overwrite.
        val colours = palette
        if (colours != null) {
            views.setTextColor(R.id.widget_recent_row_primary, colours.rowPrimary)
            views.setTextColor(R.id.widget_recent_row_secondary, colours.label)
            views.setTextColor(R.id.widget_recent_row_date, colours.label)
        }
        views.setTextViewText(R.id.widget_recent_row_primary, weighted(row.primary, row.unread))
        views.setTextViewText(R.id.widget_recent_row_secondary, weighted(row.secondary.orEmpty(), row.unread))
        views.setViewVisibility(
            R.id.widget_recent_row_secondary,
            if (row.secondary == null) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(R.id.widget_recent_row_date, weighted(row.date, row.unread))
        // Which account this line came from, as a colour and never as a name — the list interleaves
        // several mailboxes by date and nothing else on the row says which is which.
        //
        // ⛔ setColorFilter and NOT setBackgroundColor: the second replaces the drawable with a flat
        // fill and the dot stops being a circle. The two lines are straight, with no `?.let`,
        // because the view is INVISIBLE whenever the colour is null — a leftover filter under it
        // draws nothing.
        views.setInt(R.id.widget_recent_row_account, "setColorFilter", row.accountColor ?: 0)
        views.setViewVisibility(
            R.id.widget_recent_row_account,
            if (row.accountColor == null) View.INVISIBLE else View.VISIBLE,
        )
        // What a tap on THIS row asks the app for, as extras the launcher merges into the list's
        // template (RecentMailWidgetDraw.rowTapTemplate). On the row's ROOT: on one of the two
        // TextViews the cell would only react on the sender's name and read as broken.
        views.setOnClickFillInIntent(R.id.widget_recent_row_root, RecentMailWidgetDraw.rowFillIn(item.tap))
        return views
    }

    /**
     * [text] in bold when the row is unread, plain when it is not — this cell's ONLY unread mark,
     * and the same one the message list inside the app has used for releases (EmailListItem
     * shows unread by weight and deliberately NOT by a status dot).
     *
     * ⛔ ALL THREE FIELDS GO THROUGH IT: sender, subject and date. That is the project's written
     * rule, not a preference — DESIGN.md:58, "Unread is shown by weight, never by a status dot:
     * sender, time and subject go bold" — and the list bolds the three (EmailListItem.kt:289, :311,
     * :319). A cell that weighted only two of them would draw, on an unread message, a bold sender
     * and a bold time above a plain subject: the home screen and the list it opens saying two
     * different things about the same message, which is the very contradiction this row exists to
     * remove.
     *
     * ⚠ Under the SENDER_ONLY position of the notification-content setting the second line is not a
     * subject at all but the generic stand-in ("New message"), and it is bolded like the rest — it
     * occupies THE SUBJECT'S PLACE on the row, and the weight states the message's read state, not
     * what the line happens to say. Leaving it plain there would mark a row half unread.
     *
     * ⚠ `RemoteViews` HAS NO WAY TO SET A TYPEFACE. There is no `setTypeface` on `TextView` that
     * takes one parcelable argument, so `setInt(…, "setTypeface", …)` compiles and does nothing.
     * The weight therefore travels INSIDE the text: `setTextViewText` takes a `CharSequence`, and a
     * `StyleSpan` is a `ParcelableSpan`, so the span survives the binder hop into the launcher's
     * process and is applied there.
     *
     * ⛔ Not two alternating TextViews, one bold and one not, switched by visibility: that is a
     * second view and a second id per field, four views for two lines, and every rule that touches
     * a row would then have to touch both halves and stay consistent between them.
     *
     * ⚠ NOTHING IN THIS SUITE EXECUTES THIS FUNCTION — `RemoteViews` is not on the JVM classpath
     * and there is no Robolectric here. That a row actually LOOKS bold on a home screen is a bench
     * claim; what the tests hold is that both fields go through this call.
     */
    private fun weighted(text: String, unread: Boolean): CharSequence =
        if (!unread) text
        else SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
