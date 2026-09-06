package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount

/**
 * What the widget's label IS, without saying how it is drawn. Two shapes, because the two labels
 * come from different places: one is a resource the app translates, the other is a name the server
 * chose and nobody translates.
 */
internal sealed interface WidgetLabel {

    /** A string resource, drawn as `getString(id, *args)`. */
    data class Translated(val id: Int, val args: List<Int> = emptyList()) : WidgetLabel

    /** Text taken verbatim from stored data (a mailbox name); never translated, never formatted. */
    data class Verbatim(val text: String) : WidgetLabel
}

/** Everything the widget draws: one label and one number. */
internal data class UnreadWidgetState(val label: WidgetLabel, val count: Int)

/**
 * ONE line of the ENLARGED cell: an account's name, and the unread figure of its own inbox.
 *
 * The name is [StoredAccount.label] — the same word the drawer writes above the folder list, so the
 * home screen and the app call an account the same thing. It is not always a name somebody chose:
 * `label()` falls back to `username`, which IS the mail address. That is what
 * [UnreadWidgetContent.byAccount] refuses to build under an app lock.
 */
internal data class UnreadAccountRow(val name: String, val count: Int)

/**
 * The widget's rendering decision, kept apart from Android so it can be EXECUTED in a JVM test —
 * same shape and same reason as `BootRestart` behind `BootReceiver`. There is no Robolectric here:
 * a `RemoteViews`, an `AppWidgetProvider` and a `Context` cannot run in this suite, so the only
 * part worth proving is the part that decides, and it lives here with no Android type in sight.
 *
 * The number is never decided here. It always comes from
 * `MailRepository.observeUnifiedInboxUnread()` — the same derivation the drawer's "All inboxes (N)"
 * reads (`UnifiedInbox`), including when a single account is configured, where the scopes are worth
 * exactly that one inbox. A second way of counting is the one thing that branch existed to prevent.
 *
 * What IS decided here is what the number is called, and the rule has three arms:
 *
 *  1. **The app has an "All inboxes" view** ([AllInboxesView], the bound [UnreadWidgetTap] reads
 *     too, so the cell cannot end up saying one thing and opening another) → the drawer's bare
 *     `inbox_all_inboxes`, whatever the count is.
 *     Never the drawer's "(N)" form: in the drawer that row carries no other number, while here
 *     the big figure above the label IS the count — the counted string would draw 3, and "All
 *     inboxes  (3)" right under it, in a cell whose entire content is that one number.
 *     (`inbox_all_inboxes_unread` stays where it belongs, in the drawer.)
 *  2. **One account** → never "All inboxes". The drawer only offers that entry from two accounts
 *     up (see [AllInboxesView]), so naming a grouping that does not exist would be the widget
 *     claiming more than the app has. The label is that account's own inbox name — the same word
 *     the drawer and the top bar already show for it — unless the server left that name BLANK, in
 *     which case it falls back to the app's name like arm 3, because a figure with nothing written
 *     under it names nothing at all. The count is kept: a missing name is a cosmetic gap, a
 *     total quietly replaced by 0 is a lie about the mailbox.
 *  3. **No account at all** → the app's name, and zero. Nothing invented, no new string, and no
 *     number carried over from a caller.
 */
internal object UnreadWidgetContent {

