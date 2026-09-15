package app.sterna.widget

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The widget reads the `emails` columns straight, and a row a JMAP account cached may hold a
 * subject or a sender name with its bidi marks. Run, not read as source.
 */
class RecentMailWidgetDisplayTextTest {

    private val raw = RecentEmailRow(
        id = "m1",
        accountId = "acc1",
        mailboxId = "inbox",
        subject = "Invoice \u202E\u2066\u200F\u0007fdp.exe",
        fromName = "Support \u202E\u2066\u200F\u0007moc.knab@troppus",
        fromEmail = "support@evil.example",
        seen = false,
        sortKey = 1_000L,
        receivedAt = "2026-09-01T10:00:00Z",
    )

    private fun drawn() = RecentMailWidgetContent.row(
        row = raw,
        content = NotificationContent.SENDER_AND_SUBJECT,
        appLockEnabled = false,
        listMonogram = false,
        unknownSender = "nobody",
        noSubject = "untitled",
        generic = "hidden",
        accountColors = emptyMap(),
        zone = ZoneId.of("UTC"),
        today = LocalDate.of(2026, 9, 1),
        locale = Locale.UK,
    )

    @Test fun `the sender line loses the marks`() {
        assertEquals("primary (sender)", "Support moc.knab@troppus", drawn().primary)
    }

    @Test fun `the subject line loses the marks`() {
        assertEquals("secondary (subject)", "Invoice fdp.exe", drawn().secondary)
    }
}
