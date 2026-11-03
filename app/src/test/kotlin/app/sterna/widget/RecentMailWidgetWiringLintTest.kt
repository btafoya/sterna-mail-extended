package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest] next door, and it exists for the same reason: there is no
 * Robolectric in this repository, so `RecentMailWidgetDraw`, `RecentMailWidgetService` and
 * `RecentMailWidgetProvider` cannot be run by this suite at all.
 */
class RecentMailWidgetWiringLintTest {

    /**
     * The four facts that decide what a row may say, what it wears and which mailbox it came from,
     * must be read, from the store and the settings, every time the widget is read.
     */
    @Test fun `the widget read fetches the settings, the lock and the accounts, and hands them to the rule`() {
        assertEquals(
            "RecentMailWidgetDraw.read must read the notification-content setting, the " +
                "sender-initials switch, the app lock and the account list from the app's own " +
                "stores and pass all four into " +
                "RecentMailWidgetContent. A constant in either of the first two silently " +
                "ungoverns the home screen: the reader has set a privacy setting, or switched the " +
                "lock on, and the cell keeps naming senders. A constant map in the third leaves " +
                "an interleaved list with no dot at all, and a SECOND read of the account list " +
                "lets the colours and the rows come from two different moments. ⛔ And " +
                "`listMonogram = true` written here is the initials switch (#144) ungoverning " +
                "the home screen: the reader took the initials off her message list and gets " +
                "them back on her launcher, with no switch anywhere that turns them off.",
            listOf(
                "suspend fun read(app: Application, kind: RecentWidgetKind): RecentWidgetDrawing {",
                "val container = app.container",
                "val content = container.settingsRepository.notificationContent.first()",
                "val listMonogram = container.settingsRepository.listMonogram.first()",
                "val appLockEnabled = container.accountStore.appLockEnabled()",
                "val accounts = container.accountStore.accounts()",
                "val inbox = kind.read(container, accounts)",
                "val snapshot = RecentMailWidgetContent.snapshot(",
                "inbox = inbox,",
                "content = content,",
                "appLockEnabled = appLockEnabled,",
                "listMonogram = listMonogram,",
                "unknownSender = app.getString(R.string.message_unknown_sender),",
                "noSubject = app.getString(R.string.message_no_subject),",
                "generic = app.getString(R.string.widget_latest_hidden),",
                "accountColors = RecentMailWidgetContent.accountColors(accounts),",
                ")",
                "val colours = WidgetThemeReader.colours(app)",
                "val drawing = RecentWidgetDrawing(snapshot, colours, RecentMailWidgetTap.templateExtras(kind, accounts))",
                "held.hold(kind, drawing)",
                "return drawing",
            ),
            functionBody(DRAW, "suspend fun read(app: Application, kind: RecentWidgetKind)"),
        )
    }

    @Test fun `each list reads its own mail, the unread one from the accounts read once`() {
        assertEquals(
            "RecentWidgetKind must read the inboxes' list from recentUnifiedInbox and the unread " +
                "list from recentUnread over everyAccountUnreadScopesNow(flowOf(accounts)), the very " +
                "list read() read: a second store read lets scopes and colours disagree. And the " +
                "flows must be the same two reads, observed, bounded by ROW_LIMIT.",
            listOf(
                "{ container, _ -> container.mailRepository.recentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT) },",
                "{ container -> container.mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT) },",
                "{ container, accounts ->",
                "container.mailRepository.recentUnread(",
                "everyAccountUnreadScopesNow(flowOf(accounts)) { container.mailRepository.observeMailboxes(it) },",
                "RecentMailWidgetDraw.ROW_LIMIT,",
                "{ container ->",
                "container.mailRepository.observeRecentUnread(",
                "everyAccountUnreadScopesFlow(container.accountStore.accountsFlow) { container.mailRepository.observeMailboxes(it) },",
                "RecentMailWidgetDraw.ROW_LIMIT,",
            ),
            kotlinLines(KIND).filter { "container" in it || "ROW_LIMIT" in it },
        )
    }

