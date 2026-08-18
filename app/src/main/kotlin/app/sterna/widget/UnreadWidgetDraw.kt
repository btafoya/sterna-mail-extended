package app.sterna.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ONE way the widget is drawn, and the one place that knows which cells exist.
 *
 * It was inside [UnreadWidgetProvider] while the system was the only thing that ever asked for a
 * draw. There are now two callers — the provider, and the app-scoped push of [UnreadWidgetPush] —
 * and a second `RemoteViews` built somewhere else is the same fault as a second way of counting:
 * the two draws drift, and the cell shows one or the other depending on what woke it.
 *
 * ⛔ The number is never decided here either. Every path below asks
 * `MailRepository.observeUnifiedInboxUnread()` or is HANDED what it returned (#112).
 */
internal object UnreadWidgetDraw {

    /**
     * The cells the user has actually placed. Empty is the answer for the large majority of
     * installs, and every caller checks it BEFORE reaching for the container: a widget nobody
     * placed must not open Room, must not count and must not cost a wake-up.
     */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, UnreadWidgetProvider::class.java))

    /**
     * Draw [unread] into [ids]. Not suspending, and NOT free: `AccountStore.accounts()` runs
     * `migrateIfNeeded()`, reads the shared preferences and decodes the whole account blob from
     * JSON, and `updateAppWidget` is a binder call the launcher re-inflates on. It is still the
     * cheap end of the work — the count is the expensive part and it arrives here already decided —
     * but it is not something to spend twice for one number, which is why the caller only reaches
     * here on a count that actually CHANGED ([UnreadWidgetPush.redraws]).
     *
     * An empty [ids] draws nothing rather than nothing-in-particular: the provider is handed the
     * cells the system named, the push is handed [placedIds], and both are allowed to be empty.
     */
    fun draw(app: Application, ids: IntArray, unread: Int) {
        if (ids.isEmpty()) return
        // One read of the account list, spent twice: what the cell is CALLED and what a tap on it
        // asks for. The two rules mirror each other arm for arm ([UnreadWidgetTap]), and reading
        // the list twice is how they would come to disagree — a cell labelled "All inboxes" that
        // opens a single folder, or the reverse.
        val accounts = app.container.accountStore.accounts()
        val views = render(app, UnreadWidgetContent.of(accounts, unread), UnreadWidgetTap.of(accounts))
        val manager = AppWidgetManager.getInstance(app)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Draw [unread] into every placed cell — the push's end of [draw]. */
    fun redraw(context: Context, unread: Int) {
        val app = context.applicationContext as? Application ?: return
        draw(app, placedIds(app), unread)
    }

    /**
     * Read the count and draw it — for a caller that has no number in hand.
     *
     * ⚠ [withTimeoutOrNull] and NOT `withTimeout`: the only caller is the tail of
     * `FetchAndNotify.run`, which treats its failures with `runCatching { … }.rethrowIfCancelled()`
     * so that a sign-out is never mistaken for a failed step. `withTimeout` reports a slow read as
     * a [kotlinx.coroutines.CancellationException], which that wrapper would re-throw — a widget
     * that took too long to draw would then cancel the pass that was delivering the mail. Returning
     * null keeps a slow read exactly as expensive as it should be: a stale cell.
     */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val ids = placedIds(app)
        if (ids.isEmpty()) return
        val unread = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            app.container.mailRepository.observeUnifiedInboxUnread().first()
        } ?: return
        draw(app, ids, unread)
    }

    private fun render(context: Context, state: UnreadWidgetState, target: WidgetTapTarget): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_unread).apply {
            setTextViewText(R.id.widget_unread_count, state.count.toString())
            setTextViewText(R.id.widget_unread_label, text(context, state.label))
            // The whole cell is the tap target, not one of the two labels: a home-screen cell that
            // only reacts on the digits reads as broken.
            setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, target))
        }

    /**
     * What a tap opens (#112): the unified inbox when the app has one, the app plainly otherwise —
     * [UnreadWidgetTap] decides which, and it decides it with no Android type in hand so the rule
     * can be executed in a test.
     *
     * ⛔ THE ACTION IS LOAD-BEARING, and it is not a matter of taste. A `PendingIntent`'s identity
     * is `(requestCode, Intent compared by filterEquals, flags)`, and `filterEquals` ignores
     * EXTRAS. Every new-mail notification builds `Intent(context, MainActivity::class.java)` with
     * neither action nor data, so those intents are filterEquals-identical to one another and are
     * told apart by request code alone — `Notifications.childId(accountId, emailId)`, which is a
     * `"$accountId $emailId".hashCode()` and therefore covers the entire `Int` range. No range can
     * be reserved against it, and picking a "safe-looking" number is not a defence.
     *
     * Setting an action of our own is: it makes this intent diverge under `filterEquals` whatever
     * number collides. Without it, the first notification whose `childId` lands on
     * [TAP_REQUEST_CODE] has its extras rewritten by `FLAG_UPDATE_CURRENT` — the user taps the
     * banner announcing a message and the app opens the unified inbox instead of that message.
     * ⛔ A future reader "simplifying" this line back to a bare `Intent(context, …)` reintroduces
     * exactly that, silently and only for some users. [TAP_REQUEST_CODE] is a belt on top.
     *
     * `FLAG_UPDATE_CURRENT` is deliberate for the other direction: the target changes when a second
     * account is added or the second-to-last removed, and this replaces the stored extras (an
     * `Intent` with none clears them) so the cell placed months ago opens what it now says.
     */
    private fun tapIntent(context: Context, target: WidgetTapTarget): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_WIDGET_TAP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (target == WidgetTapTarget.UnifiedInbox) {
            intent.putExtra(MainActivity.EXTRA_OPEN_UNIFIED, true)
        }
        return PendingIntent.getActivity(
            context,
            TAP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun text(context: Context, label: WidgetLabel): CharSequence = when (label) {
        is WidgetLabel.Translated -> context.getString(label.id, *label.args.toTypedArray())
        is WidgetLabel.Verbatim -> label.text
    }

    /**
     * How long [refresh] may wait for the count before leaving the cell as it is.
     *
     * The same 5 s as the provider's own draw, for a different reason: this one runs at the end of
     * a delivery pass that holds a service or a worker awake, and a widget must not stretch that
     * wake-up. Nothing retries — the next arrival, or the next thing the user reads with the app
     * open, draws again.
     */
    private const val REFRESH_TIMEOUT_MS = 5_000L

    /**
     * The widget's own intent action — the thing that keeps its `PendingIntent` from being the
     * same one as a notification's (see [tapIntent]). It is never declared in an `intent-filter`
     * and never matched against one: the intent is explicit, so the action's only job is to be
     * different from the nothing the notifications carry.
     */
    private const val ACTION_WIDGET_TAP = "app.sterna.widget.TAP"

    /**
     * A constant, and a belt rather than the mechanism. The disjunction from the notifications is
     * the action above; this number only separates the widget's own intent from itself, which is
     * what makes `FLAG_UPDATE_CURRENT` refresh the one cell target instead of stacking targets.
     * ⛔ Do not "make it unique" — no number is, `childId` hashes over every `Int`.
     */
    private const val TAP_REQUEST_CODE = 0
}

