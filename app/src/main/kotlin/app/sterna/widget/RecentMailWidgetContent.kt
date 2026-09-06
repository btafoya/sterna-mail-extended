package app.sterna.widget

import app.sterna.R
import app.sterna.appLocale
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.MailNotificationText
import app.sterna.ui.components.MonogramSlot
import app.sterna.ui.components.initialOf
import app.sterna.ui.components.monogramSlot
import app.sterna.util.MailDates
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * ONE row of the latest-messages widget, as the launcher will draw it: six fields, all of them
 * already decided. [secondary] is null when nothing is drawn on the second line — the widget row
 * rule's own way of saying "there is nothing you may write here".
 *
 * It carries no account id, no mailbox id and no message id. Those exist on [RecentEmailRow], they
 * ORDER the list and they are what a tap opens — and they travel in [RecentRowTap], beside this
 * type and never inside it. Under the app lock a row must name nothing at all, and the cheapest way
 * to hold that is for the drawn shape to be unable to carry a name in the first place: an id on
 * this type would be one `setTextViewText` away from an unlocked home screen.
 *
 * [accountColor] is the ONE exception, and it is one because a colour NAMES NOBODY. It is an
 * ARGB `Int` the reader picked in the account editor, it is derived from no string, and it is
 * carried so that a list which interleaves several accounts by date can say which one a line came
 * from. `SECURITY.md` promises that under the lock a row "names neither sender nor subject", and a
 * dot keeps that promise where an account name would break it — which is why the name is NOT here.
 * null = no dot is drawn.
 *
 * The lint next door looks for literals like `"acct-alpha"` in [toString] and does NOT cover this
 * field: the guard on it is the TYPE — an `Int` cannot hold a name — and not that search. A later
 * reader turning it into a `String` walks straight past both.
 *
 * [monogram] IS THE SECOND EXCEPTION, and it is a narrower one. It is null under the app lock and
 * null at NONE — the two states in which the row names nobody — so nothing is added where nothing
 * may be said. When it is NOT null the row's [primary] line is ALREADY the sender's name, in full:
 * an initial taken from that name reveals nothing the line above it has not revealed.
 *
 * It is null on a THIRD state too, and that one is not about privacy at all: the sender-initials
 * switch (#144) turned off. The reader who took the initials off her message list must not find
 * them on her home screen, where there is no switch to take them off again.
 *
 * And the [RecentRowMonogram.slot] is NOT the address. It is two small indices — one of three
 * families, one of six tones, eighteen values in all — computed from the address and not
 * invertible: eighteen buckets identify nobody, an address identifies exactly one person. The
 * seed must never reach this type, whatever a later reader needs it for; it is read in [row] and
 * left behind there.
 */
internal data class RecentWidgetRow(
    val primary: String,
    val secondary: String?,
    val date: String,
    val unread: Boolean,
    val accountColor: Int?,
    val monogram: RecentRowMonogram?,
)

/**
 * The badge one row wears: the letter drawn inside it, and WHICH of the palette's eighteen colours
 * paints it — never the colour itself, and never the address it was derived from.
 *
 * The split is [MonogramSlot]'s own: the seed is read where the message is read, and the colour is
 * resolved later, against whichever scheme is in force when the launcher asks. A widget row is
 * projected once and drawn many times, possibly after a theme change, so a resolved colour carried
 * here would be the palette of the moment the mail arrived.
 */
internal data class RecentRowMonogram(val initial: String, val slot: MonogramSlot)

/**
 * What the CELL is, before a single row is considered — three states, not two, and the third is
 * the one that is easy to get wrong.
 *
 * `MailRepository.recentUnifiedInbox` answers with `configured`, and its KDoc lists the three ways
 * it comes back false: no account at all, an account blob that failed to decode, and an account
 * whose inbox id is not known yet — the state every restored backup starts in and every offline
 * account stays in. In none of the three is "your inbox is empty" a true sentence, and in none of
 * them is "you have no account, add one" one either.
 */
internal sealed interface RecentWidgetCell {

    /** There are messages; the list is what the cell shows. */
    data object Rows : RecentWidgetCell

    /** Inboxes are known and hold nothing. The ONLY state that may say the mailbox is empty. */
    data object EmptyInbox : RecentWidgetCell

    /**
     * Nothing may be affirmed about mail. Not "no account": see the three causes above. The cell
     * names the app and stops there — which is what the layout already showed before the first
     * draw, so the reader sees no change of claim.
     */
    data object NothingToSay : RecentWidgetCell
}

/**
 * One line of the list, both halves of it: what it DRAWS and what it OPENS.
 *
 * Two fields and not one flat row, deliberately. [RecentWidgetRow] may carry nothing that names
 * anybody, which is what makes a locked home screen structurally silent; [RecentRowTap] is nothing
 * but names. Merged, the guard would become a promise: an identifier sitting on the drawn type is
 * one `setTextViewText` away from the home screen, and the reader who switched the app lock on
 * would be the last to know.
 */
internal data class RecentWidgetItem(val drawn: RecentWidgetRow, val tap: RecentRowTap)

/** Everything one draw needs: the cell's state, and the rows it will list (empty unless [RecentWidgetCell.Rows]). */
internal data class RecentWidgetSnapshot(val cell: RecentWidgetCell, val rows: List<RecentWidgetItem>)

/**
 * The latest-messages widget's whole decision, kept apart from Android so it can be EXECUTED in a
 * JVM test — same shape and same reason as [UnreadWidgetContent] and [UnreadWidgetTap] next door.
 * `RemoteViews`, `RemoteViewsService.RemoteViewsFactory` and `Context` do not run in this suite
 * (there is no Robolectric here), so what the widget draws is decided HERE, with no Android type
 * in sight, and the factory that spends it holds nothing but plumbing.
 *
 * EVERY drawn line goes through [MailNotificationText.widgetRow]. A `row.fromName` written
 * straight into a `RemoteViews` would be the notification-content setting and the app lock no
 * longer governing the home screen — the two things SECURITY.md says govern it — and nothing on
 * screen would say so: the widget looks the same either way to whoever set the setting years ago.
 *
 * And no address book. The list inside the app names a sender by `fromName` falling back to
 * `fromEmail` (`EmailListItem`), and so does this: a widget that resolved a contact would show, on
 * an unlocked home screen, a name the message itself never carried.
 */
internal object RecentMailWidgetContent {

    /**
     * The three states, from what the read answered. [hasRows] and not the rows themselves: the
     * decision is about the SHAPE of the cell and must not be able to look at a subject.
     */
    fun cell(configured: Boolean, hasRows: Boolean): RecentWidgetCell = when {
        // First arm, deliberately: a read that knows no inbox says nothing, whatever else is
        // true. Folding this into "no rows" is the defect — three restored accounts, still
        // syncing, and the home screen tells their owner their mail is empty.
        !configured -> RecentWidgetCell.NothingToSay
        hasRows -> RecentWidgetCell.Rows
        else -> RecentWidgetCell.EmptyInbox
    }

    /**
     * The single line drawn INSTEAD of the list, as a string resource, or null when the list is
     * what the cell shows.
     *
     * [RecentWidgetCell.NothingToSay] resolves to the app's own name — a word that makes no
     * claim about mail — and NEVER to the empty-inbox line. That is the whole difference between
     * the two silent-looking states, and it is pinned by a test rather than by this paragraph.
     */
    fun notice(cell: RecentWidgetCell): Int? = when (cell) {
        RecentWidgetCell.Rows -> null
        RecentWidgetCell.EmptyInbox -> R.string.widget_latest_empty
        RecentWidgetCell.NothingToSay -> R.string.app_name
    }

    /**
     * The dot each account's rows wear, by account id — and an EMPTY map whenever no dot should be
     * drawn at all.
     *
     * Two guards folded into one answer, both of them "draw nothing":
     *
     *  1. ONE ACCOUNT, NO DOT. The bound is [AllInboxesView.existsFor], the same one the counter
     *     consults ([UnreadWidgetContent]), and it is shared for the same reason: the drawer only
     *     offers "All inboxes" from two accounts up, and one account means the SAME dot on every
     *     single row: it tells nothing apart, while looking exactly like a marker that does. It
     *     is not the 16 dp that the guard saves — the view stays INVISIBLE and never GONE, so the
     *     column is spent either way; what it saves the reader is a colour that means nothing.
     *  2. NO COLOUR CHOSEN, NO DOT. `StoredAccount.color` is null on the "auto" position of the
     *     picker, which is what every account starts on. Inventing one here (a hash of the address,
     *     the app's accent) would put on the home screen a colour the account editor never shows,
     *     and the reader would have no way to learn what it means.
     *
     * Accounts the map does not mention draw no dot, not a default one — see [RecentWidgetRow].
     */
    fun accountColors(accounts: List<StoredAccount>): Map<String, Int> =
        if (!AllInboxesView.existsFor(accounts)) emptyMap()
        else accounts.mapNotNull { account -> account.color?.let { account.id to it } }.toMap()

    /**
     * One database row, projected onto what the launcher draws.
     *
     * [unknownSender] and [noSubject] are the app's existing stand-ins, passed in because nothing
     * here resolves resources (same reason [MailNotificationText.resolve] takes its [generic]).
     * They are needed HERE and not further down: `subject`, `fromName` and `fromEmail` are all
     * nullable columns and any of them may also be stored blank, while the widget-row rule takes
     * non-null `String`s. Defaulting is the caller's job, and this is the caller.
     *
     * The date and the unread mark are NOT governed by the setting: neither names anyone. The date
     * is [MailDates.formatListDate], the very stamp the message list inside the app carries, so the
     * two never disagree about what "today" looks like. [zone], [today] and [locale] are parameters
     * for the same reason they are there — the turn of a year and a language's date order are
     * testable, and callers leave them out.
     */
    fun row(
        row: RecentEmailRow,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetRow {
        val drawn = MailNotificationText.widgetRow(
            content = content,
            appLockEnabled = appLockEnabled,
            sender = sender(row, unknownSender),
            subject = row.subject.orBlankTo(noSubject),
            generic = generic,
        )
        return RecentWidgetRow(
            primary = drawn.primary,
            secondary = drawn.secondary,
            date = MailDates.formatListDate(row.receivedAt, zone, today, locale),
            unread = !row.seen,
            // THE BADGE IS DRAWN ONLY WHERE THE ROW ALREADY NAMES ITS SENDER, and that answer
            // comes from the rule above — the only place that has seen the app lock fold the
            // position onto NONE. An initial is a letter of somebody's name; a badge on a locked
            // home screen would be the lock's promise broken in one character.
            //
            // TWO CONDITIONS, AND THEY STAY TWO. `namesSender` is a PROMISE (SECURITY.md, the
            // app lock, the notification-content setting); [listMonogram] is a TASTE, the switch
            // #144 added to the message list for the reader whose correspondents have no avatar
            // and whose column was "a stack of identical circles". Folded into one variable the
            // promise disappears inside the preference, and the day the preference is reworked
            // there is nothing left saying a locked row may not wear a letter.
            //
            // The letter follows the DISPLAYED name and the colour follows the ADDRESS, which is
            // exactly what the list inside the app does (EmailListItem: seed = the address, label =
            // the display name). Seeded with the display name instead, the same person would wear
            // one colour here and another in the list — the complaint this pane answers.
            monogram = if (!listMonogram || !drawn.namesSender) null else RecentRowMonogram(
                initial = initialOf(row.fromName.orEmpty(), sender(row, unknownSender)),
                slot = monogramSlot(seed(row, unknownSender)),
            ),
            // THIS row's account, looked up by THIS row's id. A colour taken from anywhere else —
            // the first entry, a field held on the object — paints the whole list one colour, which
            // reads like a working feature and says nothing.
            accountColor = accountColors[row.accountId],
        )
    }

    /**
     * One database row, projected onto BOTH halves of a line: what it draws, and what a tap on it
     * opens. The same [row] feeds the two, in one place, so the line that opens a message can never
     * be a different line from the one that named it.
     */
    fun item(
        row: RecentEmailRow,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetItem = RecentWidgetItem(
        drawn = row(
            row, content, appLockEnabled, listMonogram, unknownSender, noSubject, generic, accountColors,
            zone, today, locale,
        ),
        tap = RecentMailWidgetTap.of(row),
    )

    /** The whole read, projected: the cell's state and the rows to list. */
    fun snapshot(
        inbox: RecentInbox,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetSnapshot {
        // Not named `cell`: a local of that name sitting next to the function of that name is a
        // reader's trap, and the compiler's opinion on it is not worth learning.
        val shape = cell(inbox.configured, inbox.rows.isNotEmpty())
        val rows = if (shape == RecentWidgetCell.Rows) {
            inbox.rows.map {
                item(
                    it, content, appLockEnabled, listMonogram, unknownSender, noSubject, generic, accountColors,
                    zone, today, locale,
                )
            }
        } else {
            emptyList()
        }
        return RecentWidgetSnapshot(shape, rows)
    }

    /**
     * The name a row wears: `fromName`, else `fromEmail`, else the app's stand-in — the same three
     * steps the message list takes (`EmailListItem`), so the home screen and the list inside the
     * app never call the same message two different things.
     *
     * BLANK counts as absent at every step. A server that stored an empty display name is common
     * enough, and a row whose first line is one space is a row that names nothing while looking
     * like a bug.
     */
    private fun sender(row: RecentEmailRow, unknownSender: String): String =
        row.fromName?.takeIf { it.isNotBlank() }
            ?: row.fromEmail?.takeIf { it.isNotBlank() }
            ?: unknownSender

    /**
     * What a badge's COLOUR is derived from: the address, else the name shown, else the stand-in —
     * the message list's own order (`EmailListItem`: `seed = (recipient ?: from)?.email ?:
     * senderName`), and it is that order and not this row's that matters.
     *
     * NOT [sender]. The two differ by which of the name and the address comes first, and that
     * difference is the whole point: an address is stable while a display name is whatever the last
     * server wrote, so seeding on the name repaints a correspondent the reader had learnt to
     * recognise — and paints them a different colour here than in the list, which is the report
     * this pane answers.
     *
     * BLANK counts as absent at every step, for [sender]'s reason: a stored empty string would
     * otherwise become a seed of its own, shared by every message that has one.
     */
    private fun seed(row: RecentEmailRow, unknownSender: String): String =
        row.fromEmail?.takeIf { it.isNotBlank() }
            ?: row.fromName?.takeIf { it.isNotBlank() }
            ?: unknownSender

    private fun String?.orBlankTo(fallback: String): String =
        this?.takeIf { it.isNotBlank() } ?: fallback
}
