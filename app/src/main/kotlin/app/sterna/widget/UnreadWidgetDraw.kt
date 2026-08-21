package app.sterna.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
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
 * ⛔ The numbers are never decided here either. Every path below asks
 * `MailRepository.observeUnifiedInboxUnreadByAccount()` or is HANDED what it returned (#112) — one
 * entry per account, whose total is what the small cell and the drawer's "All inboxes (N)" show.
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
     * Draw [unreadByAccount] into [ids]. NOT free: `AccountStore.accounts()` runs
     * `migrateIfNeeded()`, reads the shared preferences and decodes the whole account blob from
     * JSON, and `updateAppWidget` is a binder call the launcher re-inflates on. It is still the
     * cheap end of the work — the counting is the expensive part and it arrives here already
     * done — but it is not something to spend twice for one number, which is why the caller only
     * reaches here on a breakdown that actually CHANGED ([UnreadWidgetPush.redraws]).
     *
     * An empty [ids] draws nothing rather than nothing-in-particular: the provider is handed the
     * cells the system named, the push is handed [placedIds], and both are allowed to be empty.
     *
     * ⭐ ONE `RemoteViews` for every cell, still. What the user dragged their cell to is not read
     * here at all: [android.widget.RemoteViews] built from a map of sizes lets the LAUNCHER pick,
     * per cell and on every resize, so nothing in this file has to know a geometry — and the four
     * `OPTION_APPWIDGET_M**_*` numbers, which describe two ORIENTATIONS rather than one size, are
     * never consulted. That is also why there is no `onAppWidgetOptionsChanged` anywhere.
     */
    suspend fun draw(app: Application, ids: IntArray, unreadByAccount: Map<String, Int>) {
        if (ids.isEmpty()) return
        // The cell's colours, read from the app's own theme settings and not from res/values-night
        // (#117). This is why draw() suspends: three DataStore flows, first()-ed. All three
        // callers were already inside a coroutine — the provider's launch, the app-scoped push's
        // collect, and refresh() below — so nothing gained a thread here.
        //
        // ⚠ It may be NULL, and null is not handled here on purpose: it travels to BOTH renders
        // and is drawn by posting no colour at all. That is the reader saying either "the read
        // timed out" or "the app does not diverge from the system", and in both cases the
        // resources hold the right answer. ⛔ Never a `?:` default here — a default is this file
        // deciding a colour.
        val palette = WidgetThemeReader.palette(app)
        // One read of the account list, spent three times: what the cell is CALLED, what a tap on
        // it asks for, and the lines of the enlarged layout. The first two rules mirror each other
        // arm for arm ([UnreadWidgetTap]), and reading the list twice is how they would come to
        // disagree — a cell labelled "All inboxes" that opens a single folder, or the reverse.
        val store = app.container.accountStore
        val accounts = store.accounts()
        val target = UnreadWidgetTap.of(accounts)
        // The small layout's figure is this breakdown TOTALLED, never a second count: the same
        // arithmetic `MailRepository.observeUnifiedInboxUnread()` does for the drawer.
        val small = tapped(app, renderTotal(app, UnreadWidgetContent.of(accounts, unreadByAccount.values.sum()), palette), target)
        // The app lock is read synchronously, the way RecentMailWidgetDraw reads it: a locked app
        // draws the total at every size, because an account's name can be its owner's address.
        val rows = UnreadWidgetContent.byAccount(accounts, unreadByAccount, store.appLockEnabled())
        val views =
            // ⚠ API 31+ only, and the fallback is deliberate: below it the size-map constructor
            // does not exist, the cell keeps the layout it has always had however large it is
            // drawn, and nothing else changes. The same trade RecentMailWidgetDraw takes for
            // RemoteCollectionItems, for the same reason — minSdk here is 26.
            if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // ⛔ The ventilated entry's height is DERIVED from how many lines there are, never a
                // constant: a constant is a claim about how many accounts the reader has, and at
                // four it cost them one, cut off by the edge in silence ([ventilatedHeightDp]).
                // Below that height the launcher falls back to SMALL by itself — the total.
                // ⚠ The font scale is read HERE, at draw time, because the rest of the size rule
                // must stay runnable in a test and `Configuration` is not.
                val fontScale = app.resources.configuration.fontScale
                val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))
                RemoteViews(mapOf(SMALL to small, ventilated to tapped(app, renderAccounts(app, rows, palette), target)))
            } else {
                small
            }
        val manager = AppWidgetManager.getInstance(app)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Draw [unreadByAccount] into every placed cell — the push's end of [draw]. */
    suspend fun redraw(context: Context, unreadByAccount: Map<String, Int>) {
        val app = context.applicationContext as? Application ?: return
        draw(app, placedIds(app), unreadByAccount)
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
        val unreadByAccount = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            app.container.mailRepository.observeUnifiedInboxUnreadByAccount().first()
        } ?: return
        draw(app, ids, unreadByAccount)
    }

    /**
     * ⛔ THE ONLY PLACE IN THIS FILE THAT ATTACHES A TAP, and a source lint holds it to one line.
     * The whole cell is the target, not one of the labels and not a row: a home-screen cell that
     * only reacts on the digits reads as broken, and both layouts put that id on their ROOT.
     *
     * Both sizes go through here, so the small cell and the enlarged one cannot end up opening
     * different things — or one of them opening nothing.
     */
    private fun tapped(context: Context, views: RemoteViews, target: WidgetTapTarget): RemoteViews =
        views.apply {
            setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, target))
        }

    /**
     * The cell as it has always been: one figure, one label under it.
     *
     * ⛔ It reads NOTHING: [palette] arrives decided, like the count and the tap target, because
     * this function is not suspending and never was — a settings read pushed down here would have
     * to block whatever thread the caller is on.
     *
     * ⭐ [palette] MAY BE NULL, and null posts NO colour line at all — not a default one. The three
     * lines below are `setBackgroundResource(id)` and two `setTextColor(int)`, i.e. values FROZEN
     * into a `RemoteViews` the launcher caches, and the three `widget_surface_*.xml` have no
     * `-night` twin. Skipping them leaves the cell on `@drawable/widget_unread_background` and
     * `@color/widget_unread_*`, which the launcher re-resolves through `values-night` at every
     * inflation — so the cell follows the system's dusk switch by itself, with this process dead.
     * That is exactly what [widgetFollowsResources] buys, and it is the same rule
     * [RecentMailWidgetFactory] states for its rows: no `setTextColor` keeps the layout's colour,
     * stale at worst, never wrong. ⛔ All three go together: post the background without the text
     * colours (or the reverse) and the cell is half this theme and half the other.
     *
     * ⛔ The background is set with `setBackgroundResource`, NEVER `setBackgroundColor`.
     * `View.setBackgroundColor(int)` throws the shape drawable away and replaces it with a flat
     * fill, so the cell comes out SQUARE among rounded neighbours — with every corner rule in this
     * package still green, because none of them can see a RemoteViews. `setBackgroundTintList`
     * would keep the shape but is API 31 and `minSdk` is 26; hence three pre-built drawables.
     */
    private fun renderTotal(
        context: Context,
        state: UnreadWidgetState,
        palette: WidgetPalette?,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_unread).apply {
            if (palette != null) {
                setInt(R.id.widget_unread_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
                setTextColor(R.id.widget_unread_count, palette.accent)
                setTextColor(R.id.widget_unread_label, palette.label)
            }
            setTextViewText(R.id.widget_unread_count, state.count.toString())
            setTextViewText(R.id.widget_unread_label, text(context, state.label))
        }

    /**
     * The enlarged cell: [rows] and nothing else — no total above them, no "All inboxes" heading.
     * The lines already say both.
     *
     * [android.widget.RemoteViews.removeAllViews] FIRST, and ⛔ NOT because this object carries
     * anything over — it is built fresh on every draw, so there is nothing of the last one in it.
     * It is the LAUNCHER's side that carries: the actions are replayed onto a view hierarchy that
     * may already be inflated, and `reapply` on a container that already holds three lines appends
     * three more. An account removed would keep its line, and every redraw would lengthen the
     * cell — which is also why this is one of the two places a stale row could survive at all.
     *
     * ⛔ [palette] IS POSTED ON THE ROWS TOO, and that is not decoration. This layout and its rows
     * name `@color/widget_row_primary` and `@color/widget_unread_count`, which is exactly where
     * the small cell's colours used to come from before #117: a reader who forces Sterna to light
     * on a dark phone would get a dark enlarged cell, and pure black could not reach it at all.
     * Painting one layout of the map and not the other is worse than painting neither — the same
     * cell would change theme as the user drags it larger.
     *
     * ⭐ Null still posts NOTHING, on the container and on every row alike, for the reason
     * [renderTotal] states: the launcher re-resolves `values-night` at every inflation, so the
     * cell follows dusk with this process dead. ⛔ The rows must follow the container's answer —
     * a row painted while the container is left to the resources is a line of this theme's text
     * on the other theme's surface.
     */
    private fun renderAccounts(
        context: Context,
        rows: List<UnreadAccountRow>,
        palette: WidgetPalette?,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_unread_accounts).apply {
            if (palette != null) {
                setInt(R.id.widget_unread_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
            }
            removeAllViews(R.id.widget_unread_accounts)
            rows.forEach { row ->
                val line = RemoteViews(context.packageName, R.layout.widget_unread_account_row)
                line.setTextViewText(R.id.widget_unread_account_name, row.name)
                line.setTextViewText(R.id.widget_unread_account_count, row.count.toString())
                if (palette != null) {
                    line.setTextColor(R.id.widget_unread_account_name, palette.rowPrimary)
                    line.setTextColor(R.id.widget_unread_account_count, palette.accent)
                }
                addView(R.id.widget_unread_accounts, line)
            }
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
     * The smaller of the two sizes offered to the launcher, in dp, and the launcher alone decides
     * which a given cell gets — the point of the map form.
     *
     * It is the declared minimum of `appwidget_info_unread.xml` (110x40), so it is always a
     * candidate and a cell can never end up with nothing to draw. ⛔ The other entry is NOT a
     * constant next to it any more: it is [VENTILATED_WIDTH_DP] by [ventilatedHeightDp], i.e. the
     * room this many account lines actually need at this font scale. A fixed 110dp high was a
     * silent promise that nobody has a fourth account, and the fourth one was cut off by the edge.
     */
    private val SMALL = SizeF(110f, 40f)

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
