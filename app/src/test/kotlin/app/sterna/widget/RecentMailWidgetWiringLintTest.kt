package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest] next door, and it exists for the same reason: there is no
 * Robolectric in this repository, so `RecentMailWidgetDraw`, `RecentMailWidgetService` and
 * `RecentMailWidgetProvider` cannot be RUN by this suite at all.
 *
 * That gap is not theoretical. An independent review of the widget mutated three single lines in
 * those files — the app lock read replaced by `false`, the cell's notice replaced by the
 * empty-inbox line, this widget's presence pointed at the counter's — and every one of them
 * compiled and left the whole suite green. Each is a defect a reader would meet on their home
 * screen and could not diagnose. The rules below are what closes them.
 *
 * WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line, which is how the first version of another lint in this repo was walked past
 * twice. The price is that reformatting any pinned line reddens this test — loud, and one line to
 * fix.
 */
class RecentMailWidgetWiringLintTest {

    /**
     * The four facts that decide what a row may say, what it wears and which mailbox it came from,
     * must be READ — from the store and the settings, every time the widget is read.
     *
     * The fourth is the sender-initials switch (#144). It is a matter of TASTE and not of
     * privacy, which is exactly why it is easy to leave out: nothing breaks, nothing leaks, and a
     * reader who removed the initials from her message list simply finds them back on her home
     * screen with no switch anywhere that turns them off.
     *
     * Each is one line, each is trivially replaceable by a constant, and none is executed anywhere
     * in this suite: [RecentMailWidgetContentTest] proves the RULES are right by passing the values
     * in itself, which says nothing about whether the shipped code goes and fetches them.
     * `val appLockEnabled = false` is the app lock ceasing to exist for the home screen while
     * `SECURITY.md` goes on promising it, with no pixel anywhere to say so.
     *
     * AND THE ACCOUNT LIST IS READ EXACTLY ONCE, which is why the whole body is pinned rather
     * than the lines of interest. `accountColors = emptyMap()` is a list whose dots never appear,
     * on an install with several mailboxes, and nothing on the cell says why. A SECOND
     * `accountStore.accounts()` is the defect [UnreadWidgetDraw.draw] documents one file over: two
     * reads may answer differently — an account added, removed or recoloured in between — and the
     * cell would then colour its rows from one list while the count it sits beside came from
     * another.
     *
     * The read now also carries the cell's PALETTE, held with the rows rather than beside them —
     * why that pairing exists is [RecentWidgetDrawing]'s own rule and is pinned by
     * [RecentMailWidgetPaletteWiringLintTest]. It appears here because this test pins the body
     * whole, which is what makes a second, separate read of anything visible at all.
     *
     * That palette may be NULL, and this line is deliberately unchanged by that: the decision to
     * post nothing belongs to [WidgetThemeReader.palette], not here. The call site does not test
     * it, does not default it and must not start to — a `?: somePalette` here would put back, in
     * one line, exactly the frozen colours the nullability exists to avoid.
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
                "suspend fun read(app: Application): RecentWidgetDrawing {",
                "val container = app.container",
                "val content = container.settingsRepository.notificationContent.first()",
                "val listMonogram = container.settingsRepository.listMonogram.first()",
                "val appLockEnabled = container.accountStore.appLockEnabled()",
                "val accounts = container.accountStore.accounts()",
                "val inbox = container.mailRepository.recentUnifiedInbox(ROW_LIMIT)",
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
                "val drawing = RecentWidgetDrawing(snapshot, colours)",
                "held = drawing",
                "return drawing",
            ),
            functionBody(DRAW, "suspend fun read(app: Application)"),
        )
    }

    /**
     * The line the cell shows INSTEAD of a list comes from the rule that knows the three states
     * apart, and never from a literal.
     *
     * `RecentMailWidgetContent.notice` is executed and pinned next door; what no test could see is
     * the caller ignoring it. `app.getString(R.string.widget_latest_empty)` here tells someone who
     * has just restored a backup, and everyone offline before a first connection, that their mail
     * is empty — the single defect the three-state shape was built to prevent.
     *
     * The fallback is [app.sterna.R.string.app_name] and not the empty string: this text is the
     * cell's EMPTY VIEW, so it is what the launcher shows whenever the adapter holds no rows —
     * including on a process the launcher has just rebound, before any read has answered. Blank
     * there is the empty rectangle the layout rule forbids, re-created at runtime.
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
     * THE SECOND WIDGET MUST NOT TOUCH THE FIRST ONE'S PRESENCE, and the whole bodies are pinned
     * because the mutation is one identifier long.
     *
     * `UnreadWidgetPresence` is process-global and seeded from the COUNTER's placed cells. Written
     * from here, removing the last latest-messages cell would close the counter's push gate while a
     * counter cell is still on the home screen: its number would quietly stop following the mail,
     * on an install the user never changed. [RecentMailWidgetPresenceTest] proves the two objects
     * are distinct, which is a different claim from this one — it never touches the provider.
     */
    @Test fun `this widget's presence callbacks write ITS OWN state, not the counter's`() {
        assertEquals(
            "RecentMailWidgetProvider.onEnabled/onDisabled must write RecentMailWidgetPresence. " +
                "UnreadWidgetPresence here switches off the COUNTER's push when the last " +
                "latest-messages cell goes, and nothing on either cell would say why.",
            listOf(
                listOf(
                    "override fun onEnabled(context: Context) {",
                    "RecentMailWidgetPresence.set(true)",
                ),
                listOf(
                    "override fun onDisabled(context: Context) {",
                    "RecentMailWidgetPresence.set(false)",
                ),
            ),
            listOf(
                functionBody(PROVIDER, "override fun onEnabled(context: Context)"),
                functionBody(PROVIDER, "override fun onDisabled(context: Context)"),
            ),
        )
    }