    fun of(accounts: List<StoredAccount>, unread: Int): UnreadWidgetState = when {
        // Arm 1 first, because it is the one shared with [UnreadWidgetTap]: the cell that SAYS
        // "All inboxes" has to be the cell that OPENS it, and [AllInboxesView] is the single bound
        // both consult so one of them cannot drift. The other two arms are this rule's own.
        AllInboxesView.existsFor(accounts) ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), unread)
        accounts.isEmpty() ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), 0)
        else -> {
            // AccountStore.inboxMailboxName() only falls back when there is no ACCOUNT; a name the
            // server left empty travels through it untouched, and lands here.
            val inboxName = accounts.single().inboxName
            if (inboxName.isBlank()) {
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), unread)
            } else {
                UnreadWidgetState(WidgetLabel.Verbatim(inboxName), unread)
            }
        }
    }

    /**
     * The enlarged cell's lines: one per account, in the app's own order, each carrying that
     * account's OWN unread figure — or an empty list, which means "draw the total instead" and is
     * the answer whenever ventilating would say more than it should.
     *
     * The figures are never decided here. They arrive already counted, one entry per account,
     * from `MailRepository.observeUnifiedInboxUnreadByAccount()`, whose total IS the number the
     * small cell and the drawer show. A second way of adding up is the one thing that derivation
     * exists to prevent.
     *
     * Four guards, and each one is a thing the user would otherwise read on a home screen:
     *
     *  1. **App lock on → nothing.** [StoredAccount.label] is `accountName.ifBlank { username }`,
     *     and `username` is the MAIL ADDRESS: an account imported without a name is drawn as
     *     `someone@example.test`. A locked app hides the sender and the subject of every message;
     *     writing the owner's own address permanently on the home screen, where no unlock is ever
     *     asked for, would give away more than the mail it protects. Not the notification-content
     *     setting: that one governs what a MESSAGE says about itself, and an account name is
     *     neither a sender nor a subject.
     *  2. **Fewer than two accounts → nothing.** [AllInboxesView] is the bound under which the app
     *     offers a cross-account view at all, and the cell must never name a grouping the drawer
     *     does not have — the same rule [of] and [UnreadWidgetTap] follow, from the same line.
     *  3. **An account with no line has no line.** An account absent from [unreadByAccount] has no
     *     known inbox to count, so nothing is written for it. Never a `0`: a zero is a claim
     *     ("nothing unread here") the app is in no position to make, and it is the shape of answer
     *     a reader trusts most.
     *  4. **Fewer than two lines → nothing.** Two accounts of which only one has a known inbox
     *     would ventilate into a single line: a name and a figure that are simply the total under
     *     another word, plus the silent disappearance of the other account. Below two lines the
     *     total says the same thing and says it honestly.
     */
    fun byAccount(
        accounts: List<StoredAccount>,
        unreadByAccount: Map<String, Int>,
        appLockEnabled: Boolean,
    ): List<UnreadAccountRow> {
        if (appLockEnabled) return emptyList()
        if (!AllInboxesView.existsFor(accounts)) return emptyList()
        val rows = accounts.mapNotNull { account ->
            unreadByAccount[account.id]?.let { UnreadAccountRow(account.label(), it) }
        }
        return if (rows.size < 2) emptyList() else rows
    }
}

/**
 * How much room ALL of [rows] need, in dp — the height at which the enlarged layout is offered to
 * the launcher, and the reason that size now MOVES.
 *
 * It was the constant 110dp, and a constant here is a claim about how many accounts the reader
 * has. A row comes to 28dp at font scale 1 and the container keeps 16dp for itself, so 110dp holds
 * three lines: from the FOURTH account on, the launcher drew the ventilated layout into a cell with
 * room for three and the last account went off the bottom edge with no ellipsis, no arrow and no
 * scrollbar — while a small cell of the same widget, next to it, went on counting the account the
 * large one was not writing (bench E3bis, 2026-08-21).
 *
 * Asking for the room the lines really need adds no cap, no scrolling and no settings screen. The
 * launcher already picks the largest entry of the map that FITS, so below this height it falls back
 * on its own to the small entry — the total, which is what the cell showed before any of this
 * existed. Either the cell shows every line it has, or it gives the total; never a list with a
 * line cut off the bottom of it.
 *
 * "Every line it has" is NOT "every account", and the difference is not pedantry: [byAccount]
 * already drops an account that has no entry in the breakdown at all — a mailbox not synced yet, a
 * folder list that failed — so a fourth account can be missing from [rows] before this function is
 * ever called. That gap is older than this rule and is not closed here; what is closed is the cell
 * asking for less room than the lines it DID build. Do not write "every account" anywhere a
 * reader will see it.
 *
 * [fontScale] belongs in the number because a row is not one material: its text is `sp` and its
 * padding is `dp`, so a reader at 200 % has 48dp rows and not 28dp ones. It is read at DRAW time
 * (`UnreadWidgetDraw.draw`) rather than baked in here — that is what makes the rule executable.
 *
 * AND IT IS STALE BETWEEN DRAWS. Accepted debt, written rather than fixed. Nothing in this app
 * listens for `ACTION_CONFIGURATION_CHANGED` and `updatePeriodMillis="0"` means no timer either
 * ([app.sterna.widget.widgetFollowsResources] states the same for colours): the only things that
 * redraw the counter are mail arriving and a theme setting being changed. So a reader who raises
 * the system font size gets taller rows immediately — the launcher re-inflates its cached
 * `RemoteViews` — while the height that cache was keyed on is still the old one, and the last line
 * can sit under the edge until the next message lands. That is a TRANSIENT, and it is the trade
 * this rule was chosen for: the defect it replaces was permanent for everyone past three accounts.
 * Do not "fix" it with a configuration receiver without pricing the wake-up it costs.
 *
 * Clamped at 1, one-sidedly, the way [app.sterna.ui.compose.minimumSuggestionRow] is: 20dp of
 * text box is a measurement, not a promise. Wrong upwards costs a cell that falls back to the
 * honest total slightly earlier than it strictly had to; wrong downwards costs a sliced line, which
 * is the defect itself.
 *
 * [ROW_SLACK_DP] IS THE SAME ASYMMETRY APPLIED TO THE MEASUREMENT, and leaving it out was the
 * hole in the first draft of this rule. The old fixed 110dp asked for far more room than two or
 * three lines need, so it absorbed any device whose line ran taller than the bench's — an OEM font,
 * bold-text accessibility, a script with a taller line box. An exact fit absorbs nothing: it would
 * newly slice the last line at TWO accounts on such a device, where the constant it replaces fell
 * back to the total. A few dp per row buys that back, and costs only an earlier fallback.
 *
 * No Android type in it, so it can be RUN — `UnreadVentilatedHeightTest` executes it. Nothing in
 * this module can build a `SizeF`, let alone ask a launcher what it would have picked.
 */
