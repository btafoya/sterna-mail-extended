package app.sterna.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ONE way the latest-messages widget is read and drawn — the counterpart of [UnreadWidgetDraw],
 * and deliberately a separate file: the two widgets share a home screen and nothing else.
 *
 * The split of labour with [RecentMailWidgetService] is the one a `ListView` widget forces. The
 * cell's FRAME (its background and the single line it shows when there is no list) is a
 * `RemoteViews` this object builds; the ROWS are served by the launcher pulling on a
 * `RemoteViewsFactory` in this app's process, on its own schedule. Both ends spend the SAME
 * snapshot, held here, so the frame and the rows can never be drawn from two different reads.
 */
internal object RecentMailWidgetDraw {

    /**
     * The cells the user has actually placed — of THIS provider. Empty for the large majority of
     * installs, and every caller checks it before reaching for the container: a widget nobody
     * placed must not open Room.
     */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, RecentMailWidgetProvider::class.java))

    /**
     * The last snapshot that ARRIVED. Never a default, and never a placeholder: null means no read
     * has answered yet, and every caller draws nothing on null rather than an empty list — an empty
     * list is a claim ("no mail"), and the setting may not even have been read at that point.
     */
    @Volatile
    private var held: RecentWidgetSnapshot? = null

    /** What the factory serves between reads. */
    val snapshot: RecentWidgetSnapshot? get() = held

    /**
     * Drop the held snapshot, because what it was computed FROM has changed.
     *
     * The factory falls back to [held] when its own read times out, which is right when the read
     * was merely slow and wrong the moment the notification-content setting is tightened or the
     * app lock is switched on: the fallback was projected under the OLD rule and would redraw the
     * very senders and subjects the reader has just asked to hide. Forgetting first means a slow
     * read after a tightening draws NOTHING, which is the honest answer — a cell one refresh
     * behind, never a cell more talkative than the setting allows.
     */
    fun forget() {
        held = null
    }

    /**
     * Read the setting, the lock and the mail, and project them — the only place that does.
     *
     * ⛔ THE SETTING IS READ FIRST AND WAITED FOR. `notificationContent` is a DataStore flow: it
     * answers asynchronously, and drawing before it does would put a subject on the home screen of
     * a reader who asked for NONE, for as long as that first draw lasts. There is no default taken
     * here, and no draw happens without this function returning.
     *
     * ⚠ Not bounded here: the caller bounds it. The provider has ~10 s of broadcast to spend and
     * the factory is on a binder thread the launcher is waiting on; they hold different budgets and
     * they both use `withTimeoutOrNull`, never `withTimeout`.
     */
    suspend fun read(app: Application): RecentWidgetSnapshot {
        val container = app.container
        val content = container.settingsRepository.notificationContent.first()
        val appLockEnabled = container.accountStore.appLockEnabled()
        val inbox = container.mailRepository.recentUnifiedInbox(ROW_LIMIT)
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = inbox,
            content = content,
            appLockEnabled = appLockEnabled,
            unknownSender = app.getString(R.string.message_unknown_sender),
            noSubject = app.getString(R.string.message_no_subject),
            generic = app.getString(R.string.widget_latest_hidden),
        )
        held = snapshot
        return snapshot
    }

    /**
     * Draw the frame of every cell in [ids] and ask the launcher to pull the rows again.
     *
     * One `RemoteViews` PER cell, unlike the counter's single shared one: the adapter is an intent
     * carrying the cell's own id, and a `RemoteViews` shared between cells would point every list
     * at whichever id was built last.
     */
    fun draw(app: Application, ids: IntArray, snapshot: RecentWidgetSnapshot) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(app)
        val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } ?: app.getString(R.string.app_name)
        ids.forEach { id ->
            val views = RemoteViews(app.packageName, R.layout.widget_recent)
            views.setTextViewText(R.id.widget_recent_notice, notice)
            @Suppress("DEPRECATION") // RemoteCollectionItems is API 31+; minSdk here is 26.
            views.setRemoteAdapter(R.id.widget_recent_list, adapterIntent(app, id))
            // ONE template for every row of this cell — the platform's only way of making the rows
            // of a collection clickable below API 31 (and above it): the launcher fills it in with
            // the per-row extras [rowFillIn] attaches. Without it the rows draw and swallow taps.
            views.setPendingIntentTemplate(R.id.widget_recent_list, rowTapTemplate(app))
            // The notice takes the cell whenever the list has nothing in it — the launcher swaps
            // the two, so there is no second draw to keep in step with the factory's answer.
            views.setEmptyView(R.id.widget_recent_list, R.id.widget_recent_notice)
            manager.updateAppWidget(id, views)
            // ⚠ AFTER updateAppWidget: it is what binds the adapter. Asking the older view to
            // refetch changes nothing.
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)
        }
    }

    /**
     * Read and draw every placed cell — the entry point for a caller that has nothing in hand.
     *
     * This is what keeps the list a list of the LATEST messages. `updatePeriodMillis="0"` means the
     * system asks for a draw at placement, at boot and after an app update, and at no other time;
     * without this call the cell would show whatever had arrived by the last of those, and a
     * message that came in an hour ago would be missing from a widget whose whole purpose is to
     * show it. The one caller is the tail of `FetchAndNotify.run`, the funnel every arrival goes
     * through, which is where the counter is redrawn from too.
     *
     * ⛔ [withTimeoutOrNull] and NOT `withTimeout`: that caller wraps its steps in
     * `runCatching { … }.rethrowIfCancelled()` so a sign-out is never mistaken for a failed step,
     * and `withTimeout` reports a slow read as a [kotlinx.coroutines.CancellationException] — a
     * widget that took too long to draw would then cancel the pass that was delivering the mail.
     * Returning null keeps a slow read exactly as expensive as it should be: a stale cell.
     *
     * With no cell placed it costs ONE binder call: [placedIds] is asked first, so nothing here
     * opens DataStore, the account store or Room for the large majority who placed no widget.
     */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val ids = placedIds(app)
        if (ids.isEmpty()) return
        val snapshot = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { read(app) } ?: return
        draw(app, ids, snapshot)
    }

    /**
     * The intent the launcher binds the row factory with.
     *
     * ⚠ THE DATA URI IS NOT DECORATION. `RemoteViews` compares adapter intents by `filterEquals`,
     * which ignores extras: two cells would otherwise hand in intents that compare equal, the
     * second would reuse the first's factory, and both lists would show the same cell's rows.
     * Encoding the whole intent (extras included) as the data makes them differ by id.
     */
    private fun adapterIntent(context: Context, widgetId: Int): Intent =
        Intent(context, RecentMailWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    /**
     * The ONE `PendingIntent` behind every row of a cell — a TEMPLATE, which is the only shape the
     * platform offers: a collection widget cannot hand the launcher one `PendingIntent` per row, so
     * the list is given this, each row is given a fill-in [Intent] of extras ([rowFillIn]), and the
     * launcher merges the two when a row is touched.
     *
     * ⛔ THE ACTION IS LOAD-BEARING, and it is the same trap [UnreadWidgetDraw.tapIntent] documents
     * for the counter. A `PendingIntent`'s identity is `(requestCode, Intent compared by
     * filterEquals, flags)`, and `filterEquals` IGNORES EXTRAS. Every new-mail notification builds
     * `Intent(context, MainActivity::class.java)` with neither action nor data, and they are told
     * apart by request code alone — `Notifications.childId(accountId, emailId)`, a
     * `"$accountId $emailId".hashCode()` covering the whole `Int` range, against which no interval
     * can be reserved. Without an action of its own this template is filterEquals-identical to all
     * of them, and the first `childId` that lands on [ROW_TAP_REQUEST_CODE] has its extras
     * REWRITTEN by `FLAG_UPDATE_CURRENT`: the reader taps the banner announcing a message and the
     * app opens another one. ⛔ A later reader "simplifying" this to a bare `Intent(context, …)`
     * reintroduces exactly that, silently and for some users only.
     *
     * ⚠ AND IT IS MUTABLE, deliberately. A fill-in intent is by definition the launcher writing
     * into this one, which an immutable `PendingIntent` forbids: rows would open nothing at all.
     * It is the second `FLAG_MUTABLE` in the app, next to the quick-reply action in
     * `Notifications`, where `RemoteInput` demands one for the same kind of reason.
     *
     * Two things bound it, and both are pinned by a source lint:
     *
     *  1. the intent names an EXPLICIT component, so what it starts is this app's own activity and
     *     nothing the caller chooses;
     *  2. it carries an action of its own, so it is not confusable with any other of ours.
     *
     * ⛔ What is NOT a bound, though it reads like one: "[rowFillIn] adds extras and nothing else".
     * That is true of what WE fill in and says nothing about what a holder of the template may.
     * `Intent.fillIn` copies any field the base intent left unset, and this one sets component,
     * action and flags and leaves data, categories and package open. The reason that costs nothing
     * is a different one: `MainActivity` is exported with a LAUNCHER filter, so any app on the
     * phone can already start it with any extras and any data without this `PendingIntent` at all.
     * The template hands out no authority that was not already public — which is the argument, and
     * it should be stated rather than a tighter one that is not true.
     *
     * ⛔ Do not spend `FLAG_MUTABLE` on a third one without the same argument written out.
     *
     * `FLAG_UPDATE_CURRENT` for the ordinary reason: one template is kept and refreshed rather than
     * a new one stacked at every draw.
     */
    private fun rowTapTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_ROW_TAP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            ROW_TAP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * What ONE row adds to the template when it is touched: the identity of its message, and
     * strictly nothing else.
     *
     * These are the three extras a tapped new-mail notification already carries, so a row opens by
     * the route that has been carrying notification taps for releases — with the guards that route
     * already holds (an account that is gone, a folder that is unknown, a message purged from the
     * cache). ⛔ No guard is added here and none is re-stated: a message that cannot be opened meets
     * the app's own generic line, which names no cause (#159). ⚠ Offline, a listed message whose
     * body is not cached is exactly that case, and it is not a defect.
     *
     * ⛔ NO ACTION, NO DATA, NO COMPONENT. A fill-in intent is merged into the template by the
     * launcher; anything beyond extras here is a caller writing into what this app starts, and it
     * is what keeps [rowTapTemplate]'s mutability bounded. The two nullable ids are put only when
     * present — [RecentMailWidgetTap] has already turned blank into absent.
     */
    fun rowFillIn(tap: RecentRowTap): Intent {
        val intent = Intent()
        intent.putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, tap.emailId)
        tap.accountId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, it) }
        tap.mailboxId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, it) }
        return intent
    }

    /**
     * This widget's own intent action, and NOT the counter's ([UnreadWidgetDraw]'s
     * `app.sterna.widget.TAP`): it is what keeps the row template from being filterEquals-equal to
     * a notification's intent, and to the counter's. It is never declared in an `intent-filter` and
     * never matched against one — the intent is explicit, and the action's only job is to differ.
     */
    private const val ACTION_ROW_TAP = "app.sterna.widget.OPEN_RECENT"

    /**
     * A constant, and a belt rather than the mechanism — the action above is the real defence.
     * ⛔ It must not be the counter's `TAP_REQUEST_CODE`: aligned, the two templates would differ by
     * their action alone, and the day either action is touched the two become one `PendingIntent`
     * rewriting the other's extras. ⛔ Do not "make it unique" against the notifications either — no
     * number is, `childId` hashes over every `Int`.
     */
    private const val ROW_TAP_REQUEST_CODE = 1

    /**
     * How many messages the cell may list. The read caps itself at 50 (`RECENT_ROWS_MAX`); this is
     * lower on purpose — the list scrolls, nobody scrolls a home-screen cell far, and every row
     * costs a `RemoteViews` built and sent over binder on every refresh.
     */
    const val ROW_LIMIT = 25

    /**
     * How long [refresh] may wait before leaving the cell as it is. The provider's own budget, for
     * a different reason: this one runs at the end of a delivery pass that holds a service or a
     * worker awake, and a widget must not stretch that wake-up. Nothing retries — the next arrival
     * draws again.
     */
    private const val REFRESH_TIMEOUT_MS = 5_000L
}