    /**
     * TURNING THE LOCK ON MUST REACH THE HOME SCREEN, and so must tightening the content
     * setting.
     *
     * This is the one the review found and the one that matters most. The cell is redrawn when the
     * system asks (placement, boot, app update) and at the tail of a delivery pass — and by nothing
     * else, because `updatePeriodMillis="0"` means there is no timer by design. Without the two
     * calls below, someone who opens Settings and switches the app lock on precisely to hide their
     * mail goes back to a home screen still listing senders and subjects, and it stays that way
     * until the next message ARRIVES: on a quiet mailbox, days. `SECURITY.md` states the lock
     * silences this widget in the present tense and without conditions, so the code has to be
     * present tense too.
     *
     * `forget()` before `refresh()` is half the fix, not tidying: the factory falls back to the
     * last snapshot it managed to read when its own read times out, and that snapshot was computed
     * under the OLD setting. Without dropping it, a slow read right after the tightening redraws
     * the very names the reader has just asked to hide.
     *
     * AND THE COUNTER IS REDRAWN WITH IT, on the lock only. Enlarged, that cell draws a line per
     * account, and an account's label falls back to its username — the reader's own mail address.
     * Its redraws are driven by the count MOVING, and flipping the lock moves no count, so it has
     * exactly the same days-long gap this rule was written for. It is deliberately absent from
     * `setNotificationContent`: that setting governs what a MESSAGE says about itself, sender and
     * subject, and the counter shows neither.
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
     * AND SO MUST TAKING THE SENDER INITIALS OFF — the fourth setter that reaches this cell, and
     * the one that is NOT a theme setting, which is why it is pinned here beside the lock and the
     * content position rather than in [WidgetThemeRedrawWiringLintTest].
     *
     * The damage is the ordinary one of this widget and it is worth spelling out: the reader opens
     * Settings, switches the sender initials off, goes back to her home screen — and every row
     * still wears a badge. `updatePeriodMillis="0"` means there is no timer by design and all three
     * redraw triggers are fed by MAIL, so the cell keeps the initials until a message ARRIVES: on a
     * quiet mailbox, days. Nothing on the cell says why and there is nothing she can do about it.
     *
     * ONE WIDGET AND NOT TWO. `UnreadWidgetDraw.refresh` has no business here: the counter draws
     * a number and no badge at all, so waking it would be a read of the account store and of Room
     * for a cell that cannot possibly have changed. The three theme setters call it because a
     * theme repaints the counter too; this one does not.
     *
     * `forget()` BEFORE `refresh()`, for the reason written at every other setter: the row
     * factory falls back to the last `RecentWidgetDrawing` it read when its own read times out, and
     * that carrier was projected under the OLD switch — a slow read right after the change would
     * redraw the very badges the reader has just removed.
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
     * RECOLOURING AN ACCOUNT MUST REACH THE HOME SCREEN TOO, and by the same two calls.
     *
     * Since this batch every row of the cell carries the dot of the account it came from
     * (`RecentMailWidgetDraw.read` passes `RecentMailWidgetContent.accountColors(accounts)`). The
     * reader opens Settings, gives an account a new colour, goes back to the home screen — and the
     * dots are still the OLD colour. Nothing on the cell says why, and there is nothing she can do
     * about it: `updatePeriodMillis="0"` means no timer by design, so the cell keeps that colour
     * until mail happens to ARRIVE, which on a quiet mailbox is days. The state on screen is a
     * colour the app no longer holds.
     *
     * AND THE ARRIVAL TRIGGER DOES NOT COVER IT — this is why the two lines are needed here and
     * not somewhere cheaper. `RecentMailWidgetPush.redraws` is fed by
     * `MailRepository.observeRecentUnifiedInbox`, i.e. `UnifiedInbox.recentRows`, which keys the
     * account list on `map(::scopes).distinctUntilChanged()`: a colour changes no (account, inbox)
     * pair, so nothing is re-subscribed and nothing is emitted. That de-duplication is deliberate
     * and documented where it lives (an OAuth token refreshed or a colour written must not rebuild
     * an observed query), and the second `distinctUntilChanged` in `redraws` would swallow the
     * emission anyway: it compares a `RecentInbox`, which carries rows and `configured` and no
     * colour at all. The colour lives in the account store, on the other side of the read.
     *
     * `forget()` before `refresh()`, as at both settings writes: the row factory falls back to the
     * last snapshot it managed to read when its own read times out, and that snapshot was projected
     * under the OLD colour. Without dropping it first, a slow read right after the change repaints
     * exactly the dots the reader has just replaced. The whole body is pinned for that reason —
     * the two calls in the wrong order are the mutation this rule exists to catch, and a check for
     * their mere presence would be satisfied by it.
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
        val PROVIDER = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetProvider.kt")
        val SETTINGS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        val ACCOUNTS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")

        const val NOTICE =
            "val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } " +
                "?: app.getString(R.string.app_name)"

        const val REDRAW_LAUNCH = "viewModelScope.launch {"
        const val FORGET = "RecentMailWidgetDraw.forget()"
        const val REFRESH = "RecentMailWidgetDraw.refresh(getApplication())"

        /** The counter's own redraw, on the app lock only — it shows no sender and no subject. */
        const val COUNTER_REFRESH = "UnreadWidgetDraw.refresh(getApplication())"
    }
}
