package app.sterna.push

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, and a last resort: neither poster can be built on the JVM. [NotificationSenderNameTest]
 * runs the name the notification writes; this pins that the poster writes it and that its three callers
 * hand over the container's book (#280).
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`senderNames` and `fromName` are only names), and 3
 * (nothing proves the book holds anything when a notification is posted).
 */
class NotificationSenderNameWiringTest {

    private val poster by lazy { DeclarationSource.functionBody(DeclarationSource.file(NOTIFICATIONS), "fun notifyNewMail(") }
    private val diff by lazy { DeclarationSource.functionBody(DeclarationSource.file(NOTIFIER), "suspend fun notifyDiff(") }
    private val snooze by lazy { DeclarationSource.functionBody(DeclarationSource.file(SNOOZE_WORKER), "override suspend fun doWork(") }
    private val repost by lazy { DeclarationSource.functionBody(DeclarationSource.file(BANNER_WORKER), "private suspend fun repostBanner(") }

    @Test fun `the poster names the sender through the book, for the title and the buttons alike`() {
        assertEquals(
            listOf(
                "senderNames: Map<String, String>,",
                "val fromName = notificationSenderName(email, senderNames)",
                "val sender = fromName ?: generic",
                "hasSender = !fromName.isNullOrBlank(),",
            ),
            poster.filter { Regex("""\b(fromName|senderNames)\b""").containsMatchIn(it) },
        )
        assertEquals(
            "no second reading of the `From:` beside the one that goes through the book",
            emptyList<String>(),
            poster.filter { "email.from" in it },
        )
    }

    @Test fun `the folder diff reads the book once, before the posting loop that may not suspend`() {
        assertEquals(
            listOf(
                "val senderNames = (context.applicationContext as Application).container.senderNames.current()",
                "senderNames,",
            ),
            diff.filter { "senderNames" in it },
        )
        assertTrue(
            "read inside `post`, the wait for the book would suspend between the notifications and their baseline",
            diff.indexOf(diff.first { "senderNames.current()" in it }) <
                diff.indexOf("NotificationPreviews.postGatheredFirst("),
        )
    }

    @Test fun `the snooze wake-up hands over the container's book`() {
        assertEquals(
            listOf("senderNames = container.senderNames.current(),"),
            snooze.filter { "senderNames" in it },
        )
    }

    @Test fun `the banner repost after a failed write hands over the container's book`() {
        assertEquals(
            listOf("senderNames = container.senderNames.current(),"),
            repost.filter { "senderNames" in it },
        )
    }

    private companion object {
        const val NOTIFICATIONS = "app/src/main/kotlin/app/sterna/push/Notifications.kt"
        const val NOTIFIER = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        const val SNOOZE_WORKER = "app/src/main/kotlin/app/sterna/snooze/SnoozeWorker.kt"
        const val BANNER_WORKER = "app/src/main/kotlin/app/sterna/push/BannerWriteWorker.kt"
    }
}