    @Test fun `the held drawing, the forget and the refresh are each list's own`() {
        assertEquals(
            "RecentMailWidgetDraw must hold its drawings in a HeldDrawings, serve each factory " +
                "its OWN kind's, and forget() / refresh(context) without a kind must reach every " +
                "kind: SettingsViewModel and AccountsViewModel call those two, and one list left " +
                "out keeps the old theme, colour or, under the lock, a sender's name.",
            listOf(
                listOf("private val held = HeldDrawings()"),
                listOf("fun drawing(kind: RecentWidgetKind): RecentWidgetDrawing? = held[kind]"),
                listOf("fun forget(kind: RecentWidgetKind) {", "held.forget(kind)"),
                listOf("fun forget() {", "RecentWidgetKind.entries.forEach { forget(it) }"),
                listOf("suspend fun refresh(context: Context) {", "RecentWidgetKind.entries.forEach { refresh(context, it) }"),
            ),
            listOf(
                kotlinLines(DRAW).filter { it.startsWith("private val held") },
                kotlinLines(DRAW).filter { it.startsWith("fun drawing(") },
                functionBody(DRAW, "fun forget(kind: RecentWidgetKind)"),
                functionBody(DRAW, "fun forget()"),
                functionBody(DRAW, "suspend fun refresh(context: Context)"),
            ),
        )
    }

    @Test fun `a refresh and a placement read and draw the kind they were asked for, bounded and never thrown`() {
        assertEquals(
            "RecentMailWidgetDraw.refresh(context, kind) must ask for THAT kind's cells, read THAT " +
                "kind under withTimeoutOrNull and draw THAT kind. A constant kind on any of the " +
                "three lines draws the inboxes' rows into the unread cell, or reads one list and " +
                "draws the other's ids; withTimeout throws a CancellationException that " +
                "FetchAndNotify and the push collection read as a sign-out or a cancelled pass.",
            listOf(
                "suspend fun refresh(context: Context, kind: RecentWidgetKind) {",
                "val app = context.applicationContext as? Application ?: return",
                "val ids = placedIds(app, kind)",
                "if (ids.isEmpty()) return",
                "val drawing = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { read(app, kind) } ?: return",
                "draw(app, kind, ids, drawing)",
            ),
            functionBody(DRAW, "suspend fun refresh(context: Context, kind: RecentWidgetKind)"),
        )
        assertEquals(
            "RecentListWidgetProvider.onUpdate must read and draw its OWN kind, bounded by " +
                "withTimeoutOrNull(DRAW_TIMEOUT_MS), draw nothing on null, catch everything and " +
                "finish the broadcast on every path. A constant kind makes a freshly placed unread " +
                "cell show the inboxes' list; withTimeout, or a missing finish(), costs the " +
                "process at about 10 s.",
            listOf(
                "override fun onUpdate(",
                "context: Context,",
                "appWidgetManager: AppWidgetManager,",
                "appWidgetIds: IntArray,",
                ") {",
                "val app = context.applicationContext as? Application ?: return",
                "val pending = goAsync()",
                "CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {",
                "try {",
                "val drawing = withTimeoutOrNull(DRAW_TIMEOUT_MS) { RecentMailWidgetDraw.read(app, kind) }",
                "if (drawing != null) RecentMailWidgetDraw.draw(app, kind, appWidgetIds, drawing)",
                "} catch (t: Throwable) {",
                "Log.w(TAG, \"Latest-messages widget not redrawn; the cell keeps what it last showed\", t)",
                "} finally {",
                "pending.finish()",
                "}",
                "}",
            ),
            functionBody(PROVIDER, "override fun onUpdate("),
        )
        assertEquals(
            "and the two delays stay what the system allows a goAsync() receiver and a delivery " +
                "pass: 5 s each, well under the ~10 s after which the process is killed.",
            listOf("const val DRAW_TIMEOUT_MS = 5_000L", "private const val REFRESH_TIMEOUT_MS = 5_000L"),
            kotlinLines(PROVIDER).filter { it.startsWith("const val DRAW_TIMEOUT_MS") } +
                kotlinLines(DRAW).filter { it.startsWith("private const val REFRESH_TIMEOUT_MS") },
        )
    }

