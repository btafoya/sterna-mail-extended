package app.sterna.core.jmap.model

import app.sterna.core.jmap.JmapClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Mailbox.isSubscribed` (RFC 8621 §2) decoded with the SHIPPING [JmapClient.DefaultJson], not a
 * local `Json {}` — the point is what the real client does with a real server's answer.
 *
 * ⛔ Why the Kotlin default is load-bearing and not a nicety: `DefaultJson` is
 * `ignoreUnknownKeys = true`, which forgives a property the server sends and we do not know. It
 * forgives NOTHING about a property we declare and the server omits — a non-defaulted field throws
 * `MissingFieldException` there. `JmapClient.decodeList` turns that into a `JmapException`, so a
 * server that does not report subscription would not degrade the drawer, it would EMPTY it:
 * `Mailbox/get` fails, the account lists no folder at all and cannot be used.
 */
class MailboxSubscriptionDecodeTest {
    private val json: Json = JmapClient.DefaultJson

    @Test fun aServerThatDoesNotReportSubscriptionStillDecodesAndTheFolderIsSubscribed() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb2","name":"Projekte","sortOrder":6,"totalEmails":4,"unreadEmails":1}""",
        )

        assertEquals("mb2", mailbox.id)
        assertTrue(
            "a JMAP server that omits isSubscribed must leave the folder SUBSCRIBED: the unknown " +
                "never hides a folder, and a throw here empties the whole drawer",
            mailbox.isSubscribed,
        )
    }

    @Test fun aServerThatReportsAnUnsubscribedFolderIsBelieved() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb2","name":"Projekte","isSubscribed":false}""",
        )

        assertEquals(false, mailbox.isSubscribed)
    }

    @Test fun aServerThatReportsASubscribedFolderIsBelieved() {
        val mailbox = json.decodeFromString(
            Mailbox.serializer(),
            """{"id":"mb1","name":"Inbox","role":"inbox","isSubscribed":true}""",
        )

        assertEquals(true, mailbox.isSubscribed)
    }
}
