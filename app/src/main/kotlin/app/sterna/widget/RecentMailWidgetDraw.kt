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
import java.util.concurrent.atomic.AtomicReference

/**
 * One read, and everything the two ends of the cell spend it on: the rows, the palette they are drawn
 */
internal data class RecentWidgetDrawing(val snapshot: RecentWidgetSnapshot, val colours: WidgetColours?, val tapExtras: Map<String, Boolean>)

/** The one way the latest-messages widget is read and drawn. The frame is a `RemoteViews` this
 *  object builds; the rows are served by the launcher pulling on a `RemoteViewsFactory` on its own
 *  schedule. Both ends spend the same read, so they cannot come from two different moments. */
internal object RecentMailWidgetDraw {

    /** The cells the user has actually placed, checked before the container: a widget nobody
     *  placed must not open Room. */
    fun placedIds(context: Context, kind: RecentWidgetKind): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.provider))

    /** The last read that arrived for each kind, colours included. Null means no read has answered
     *  yet, and a caller then draws nothing rather than an empty list, which is a claim ("no mail"). */
    private val held = HeldDrawings()

    /** What the factory of [kind] serves between reads. */
    fun drawing(kind: RecentWidgetKind): RecentWidgetDrawing? = held[kind]

    /** Drop the held snapshot of [kind], because what it was computed from has changed: the factory
     *  falls back to it when its own read times out, and the moment the content setting is tightened
     *  that fallback would redraw the very senders the reader has just asked to hide. */
    fun forget(kind: RecentWidgetKind) {
        held.forget(kind)
    }

    fun forget() {
        RecentWidgetKind.entries.forEach { forget(it) }
    }

    /** Read the setting, the lock, the accounts, the mail and the theme, and project them, the only
     * place that does. The settings are read first and waited for: drawing before these DataStore
     * flows answer would put a subject on the home screen of a reader who asked for none. Not
     *  bounded here, the caller bounds it, and the two callers hold different budgets. */
    suspend fun read(app: Application, kind: RecentWidgetKind): RecentWidgetDrawing {
        val container = app.container
        val content = container.settingsRepository.notificationContent.first()
        // The sender-initials switch (#144), never defaulted here: a fourth copy of
        // LIST_MONOGRAM_DEFAULT would be free to disagree with the repository.
        val listMonogram = container.settingsRepository.listMonogram.first()
        val appLockEnabled = container.accountStore.appLockEnabled()
        // One read of the account list, not one per row, and two reads may answer differently.
        val accounts = container.accountStore.accounts()
        val inbox = kind.read(container, accounts)
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = inbox,
            content = content,
            appLockEnabled = appLockEnabled,
            listMonogram = listMonogram,
            unknownSender = app.getString(R.string.message_unknown_sender),
            noSubject = app.getString(R.string.message_no_subject),
            generic = app.getString(R.string.widget_latest_hidden),
            accountColors = RecentMailWidgetContent.accountColors(accounts),
        )
        // The cell's colours, from the app's own theme settings and no longer from the system's
        // night mode (#117), read here so frame and rows agree. Null is carried as-is. No `?:`.
        val colours = WidgetThemeReader.colours(app)
        val drawing = RecentWidgetDrawing(snapshot, colours, RecentMailWidgetTap.templateExtras(kind, accounts))
        held.hold(kind, drawing)
        return drawing
    }

    /**
     * Draw the frame of every cell in [ids] and ask the launcher to pull the rows again. One
     */
    fun draw(app: Application, kind: RecentWidgetKind, ids: IntArray, drawing: RecentWidgetDrawing) {
        if (ids.isEmpty()) return
        val snapshot = drawing.snapshot
        val palette = drawing.colours?.palette
        val manager = AppWidgetManager.getInstance(app)
        val notice = RecentMailWidgetContent.notice(snapshot.cell, kind)?.let { app.getString(it) } ?: app.getString(R.string.app_name)
        val template = rowTapTemplate(app, kind, drawing.tapExtras)
        ids.forEach { id ->
            val views = RemoteViews(app.packageName, R.layout.widget_recent)
            if (palette != null) {
                views.setInt(R.id.widget_recent_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
                views.setTextColor(R.id.widget_recent_notice, palette.label)
            }
            views.setTextViewText(R.id.widget_recent_notice, notice)
            @Suppress("DEPRECATION") // RemoteCollectionItems is API 31+; minSdk here is 26.
            views.setRemoteAdapter(R.id.widget_recent_list, adapterIntent(app, kind, id))
            // One template for every row, the platform's only way of making the rows of a
            // collection clickable. Without it the rows draw and swallow taps.
            views.setPendingIntentTemplate(R.id.widget_recent_list, template)
            // A cell that opens a view opens it from anywhere, the empty cell included. The list of
            // the inboxes has no such target, its touches are its rows'.
            if (kind.opensAs != null) {
                views.setOnClickPendingIntent(R.id.widget_recent_root, template)
                views.setOnClickPendingIntent(R.id.widget_recent_notice, template)
            }
            // The notice takes the cell whenever the list is empty; the launcher swaps the two.
            views.setEmptyView(R.id.widget_recent_list, R.id.widget_recent_notice)
            manager.updateAppWidget(id, views)
            // After updateAppWidget, which is what binds the adapter.
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)
        }
    }

    /** Read and draw every placed cell. `updatePeriodMillis="0"` means the system asks at placement,
     *  at boot and after an update and at no other time, so without this a message that arrived an
     *  mail. [placedIds] is asked first, so nothing here opens Room for the majority. */
    suspend fun refresh(context: Context, kind: RecentWidgetKind) {
        val app = context.applicationContext as? Application ?: return
        val ids = placedIds(app, kind)
        if (ids.isEmpty()) return
        val drawing = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { read(app, kind) } ?: return
        draw(app, kind, ids, drawing)
    }

    suspend fun refresh(context: Context) {
        RecentWidgetKind.entries.forEach { refresh(context, it) }
    }

    /** The intent the launcher binds the row factory with. The data URI is not decoration:
     *  `RemoteViews` compares adapter intents by `filterEquals`, which ignores extras, so two cells
     *  would otherwise share one factory and show the same rows. The kind is written before the
     *  URI is taken, so the URI differs by kind as well as by cell. */
    private fun adapterIntent(context: Context, kind: RecentWidgetKind, widgetId: Int): Intent =
        Intent(context, RecentMailWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(EXTRA_KIND, kind.name)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    /**
     * The one `PendingIntent` behind every row of a cell, a template, the only shape the platform
     */
    private fun rowTapTemplate(context: Context, kind: RecentWidgetKind, extras: Map<String, Boolean>): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(kind.action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        extras.forEach { (name, value) -> intent.putExtra(name, value) }
        return PendingIntent.getActivity(
            context,
            kind.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** What one row adds to the template when it is touched, [RecentMailWidgetTap.rowExtras] and
     *  nothing else: extras only, no action, data or component. Posted even when empty, a row with
     *  no fill-in swallows the touch. */
    fun rowFillIn(kind: RecentWidgetKind, tap: RecentRowTap): Intent {
        val intent = Intent()
        RecentMailWidgetTap.rowExtras(kind, tap).forEach { (name, value) -> intent.putExtra(name, value) }
        return intent
    }

    /** Which list an adapter intent serves, read back by `RecentMailWidgetService`. */
    const val EXTRA_KIND = "app.sterna.widget.RECENT_KIND"

    /** How many messages the cell may list, lower than the read's own cap: nobody scrolls a
     *  home-screen cell far, and every row costs a `RemoteViews` sent over binder at each refresh. */
    const val ROW_LIMIT = 25

    /** How long [refresh] may wait before leaving the cell as it is: this runs at the end of a
     *  delivery pass holding a service awake. Nothing retries, the next arrival draws again. */
    private const val REFRESH_TIMEOUT_MS = 5_000L
}

/** One slot per kind: a shared slot hands one cell the other's rows when its read times out. */
internal class HeldDrawings {

    private val slots = RecentWidgetKind.entries.associateWith { AtomicReference<RecentWidgetDrawing?>(null) }

    operator fun get(kind: RecentWidgetKind): RecentWidgetDrawing? = slots.getValue(kind).get()

    fun hold(kind: RecentWidgetKind, drawing: RecentWidgetDrawing) {
        slots.getValue(kind).set(drawing)
    }

    fun forget(kind: RecentWidgetKind) {
        slots.getValue(kind).set(null)
    }
}