/**
 * Is at least one widget placed? — the condition [UnreadWidgetPush] gates its collection on, held
 * here because answering it needs Android and the rule that reads it must not.
 *
 * Two sources, and they agree by construction:
 *
 *  - the SEED, read once from [UnreadWidgetDraw.placedIds] when the collection starts. It is the
 *    authoritative answer at that instant, and it is the only one a process that starts with a
 *    widget already on the home screen would ever get: `onEnabled` fired long ago, on a process
 *    that has since died;
 *  - the CALLBACKS, for the answer changing under a running process. ⚠ [android.appwidget
 *    .AppWidgetProvider.onEnabled] fires for the FIRST instance only and `onDisabled` for the last
 *    one going — neither is per instance, which is exactly the granularity wanted here: the
 *    collection does not care how many cells there are, only whether there is one.
 *
 * A callback that arrives before the seed is not lost: the widget manager already knows about it,
 * so the seed's own read agrees with what the callback set.
 */
internal object UnreadWidgetPresence {

    private val state = MutableStateFlow(false)

    @Volatile private var seeded = false

    /** The live answer, seeded from the widget manager on the first call. */
    fun placed(context: Context): StateFlow<Boolean> {
        if (!seeded) {
            seeded = true
            set(anyWidgetPlaced(UnreadWidgetDraw.placedIds(context)))
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

/**
 * Does this set of placed cell ids mean the app has a widget? — the seed's whole decision, pulled
 * out where it can be RUN.
 *
 * ⛔ It is one call, and it was one call before; what it was not, was covered. Nothing referenced
 * [UnreadWidgetPresence] in the suite, and the wiring lint next door pins that the seed EXISTS, not
 * what it answers — so inverting this to `isEmpty()` left every test green while breaking both
 * sides of the gate at once: a user who HAS placed a cell keeps the gate shut for the life of the
 * process and their number stops following the mail, and a user who has NOT gets two global Room
 * aggregates subscribed for the life of every process, which is the exact expense this whole
 * mechanism exists to refuse.
 *
 * [IntArray] is not an Android type, so this is executable as it stands — no Robolectric, no fake
 * widget manager. `UnreadWidgetPresenceTest` runs both directions.
 */
internal fun anyWidgetPlaced(ids: IntArray): Boolean = ids.isNotEmpty()
