package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount

/** What the widget's label IS: a resource the app translates, or a name the server chose. */
internal sealed interface WidgetLabel {

    /** A string resource, drawn as `getString(id, *args)`. */
    data class Translated(val id: Int, val args: List<Int> = emptyList()) : WidgetLabel

    /** Text taken verbatim from stored data (a mailbox name); never translated, never formatted. */
    data class Verbatim(val text: String) : WidgetLabel
}

internal data class UnreadWidgetState(val label: WidgetLabel, val count: Int)

/** ONE line of the ENLARGED cell. The name is not always one somebody chose — `label()` falls
 *  back to `username`, the mail address — hence [UnreadWidgetContent.byAccount] refusing to build
 *  these under an app lock. */
internal data class UnreadAccountRow(val name: String, val count: Int)

/**
 * The widget's rendering decision, kept apart from Android so it can be EXECUTED in a JVM test.
 * The number is never decided here, only what it is called.
 *
 *  1. The app has an "All inboxes" view ([AllInboxesView], the bound [UnreadWidgetTap] reads too) →
 * the drawer's bare `inbox_all_inboxes`. Never the "(N)" form, the figure above being it.
 * 2. One account → never "All inboxes", a grouping the app does not offer; the account's own
 * inbox name instead, falling back like arm 3 when blank. The count is kept: a missing name
 *     is cosmetic, a total replaced by 0 is a lie.
 *  3. No account at all → the app's name, and zero.
 */
internal object UnreadWidgetContent {

    fun of(accounts: List<StoredAccount>, unread: Int): UnreadWidgetState = when {
        // Arm 1 first, it being the one shared with [UnreadWidgetTap]: the cell that SAYS "All
        // inboxes" has to be the cell that OPENS it.
        AllInboxesView.existsFor(accounts) ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), unread)
        accounts.isEmpty() ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), 0)
        else -> {
            // inboxMailboxName() only falls back when there is no ACCOUNT.
            val inboxName = accounts.single().inboxName
            if (inboxName.isBlank()) {
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), unread)
            } else {
                UnreadWidgetState(WidgetLabel.Verbatim(inboxName), unread)
            }
        }
    }

    /**
     * The enlarged cell's lines, or an empty list meaning "draw the total instead". Four guards, each
     * one a thing the user would otherwise read on a home screen: an app lock on ([StoredAccount
     * .label] falling back to the mail address, which a locked app must not write permanently on the
     * home screen — not the content setting, which governs what a MESSAGE says); fewer than two
     * accounts, never naming a grouping the drawer lacks; an account absent from [unreadByAccount],
     * which gets no line and never a `0`; and fewer than two lines, one line being the total.
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
 * How much room ALL of [rows] need, in dp — the height at which the enlarged layout is offered to the
 * launcher, and the reason that size now MOVES.
 *
 * It was the constant 110dp, a claim about how many accounts the reader has: from the fourth on,
 * the last line went off the bottom edge in silence. The launcher picks the largest entry that FITS,
 * so below this height it falls back to the total. Either the cell shows every line it has, or it
 * gives the total. "Every line it has" is NOT "every account": [byAccount] already drops an
 * account with no entry, a gap older than this rule.
 *
 * [fontScale] belongs in the number and is read at DRAW time, which is what makes the rule
 * executable — and it is STALE between draws, nothing listening for a configuration change.
 * Accepted debt; the defect it replaces was permanent past three accounts. Clamped at 1
 * one-sidedly, and [ROW_SLACK_DP] applies the same asymmetry to the measurement.
 */
internal fun ventilatedHeightDp(rows: Int, fontScale: Float): Float =
    (CONTAINER_PADDING_DP + rows * (ROW_PADDING_DP + ROW_SLACK_DP + ROW_TEXT_DP * fontScale.coerceAtLeast(1f)))
        .coerceAtLeast(SMALLEST_VENTILATED_HEIGHT_DP)

/** The width offered with that height, and STRICTLY above the small entry's 110dp — see
 *  [SMALLEST_VENTILATED_HEIGHT_DP]. */
internal const val VENTILATED_WIDTH_DP = 180f

private const val CONTAINER_PADDING_DP = 16f

/** `@layout/widget_unread_account_row`'s own padding. In `dp`, so this half of a row does NOT grow
 *  with the system font — which is why the scale applies to the other half alone. */
private const val ROW_PADDING_DP = 8f

/** The line box of that row's 14sp text at font scale 1. If either XML changes its padding or its
 *  text size this model is wrong and the last line goes back to being cut. */
private const val ROW_TEXT_DP = 20f

/** The few dp per row that keep this a model of a line rather than a bet on one, [ROW_TEXT_DP] being
 * read off one device in one font. Erring the other way is not symmetrical: too tall gives the
 * total, which is TRUE; too short puts the last account under the edge in silence. Small on
 *  purpose, or three lines would pass the 110dp the fixed entry used to offer. */
private const val ROW_SLACK_DP = 2f

/** THE FLOOR, and what keeps the launcher's ordering meaningful: the other entry is the declared
 *  minimum 110x40dp, so anything shorter would be picked for the smallest cell on the home screen.
 *  One dp above 40 rather than at it, how a launcher breaks a tie not being ours to assume. It
 *  cannot bind today, and is held explicitly all the same. */
private const val SMALLEST_VENTILATED_HEIGHT_DP = 41f
