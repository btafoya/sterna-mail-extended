package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind "Upload sent messages", executed, not read as text.
 */
class SentCopyUploadTest {

    private val sent = "INBOX.Sent"

    private fun folder(id: String, role: String?) = MailboxEntity(
        accountId = "account-1", id = id, name = id, role = role,
        sortOrder = 0, totalEmails = 0, unreadEmails = 0,
    )

    private class Effects {
        var listings = 0
        val created = mutableListOf<String>()
    }

    private fun resolve(
        uploadSentCopy: Boolean,
        cached: String?,
        effects: Effects = Effects(),
        chosen: Map<String, String> = emptyMap(),
        onServer: List<MailboxEntity> = emptyList(),
    ): String? = sentMailboxToUpload(
        uploadSentCopy = uploadSentCopy,
        cached = cached,
        chosen = chosen,
        listed = { effects.listings++; onServer },
        create = { effects.created += it },
    )

    @Test
    fun `setting on hands the send the sent folder it was given`() {
        val effects = Effects()
        assertEquals(
            "with the setting on, the Sent copy must still be uploaded — this is the shipped " +
                "default and every existing account is on it.",
            sent,
            resolve(uploadSentCopy = true, cached = sent, effects = effects),
        )
        assertEquals("the cache answered: no LIST is owed on every send", 0, effects.listings)
        assertEquals(emptyList<String>(), effects.created)
    }

    @Test
    fun `setting off hands the send no folder at all`() {
        assertNull(
            "with the setting off the send must receive NO folder: null is what stops the APPEND, " +
                "and the SMTP submission itself is unaffected. Anything else here means the second " +
                "copy is still written and the setting changes nothing.",
            resolve(uploadSentCopy = false, cached = sent),
        )
    }

    @Test
    fun `no sent folder cached asks the server, setting on`() {
        val effects = Effects()
        assertEquals(
            "no cached folder carries the 'sent' role (a freshly added account, folders not " +
                "synced): the account asked for a copy, so the server's own Sent folder takes it.",
            "Sent Messages",
            resolve(
                uploadSentCopy = true, cached = null, effects = effects,
                onServer = listOf(folder("INBOX", "inbox"), folder("Sent Messages", "sent"), folder("Sent", null)),
            ),
        )
        assertEquals(1, effects.listings)
        assertEquals("a folder the server already has is not created", emptyList<String>(), effects.created)
    }

    @Test
    fun `no sent folder on the server either creates the literal one, setting on`() {
        val effects = Effects()
        assertEquals(
            "Sent",
            resolve(
                uploadSentCopy = true, cached = null, effects = effects,
                chosen = mapOf("archive" to "Sent Items"),
                onServer = listOf(folder("INBOX", "inbox"), folder("Sent Items", "archive")),
            ),
        )
        assertEquals(listOf("Sent"), effects.created)
    }

    @Test
    fun `the literal folder designated for another role refuses, whatever its case`() {
        listOf("Sent", "sent", "SENT").forEach { pinned ->
            val effects = Effects()
            val thrown = runCatching {
                resolve(
                    uploadSentCopy = true, cached = null, effects = effects,
                    chosen = mapOf("archive" to "Sent Items", "junk" to pinned),
                    onServer = listOf(folder("INBOX", "inbox"), folder("Sent Items", "archive"), folder(pinned, "junk")),
                )
            }.exceptionOrNull()
            assertEquals(
                "junk pinned on '$pinned': a copy filed there is a sent message in the spam folder",
                "No Sent folder, and the name Sent is designated for another role",
                thrown?.message,
            )
            assertTrue(thrown is IllegalStateException)
            assertEquals(emptyList<String>(), effects.created)
        }
    }

    @Test
    fun `the server's Sent folder designated as the archive, the bin or the spam folder refuses`() {
        listOf("archive", "trash", "junk").forEach { role ->
            val effects = Effects()
            val thrown = runCatching {
                resolve(
                    uploadSentCopy = true, cached = null, effects = effects,
                    chosen = mapOf(role to "Sent"),
                    onServer = listOf(folder("INBOX", "inbox"), folder("Sent", role)),
                )
            }.exceptionOrNull()
            assertEquals(
                "Sent designated as $role, alone: the listing carries it under that role and no " +
                    "folder answers 'sent'. The literal must be asked as the sent role's, never " +
                    "as $role's own, or the copy is filed into the folder designated as $role",
                "No Sent folder, and the name Sent is designated for another role",
                thrown?.message,
            )
            assertEquals("Sent designated as $role", 1, effects.listings)
            assertEquals("Sent designated as $role", emptyList<String>(), effects.created)
        }
    }

    @Test
    fun `no sent folder known stays null, setting off`() {
        val effects = Effects()
        assertNull(
            "setting off and no folder known must not conjure a folder up.",
            resolve(
                uploadSentCopy = false, cached = null, effects = effects,
                chosen = mapOf("junk" to "Sent"),
                onServer = listOf(folder("Sent Messages", "sent")),
            ),
        )
        assertEquals("setting off owes the server no LIST", 0, effects.listings)
        assertEquals(emptyList<String>(), effects.created)
    }
}
