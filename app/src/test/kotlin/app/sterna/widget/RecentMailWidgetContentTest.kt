package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the latest-messages widget draws, RUN — not read as source, and not recomputed here. Every
 * expectation below is a literal [RecentWidgetRow] or [RecentWidgetCell], so a shipped rule whose
 * arms were swapped, dropped or written afresh disagrees with this file instead of agreeing with it.
 *
 * The stakes are not the same as the counter's. This widget draws SENDERS and SUBJECTS on an
 * unlocked home screen, in front of whoever is holding the phone, and it stays there. Two settings
 * are supposed to govern that — the notification-content position (Codeberg #25) and the app lock —
 * and both govern it only for as long as every drawn line goes through
 * `MailNotificationText.widgetRow`. A `row.fromName` written straight into a `RemoteViews` would
 * look identical to anyone but the reader who had asked for neither.
 */
class RecentMailWidgetContentTest {

    // The caller's stand-ins, deliberately NOT the app's own strings: they are passed in, and this
    // file proves they are what comes back out.
    private val unknownSender = "«nobody named»"
    private val noSubject = "«nothing titled»"
    private val generic = "«hidden»"

    // A fixed instant and a fixed reference day, so the stamp is a literal and not a clock.
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 18)

    private fun mail(
        subject: String? = "Quarterly report",
        fromName: String? = "Marie Dupont",
        fromEmail: String? = "marie@example.test",
        seen: Boolean = false,
        receivedAt: String? = "2026-08-18T09:12:00Z",
    ) = RecentEmailRow(
        id = "msg-77",
        accountId = "acct-alpha",
        mailboxId = "mbox-beta",
        subject = subject,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        sortKey = 1_000L,
        receivedAt = receivedAt,
    )

    private fun drawn(
        row: RecentEmailRow = mail(),
        content: NotificationContent = NotificationContent.SENDER_AND_SUBJECT,
        appLockEnabled: Boolean = false,
    ) = RecentMailWidgetContent.row(
        row = row,
        content = content,
        appLockEnabled = appLockEnabled,
        unknownSender = unknownSender,
        noSubject = noSubject,
        generic = generic,
        zone = utc,
        today = today,
        locale = Locale.UK,
    )

    // ---- the four positions of the notification-content setting ----

    /**
     * ⛔ BODY_PREVIEW draws NO body here. The rule deliberately gives it the same two lines as
     * SENDER_AND_SUBJECT: a home screen has no expanded state, the preview column exists on one
     * protocol and not the other, and no position of this setting ever asked for a message's body
     * to sit on a home screen.
     */
    @Test fun `body preview draws the sender and the subject, and no body`() {
        assertEquals(
            "the widget row rule gives BODY_PREVIEW the sender and the subject — the collapsed " +
                "pair of a notification, never a third line of body.",
            RecentWidgetRow(primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12", unread = true),
            drawn(content = NotificationContent.BODY_PREVIEW),
        )
    }

    @Test fun `sender and subject draws both`() {
        assertEquals(
            RecentWidgetRow(primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12", unread = true),
            drawn(content = NotificationContent.SENDER_AND_SUBJECT),
        )
    }

    @Test fun `sender only replaces the subject with the caller's stand-in`() {
        assertEquals(
            "SENDER_ONLY must put the generic line where the subject was — not an empty line, and " +
                "above all not the subject.",
            RecentWidgetRow(primary = "Marie Dupont", secondary = generic, date = "09:12", unread = true),
            drawn(content = NotificationContent.SENDER_ONLY),
        )
    }

    @Test fun `none names nobody and draws no second line`() {
        assertEquals(
            "NONE is the position where a row identifies no one: the generic line alone, and null " +
                "under it — null and not blank, so the launcher draws nothing rather than a hole.",
            RecentWidgetRow(primary = generic, secondary = null, date = "09:12", unread = true),
            drawn(content = NotificationContent.NONE),
        )
    }

    // ---- the app lock ----

    /**
     * ⛔ The lock folds every position onto NONE. SECURITY.md promises it keeps someone who picks
     * up the unlocked phone from reading the mail, and a home screen is read by exactly that person.
     */
    @Test fun `the app lock silences even the most talkative position`() {
        assertEquals(
            "with the app lock enabled a row must say what NONE says, whatever the setting is.",
            RecentWidgetRow(primary = generic, secondary = null, date = "09:12", unread = true),
            drawn(content = NotificationContent.BODY_PREVIEW, appLockEnabled = true),
        )
    }

    /**
     * ⛔ NOTHING of the message survives the lock — and the sender and the subject are not the only
     * things that name someone. The account id and the mailbox id are carried by the database row
     * (they order the list, and a later pane opens it with them); drawing either would put "work"
     * or "alice@…" on the home screen of a locked phone.
     *
     * The needles are looked for in the WHOLE projected row, not in the two text fields, so a field
     * added later carries no name past this test either.
     */
    @Test fun `under the lock no drawn field carries the sender, the subject, or any identifier`() {
        val row = drawn(content = NotificationContent.BODY_PREVIEW, appLockEnabled = true)
        val needles = listOf(
            "Marie Dupont", "marie@example.test", "Quarterly report",
            "acct-alpha", "mbox-beta", "msg-77",
        )
        assertEquals(
            "these must not appear anywhere in what a locked row draws: $row",
            emptyList<String>(),
            needles.filter { it in row.toString() },
        )
    }

    /**
     * The same, one level up: a whole snapshot, locked, DRAWING nobody's name.
     *
     * ⚠ It is the drawn half that is searched, and that is not a softening. A line has two halves
     * since the tap pane — what it draws and what it opens — and the second is nothing but
     * identifiers, by construction: they travel in an `Intent`'s extras to an app that is entered
     * through its own lock screen, and no pixel of them reaches the home screen. What must stay
     * impossible is an identifier in a field the launcher can DRAW, and that is what this asks.
     */
    @Test fun `under the lock a whole snapshot draws nobody`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(configured = true, rows = listOf(mail(), mail(fromName = "Tomasz Nowak"))),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = true,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        val drawn = snapshot.rows.map { it.drawn }
        val needles = listOf("Marie Dupont", "Tomasz Nowak", "marie@example.test", "Quarterly report", "acct-alpha")
        assertEquals(
            "a locked cell lists dates and unread marks and nothing else: $drawn",
            emptyList<String>(),
            needles.filter { it in drawn.toString() },
        )
    }

    /**
     * ⛔ THE FRONTIER THE TAP PANE HAD TO CROSS WITHOUT BREAKING: a line now CARRIES the three
     * identifiers it opens with, and not one of them may end up in a field that is drawn — at any
     * position of the setting, and above all under the lock, where a row names nothing at all.
     *
     * Every position is walked, not just the loud one: the drawn shape is the same object in all
     * four, so an identifier written into it would surface wherever the reader happens to be.
     * ⭐ And the tap is asserted to hold them, in the same breath: a projection that answered this
     * by carrying nothing would pass the first half and leave a widget whose rows open nothing.
     */
    @Test fun `at every position of the setting, and under the lock, no DRAWN field carries an identifier`() {
        val identifiers = listOf("msg-77", "acct-alpha", "mbox-beta")
        val positions = NotificationContent.entries
        val drawnFindings = positions.associateWith { content ->
            listOf(true, false).flatMap { locked ->
                val item = RecentMailWidgetContent.item(
                    row = mail(),
                    content = content,
                    appLockEnabled = locked,
                    unknownSender = unknownSender,
                    noSubject = noSubject,
                    generic = generic,
                    zone = utc,
                    today = today,
                    locale = Locale.UK,
                )
                identifiers.filter { it in item.drawn.toString() }
            }
        }
        assertEquals(
            "the message id, the account id and the folder id travel in an Intent's extras and " +
                "must reach no drawn field. One of them in RecentWidgetRow is one setTextViewText " +
                "away from an unlocked home screen naming a mailbox.",
            positions.associateWith { emptyList<String>() },
            drawnFindings,
        )
        assertEquals(
            "⭐ and they must still be carried: a line that draws nothing AND opens nothing would " +
                "satisfy the rule above by doing the widget's job badly.",
            positions.associateWith { RecentRowTap("msg-77", "acct-alpha", "mbox-beta") },
            positions.associateWith { content ->
                RecentMailWidgetContent.item(
                    row = mail(),
                    content = content,
                    appLockEnabled = true,
                    unknownSender = unknownSender,
                    noSubject = noSubject,
                    generic = generic,
                    zone = utc,
                    today = today,
                    locale = Locale.UK,
                ).tap
            },
        )
    }

    // ---- the columns are nullable, and may be blank ----

    @Test fun `a message with no display name is drawn by its address`() {
        assertEquals(
            "fromName is a nullable column; the list inside the app falls back to the address and " +
                "so does the widget.",
            "marie@example.test",
            drawn(row = mail(fromName = null)).primary,
        )
    }

    @Test fun `a display name stored blank counts as absent`() {
        assertEquals(
            "a server that stored an empty display name would otherwise draw a row whose first " +
                "line is one space.",
            "marie@example.test",
            drawn(row = mail(fromName = "   ")).primary,
        )
    }

    @Test fun `with neither name nor address the caller's stand-in is drawn`() {
        assertEquals(
            unknownSender,
            drawn(row = mail(fromName = null, fromEmail = null)).primary,
        )
    }

    @Test fun `a name and an address both blank count as absent too`() {
        assertEquals(
            unknownSender,
            drawn(row = mail(fromName = " ", fromEmail = "\t")).primary,
        )
    }

    @Test fun `a message with no subject wears the caller's stand-in`() {
        assertEquals(noSubject, drawn(row = mail(subject = null)).secondary)
    }

    @Test fun `a subject stored blank counts as absent`() {
        assertEquals(noSubject, drawn(row = mail(subject = "  ")).secondary)
    }

    // ---- the date and the unread mark, which the setting does not govern ----

    @Test fun `a message from an earlier day of this year is stamped with its day and month`() {
        assertEquals(
            "the widget dates a row with MailDates.formatListDate — the list's own stamp, so the " +
                "home screen and the app never disagree about what today looks like.",
            "4 Jul",
            drawn(row = mail(receivedAt = "2026-07-04T09:12:00Z")).date,
        )
    }

    @Test fun `a read message is not marked unread`() {
        assertEquals(false, drawn(row = mail(seen = true)).unread)
    }

    @Test fun `an unreadable timestamp costs the stamp and nothing else`() {
        assertEquals(
            RecentWidgetRow(primary = "Marie Dupont", secondary = "Quarterly report", date = "", unread = true),
            drawn(row = mail(receivedAt = null)),
        )
    }

    // ---- the three states of the cell ----

    @Test fun `known inboxes with messages show the list`() {
        assertEquals(RecentWidgetCell.Rows, RecentMailWidgetContent.cell(configured = true, hasRows = true))
    }

    @Test fun `known inboxes holding nothing are the only empty inbox`() {
        assertEquals(
            "this is the ONE state that may say the mailbox is empty: the scopes were read, the " +
                "query ran, and it answered nothing.",
            RecentWidgetCell.EmptyInbox,
            RecentMailWidgetContent.cell(configured = true, hasRows = false),
        )
    }

    /**
     * ⛔ THE ARM THIS FILE EXISTS FOR. `configured = false` is "no answer", not "no message" and
     * not "no account": no account at all, an account blob that failed to decode, or an account
     * whose inbox is not known yet — the state every restored backup starts in and every offline
     * account stays in. Folding it onto the empty-inbox arm tells someone who has just restored
     * three accounts that their mail is gone.
     */
    @Test fun `a read that knew no inbox affirms nothing`() {
        assertEquals(
            "configured = false must be its own state. EmptyInbox here is the home screen telling " +
                "a reader whose accounts are still syncing that they have no mail.",
            RecentWidgetCell.NothingToSay,
            RecentMailWidgetContent.cell(configured = false, hasRows = false),
        )
    }

    /**
     * And what that state DRAWS is pinned on the claim, not on a wording: the app's own name, which
     * says nothing about anybody's mail — never the empty-inbox line.
     */
    @Test fun `the state that affirms nothing draws the app's name, never the empty line`() {
        assertEquals(
            "the silent state names the app and stops there.",
            R.string.app_name,
            RecentMailWidgetContent.notice(RecentWidgetCell.NothingToSay),
        )
        assertNotEquals(
            "⛔ and it is NOT the empty-inbox line, which is a claim about the reader's mailbox.",
            R.string.widget_latest_empty,
            RecentMailWidgetContent.notice(RecentWidgetCell.NothingToSay),
        )
    }

    @Test fun `the empty inbox draws the empty line, and a list draws none`() {
        assertEquals(R.string.widget_latest_empty, RecentMailWidgetContent.notice(RecentWidgetCell.EmptyInbox))
        assertEquals(null, RecentMailWidgetContent.notice(RecentWidgetCell.Rows))
    }

    // ---- the whole read, projected ----

    @Test fun `a snapshot lists the rows it was handed, in order`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(
                configured = true,
                rows = listOf(mail(), mail(subject = "Keys", fromName = "Tomasz Nowak", seen = true)),
            ),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        assertEquals(
            RecentWidgetSnapshot(
                cell = RecentWidgetCell.Rows,
                rows = listOf(
                    RecentWidgetItem(
                        drawn = RecentWidgetRow("Marie Dupont", "Quarterly report", "09:12", unread = true),
                        tap = RecentRowTap("msg-77", "acct-alpha", "mbox-beta"),
                    ),
                    RecentWidgetItem(
                        drawn = RecentWidgetRow("Tomasz Nowak", "Keys", "09:12", unread = false),
                        tap = RecentRowTap("msg-77", "acct-alpha", "mbox-beta"),
                    ),
                ),
            ),
            snapshot,
        )
    }

    @Test fun `a snapshot that knew no inbox lists nothing at all`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(configured = false, rows = listOf(mail())),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        assertEquals(
            "nothing is affirmed and nothing is listed — a row drawn under this state would be a " +
                "row from a read that had no scope to read.",
            RecentWidgetSnapshot(RecentWidgetCell.NothingToSay, emptyList()),
            snapshot,
        )
    }
}