    @Test fun `each cell's rows are served by the factory of its own kind`() {
        assertEquals(
            "adapterIntent must write the kind BEFORE taking the data URI (filterEquals ignores " +
                "extras: the URI is what keeps two cells, and two kinds, on two factories), and " +
                "the service must build its factory with the kind the intent names.",
            listOf(
                listOf(
                    "private fun adapterIntent(context: Context, kind: RecentWidgetKind, widgetId: Int): Intent =",
                    "Intent(context, RecentMailWidgetService::class.java).apply {",
                    "putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)",
                    "putExtra(EXTRA_KIND, kind.name)",
                    "data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))",
                ),
                listOf(
                    "override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =",
                    "RecentMailWidgetFactory(applicationContext, RecentWidgetKind.named(intent.getStringExtra(RecentMailWidgetDraw.EXTRA_KIND)))",
                ),
            ),
            listOf(
                functionBody(DRAW, "private fun adapterIntent(").take(5),
                kotlinLines(SERVICE).let { lines ->
                    val at = lines.indexOf("override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =")
                    if (at < 0) emptyList() else lines.subList(at, at + 2)
                },
            ),
        )
        assertEquals(
            "a name reads back as its kind, and an adapter bound before the unread list existed, " +
                "which names none, is the inboxes' list it always was.",
            mapOf("Inboxes" to RecentWidgetKind.Inboxes, "Unread" to RecentWidgetKind.Unread, null to RecentWidgetKind.Inboxes),
            listOf("Inboxes", "Unread", null).associateWith { RecentWidgetKind.named(it) },
        )
    }