internal fun ventilatedHeightDp(rows: Int, fontScale: Float): Float =
    (CONTAINER_PADDING_DP + rows * (ROW_PADDING_DP + ROW_SLACK_DP + ROW_TEXT_DP * fontScale.coerceAtLeast(1f)))
        .coerceAtLeast(SMALLEST_VENTILATED_HEIGHT_DP)

/**
 * The width offered with that height, unchanged: enough for an account name and its figure side by
 * side on a 360dp screen. It is also STRICTLY above the small entry's 110dp, and that is not
 * decoration — see [SMALLEST_VENTILATED_HEIGHT_DP].
 */
internal const val VENTILATED_WIDTH_DP = 180f

/** `@layout/widget_unread_accounts` keeps 8dp above the lines and 8dp below them. */
private const val CONTAINER_PADDING_DP = 16f

/**
 * `@layout/widget_unread_account_row`'s own 4dp above and 4dp below. In `dp`, so this half of a row
 * does NOT grow with the system font — which is the whole reason the scale is applied to the other
 * half alone.
 */
private const val ROW_PADDING_DP = 8f

/**
 * The line box of that row's 14sp text at font scale 1. With the padding above, a row measures the
 * 28dp the bench read off the enlarged cell; if either XML changes its padding or its text size,
 * this model is wrong and the last line goes back to being cut — `UnreadWidgetDeclarationTest` pins
 * both layouts against these numbers for that reason.
 */
private const val ROW_TEXT_DP = 20f

/**
 * The few dp per row that keep this a model of a line rather than a bet on one.
 *
 * [ROW_TEXT_DP] was read off one bench screen, on one device, in one font. A `TextView`'s line box
 * is the font's own ascent-to-descent, so an OEM system font, the bold-text accessibility setting
 * or a taller script all push it up a little, and every dp of that error is multiplied by the row
 * count. Erring the other way is not symmetrical: too tall means the launcher falls back to the
 * total, which is TRUE; too short means the launcher ventilates into a cell one line too small and
 * the last account goes under the edge in silence — the defect this whole rule exists to end.
 *
 * Small on purpose. It must not push three lines past the 110dp the fixed entry used to offer,
 * or this fix would take the ventilated cell away from the readers who already had it —
 * `UnreadVentilatedHeightTest` holds both ends of that.
 */
private const val ROW_SLACK_DP = 2f

/**
 * THE FLOOR, and it is what keeps the launcher's ordering meaningful.
 *
 * The map's other entry is the widget's declared minimum, 110x40dp, and the launcher picks the
 * largest entry that fits. An entry SHORTER than 40dp would be the one picked for the smallest cell
 * on the home screen: three account lines to write inside 110x40dp. One dp above 40 rather than at
 * it — at exactly 40 the two entries tie on height, and how a launcher breaks that tie is not ours
 * to assume. The width does the same job on the other axis ([VENTILATED_WIDTH_DP], 180 > 110).
 *
 * It cannot bind today: [byAccount] never returns fewer than two rows, so the computed height
 * starts at 76dp. It is held explicitly all the same, because "it cannot happen today" is what a
 * later caller turns into a home screen with the account lines in the smallest cell on it.
 */
private const val SMALLEST_VENTILATED_HEIGHT_DP = 41f
