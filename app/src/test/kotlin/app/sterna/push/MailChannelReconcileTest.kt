package app.sterna.push

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs [MailChannels.reconcile] against a fake of the channel list. What the fake
 * cannot say: that `NotificationManager` renames a channel on a second create, and that it leaves
 * a user's importance and sound alone when it does. Both are Android's documented behaviour.
 */
class MailChannelReconcileTest {

    private class FakeShelf(channels: Map<String, String>, private val active: Set<String> = emptySet()) :
        MailChannelShelf {
        /** Channel id to the account label it is named after (or a stand-in for other channels). */
        val channels = channels.toMutableMap()
        val deleted = mutableListOf<String>()

        override fun channelIds(): List<String> = channels.keys.toList()
        override fun anyActiveOn(channelId: String): Boolean = channelId in active
        override fun upsert(accountId: String, accountLabel: String) {
            channels["new_mail:$accountId"] = accountLabel
        }
        override fun delete(channelId: String) {
            deleted += channelId
            channels.remove(channelId)
        }
    }

    private val alex = StoredAccount(id = "a1b2", server = "s", username = "alex@example.test", accountName = "Alex")
    private val shared = StoredAccount(
        id = "c3d4", server = "s", username = "alex@example.test", loginId = "a1b2", accountName = "",
    )

    private fun reconcile(shelf: FakeShelf, accounts: List<StoredAccount>, unreadable: Boolean = false) =
        MailChannels.reconcile(shelf, { accounts }, { unreadable })

    @Test fun `two accounts get two channels, each named after its account`() {
        val shelf = FakeShelf(emptyMap())
        reconcile(shelf, listOf(alex, shared))
        assertEquals(
            mapOf("new_mail:a1b2" to "Alex", "new_mail:c3d4" to "alex@example.test"),
            shelf.channels,
        )
    }

    @Test fun `the channel id is the account id behind a fixed prefix, never the name`() {
        assertEquals("new_mail:a1b2", Notifications.mailChannelId("a1b2"))
    }

    @Test fun `a removed account loses its channel and the other keeps its own`() {
        val shelf = FakeShelf(mapOf("new_mail:a1b2" to "Alex", "new_mail:c3d4" to "Shared"))
        reconcile(shelf, listOf(alex))
        assertEquals(listOf("new_mail:c3d4"), shelf.deleted)
        assertEquals(mapOf("new_mail:a1b2" to "Alex"), shelf.channels)
    }

    @Test fun `a renamed account keeps its channel id under the new name`() {
        val shelf = FakeShelf(mapOf("new_mail:a1b2" to "Alex"))
        reconcile(shelf, listOf(alex.copy(accountName = "Work")))
        assertEquals(emptyList<String>(), shelf.deleted)
        assertEquals(mapOf("new_mail:a1b2" to "Work"), shelf.channels)
    }

    @Test fun `the old shared channel goes when nothing is on screen on it`() {
        val shelf = FakeShelf(mapOf("new_mail" to "-", "new_mail:a1b2" to "Alex"))
        reconcile(shelf, listOf(alex))
        assertEquals(listOf("new_mail"), shelf.deleted)
    }

    @Test fun `the old shared channel stays while a notification is on it`() {
        val shelf = FakeShelf(mapOf("new_mail" to "-", "new_mail:a1b2" to "Alex"), active = setOf("new_mail"))
        reconcile(shelf, listOf(alex))
        assertEquals(emptyList<String>(), shelf.deleted)
        assertEquals(setOf("new_mail", "new_mail:a1b2"), shelf.channels.keys)
    }

    @Test fun `the service and send-failure channels are never touched, even with no account left`() {
        val shelf = FakeShelf(mapOf("push_service" to "-", "send_failed" to "-", "new_mail:a1b2" to "Alex"))
        reconcile(shelf, emptyList())
        assertEquals(listOf("new_mail:a1b2"), shelf.deleted)
        assertEquals(mapOf("push_service" to "-", "send_failed" to "-"), shelf.channels)
    }

    @Test fun `an account list that failed to decode deletes nothing`() {
        val before = mapOf("new_mail" to "-", "new_mail:a1b2" to "Alex", "new_mail:c3d4" to "Shared")
        val shelf = FakeShelf(before)
        reconcile(shelf, emptyList(), unreadable = true)
        assertEquals(emptyList<String>(), shelf.deleted)
        assertEquals(before, shelf.channels)
    }

    @Test fun `the channels are listed before the accounts are read`() {
        // An account signed in between the two reads has its channel created by its first
        // banner; listed after the read, that channel would be taken for an orphan.
        val shelf = FakeShelf(emptyMap())
        MailChannels.reconcile(
            shelf,
            accounts = {
                shelf.channels["new_mail:e5f6"] = "Just added"
                listOf(alex)
            },
            unreadable = { false },
        )
        assertEquals(emptyList<String>(), shelf.deleted)
    }
}