    /**
     * The line the cell shows instead of a list comes from the rule that knows the three states
     */
    @Test fun `the cell's notice comes from the rule, and is never blank`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must take its notice from RecentMailWidgetContent.notice " +
                "and fall back to the app's own name. A literal here re-decides the three cell " +
                "states in a place no test can execute, and an empty fallback puts a blank " +
                "rectangle on the home screen whenever the row list is empty.",
            listOf(NOTICE),
            kotlinLines(DRAW).filter { it.startsWith("val notice =") },
        )
    }

    /**
     * The second widget must not touch the first one's presence, and the whole bodies are pinned
     */
    @Test fun `this widget's presence callbacks write ITS OWN state, not the counter's`() {
        assertEquals(
            "RecentMailWidgetProvider.onEnabled/onDisabled must write RecentMailWidgetPresence. " +
                "UnreadWidgetPresence here switches off the COUNTER's push when the last " +
                "latest-messages cell goes, and nothing on either cell would say why.",
            listOf(
                listOf(
                    "override fun onEnabled(context: Context) {",
                    "RecentMailWidgetPresence.set(kind, true)",
                ),
                listOf(
                    "override fun onDisabled(context: Context) {",
                    "RecentMailWidgetPresence.set(kind, false)",
                ),
            ),
            listOf(
                functionBody(PROVIDER, "override fun onEnabled(context: Context)"),
                functionBody(PROVIDER, "override fun onDisabled(context: Context)"),
            ),
        )
        assertEquals(
            "and each provider class is bound to its own kind, the inboxes' list keeping its " +
                "class name: Android knows a placed cell by its ComponentName.",
            listOf(
                "class RecentMailWidgetProvider : RecentListWidgetProvider(RecentWidgetKind.Inboxes)",
                "class UnreadListWidgetProvider : RecentListWidgetProvider(RecentWidgetKind.Unread)",
            ),
            kotlinLines(PROVIDER).filter { it.startsWith("class ") },
        )
        assertEquals(
            mapOf(RecentWidgetKind.Inboxes to RecentMailWidgetProvider::class.java, RecentWidgetKind.Unread to UnreadListWidgetProvider::class.java),
            RecentWidgetKind.entries.associateWith { it.provider },
        )
    }

    /**
     * Turning the lock on must reach the home screen, and so must tightening the content
     */
    @Test fun `switching the lock on, and tightening the content setting, redraw the cell at once`() {
        assertEquals(
            "SettingsViewModel must drop the held widget snapshot and redraw the latest-messages " +
                "widget when the app lock is switched on and when the notification-content " +
                "setting changes, and redraw the COUNTER on the lock as well. Without it the home " +
                "screen keeps showing senders, subjects and account addresses the reader has just " +
                "hidden, until mail happens to arrive.",
            listOf(
                listOf(
                    "fun setAppLock(value: Boolean) {",
                    "if (value && !canAuthenticate(getApplication())) {",
                    "_appLockUnavailable.value = true",
                    "_appLock.value = false",
                    "return",
                    "}",
                    "_appLockUnavailable.value = false",
                    "appLock.setEnabled(value)",
                    "_appLock.value = value",
                    REDRAW_LAUNCH,
                    FORGET,
                    REFRESH,
                    COUNTER_REFRESH,
                    "}",
                ),
                listOf(
                    "fun setNotificationContent(mode: NotificationContent) {",
                    "viewModelScope.launch {",
                    "settings.setNotificationContent(mode)",
                    FORGET,
                    REFRESH,
                    "}",
                ),
            ),
            listOf(
                functionBody(SETTINGS_VIEW_MODEL, "fun setAppLock(value: Boolean)"),
                functionBody(SETTINGS_VIEW_MODEL, "fun setNotificationContent(mode: NotificationContent)"),
            ),
        )
    }

    /**
     * And so must taking the sender initials off, the fourth setter that reaches this cell, and
     * the one that is not a theme setting, which is why it is pinned here beside the lock and the
     * content position rather than in [WidgetThemeRedrawWiringLintTest].
     */
    @Test fun `switching the sender initials off redraws the cell, and wakes only the cell that draws them`() {
        assertEquals(
            "SettingsViewModel.setListMonogram must write the setting, await it, drop the held " +
                "widget snapshot and redraw the latest-messages widget, in that order. Without " +
                "it the reader takes the sender initials off, returns to her home screen, and " +
                "every row keeps its badge until mail happens to arrive — days on a quiet " +
                "mailbox, with no switch on that screen and no way to force it. ⛔ And no " +
                "UnreadWidgetDraw.refresh: the counter draws no badge, so waking it is a read of " +
                "the account store and of Room for a cell nothing changed.",
            listOf(
                "fun setListMonogram(enabled: Boolean) {",
                REDRAW_LAUNCH,
                "settings.setListMonogram(enabled)",
                FORGET,
                REFRESH,
                "}",
            ),
            functionBody(SETTINGS_VIEW_MODEL, "fun setListMonogram(enabled: Boolean)"),
        )
    }

    /**
     * Recolouring an account must reach the home screen too, and by the same two calls.
     */
    @Test fun `recolouring an account redraws the cell, so no row keeps a colour the app dropped`() {
        assertEquals(
            "AccountsViewModel.setColor must drop the held widget snapshot and redraw the " +
                "latest-messages widget, in that order. Without it the reader recolours an " +
                "account and the rows on her home screen keep the old dot until mail arrives — " +
                "days on a quiet mailbox — with nothing on the cell to explain it and no way to " +
                "force it. The arrival trigger does not cover this: UnifiedInbox.recentRows keys " +
                "on the inbox scopes, which a colour does not change. And with refresh() before " +
                "forget(), a slow read repaints the very colour that was replaced.",
            listOf(
                "fun setColor(id: String, color: Int?) {",
                "store.setColor(id, color)",
                "refresh()",
                REDRAW_LAUNCH,
                FORGET,
                REFRESH,
                "}",
            ),
            functionBody(ACCOUNTS_VIEW_MODEL, "fun setColor(id: String, color: Int?)"),
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val KIND = file("app/src/main/kotlin/app/sterna/widget/RecentWidgetKind.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")
        val PROVIDER = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetProvider.kt")
        val SETTINGS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        val ACCOUNTS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")

        const val NOTICE =
            "val notice = RecentMailWidgetContent.notice(snapshot.cell, kind)?.let { app.getString(it) } " +
                "?: app.getString(R.string.app_name)"

        const val REDRAW_LAUNCH = "viewModelScope.launch {"
        const val FORGET = "RecentMailWidgetDraw.forget()"
        const val REFRESH = "RecentMailWidgetDraw.refresh(getApplication())"

        /** The counter's own redraw, on the app lock only, it shows no sender and no subject. */
        const val COUNTER_REFRESH = "UnreadWidgetDraw.refresh(getApplication())"
    }
}
