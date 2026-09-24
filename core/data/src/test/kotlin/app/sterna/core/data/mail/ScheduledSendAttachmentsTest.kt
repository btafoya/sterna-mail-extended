package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A scheduled message with attachments, run through the real [MailSender] methods the composer and
 * the worker call: the copy at scheduling, the hand-over at the hour, and the removal of the row.
 * Only the database and the JMAP server are stand-ins, an in-memory table and a host whose blob
 * store forgets everything uploaded before the hour.
 *
 * What this does NOT cover: the worker itself (it needs a `Context`), whose use of these methods
 * is pinned by `ScheduledSendAttachmentsWiringTest`; and `enqueueSend`'s own copy into the outbox,
 * held by the outbox tests.
 */
class ScheduledSendAttachmentsTest {

    @get:Rule val tmp = TemporaryFolder()

    private class InMemoryScheduledSends : ScheduledSendDao {
        val rows = linkedMapOf<Long, ScheduledSendEntity>()
        var refuseInsert = false
        private var next = 1L

        override suspend fun insert(entity: ScheduledSendEntity): Long {
            if (refuseInsert) error("disk full")
            val id = next++
            rows[id] = entity.copy(id = id)
            return id
        }
        override suspend fun byId(id: Long): ScheduledSendEntity? = rows[id]
        override suspend fun all(): List<ScheduledSendEntity> = rows.values.toList()
        override fun observeAll(): Flow<List<ScheduledSendEntity>> = flowOf(rows.values.toList())
        override suspend fun setFailedAttempts(id: Long, failedAttempts: Int) {
            rows[id]?.let { rows[id] = it.copy(failedAttempts = failedAttempts) }
        }
        override suspend fun delete(id: Long) {
            rows.remove(id)
        }
    }

    /** A JMAP server whose blob ids from before the hour are gone, as RFC 8620 § 6 allows. */
    private class ForgetfulBlobHost : MailSendHost {
        val uploads = mutableListOf<Triple<ByteArray, String?, String?>>()
        var refuseUpload = false

        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            error("no JMAP session is needed to hand a scheduled row over")

        override suspend fun syncMailbox(
            session: JmapSession,
            accountId: String,
            auth: JmapAuth,
            mailboxId: String,
            sizing: FolderSyncSizing,
            localAccountId: String,
        ): MailRepository.MailboxSync = error("nothing is synced here")

        override suspend fun uploadAttachment(
            credentials: AccountCredentials,
            bytes: ByteArray,
            type: String?,
            name: String?,
            disposition: String,
            cid: String?,
        ): EmailBodyPart {
            if (refuseUpload) error("upload refused: 503")
            uploads += Triple(bytes, name, cid)
            return EmailBodyPart(
                blobId = "blob-new-${uploads.size}", type = type, size = bytes.size.toLong(),
                name = name, disposition = disposition, cid = cid,
            )
        }

        override suspend fun destroyDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) =
            error("nothing is destroyed here")

        override suspend fun imapDraftAddressing(
            credentials: AccountCredentials,
            emailId: String,
            budgetMs: Int,
        ): Set<String>? = error("nothing is proven here")
    }

    private val dao = InMemoryScheduledSends()
    private val host = ForgetfulBlobHost()
    private val store = RecordingAccountStorage()

    private val scheduledRoot by lazy { File(tmp.root, "files/scheduled") }
    private val cache by lazy { File(tmp.root, "cache/outgoing").apply { mkdirs() } }

    private fun sender() = MailSender(
        client = JmapClient(),
        imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
        mailboxDao = unusableDao<MailboxDao>(),
        emailDao = unusableDao<EmailDao>(),
        accountStore = store,
        outboxDao = unusableDao<OutboxDao>(),
        scheduledSendDao = dao,
        recentContactDao = unusableDao<RecentContactDao>(),
        outboxFilesDir = File(tmp.root, "files/outbox"),
        host = host,
        outgoingDates = OutgoingDateZones.DEVICE,
        scheduledFilesDir = scheduledRoot,
    )

    private fun credentials(protocol: MailProtocol) = AccountCredentials(
        server = "https://mail.example.test",
        username = "alex@example.test",
        password = "secret",
        id = "account-1",
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    private fun row() = ScheduledSendEntity(
        accountId = "account-1",
        recipients = "bob@example.test",
        subject = "Later",
        textBody = "see attached",
        htmlBody = null,
        fromName = null,
        fromEmail = null,
        inReplyTo = null,
        references = null,
        sendAtMillis = 5_000,
    )

    private fun cacheFile(name: String, bytes: ByteArray): EmailBodyPart {
        val f = File(cache, "123-$name").apply { writeBytes(bytes) }
        return EmailBodyPart(partId = f.absolutePath, type = "application/pdf", size = bytes.size.toLong(), name = name, disposition = "attachment")
    }

    private val noBlob: suspend (EmailBodyPart) -> ByteArray = { error("no blob should be fetched here") }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    // ---- at scheduling

    @Test fun `scheduling copies a file attachment into the scheduled tree and names it on the row`() {
        val bytes = "%PDF-invoice".toByteArray()
        val part = cacheFile("invoice.pdf", bytes)

        val id = runBlocking { sender().insertScheduledSend(row(), listOf(part), noBlob) }

        val stored = OutboxAttachments.decode(dao.rows.getValue(id).attachmentsJson)
        assertEquals(1, stored.size)
        val a = stored.single()
        assertEquals(OutboxAttachments.KIND_IMAP_FILE, a.kind)
        assertEquals("invoice.pdf", a.name)
        assertEquals("attachment", a.disposition)
        val copy = File(a.path!!)
        assertTrue("the copy must live under ${scheduledRoot.path}, it is at ${copy.path}", copy.canonicalPath.startsWith(scheduledRoot.canonicalPath + File.separator))
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test fun `a cache purged before the hour does not take the attachment with it`() {
        val bytes = "photo-bytes".toByteArray()
        val part = cacheFile("photo.jpg", bytes)
        val sender = sender()
        val id = runBlocking { sender.insertScheduledSend(row(), listOf(part), noBlob) }

        cache.deleteRecursively()

        val parts = runBlocking { sender.scheduledSendAttachments(credentials(MailProtocol.IMAP), dao.rows.getValue(id)) }
        assertEquals(1, parts.size)
        assertEquals("photo.jpg", parts.single().name)
        assertArrayEquals(bytes, File(parts.single().partId!!).readBytes())
    }

    @Test fun `an unreadable attachment refuses the schedule, writes no row and leaves no file`() {
        val readable = cacheFile("a.txt", "a".toByteArray())
        val gone = EmailBodyPart(partId = File(cache, "vanished.pdf").absolutePath, name = "vanished.pdf", disposition = "attachment")

        try {
            runBlocking { sender().insertScheduledSend(row(), listOf(readable, gone), noBlob) }
            fail("a message missing one of its attachments was scheduled")
        } catch (_: IllegalStateException) {
        }

        assertEquals(emptyMap<Long, ScheduledSendEntity>(), dao.rows)
        assertEquals(emptyList<File>(), filesUnder(scheduledRoot))
    }

    @Test fun `a refused insert leaves no file behind`() {
        dao.refuseInsert = true
        try {
            runBlocking { sender().insertScheduledSend(row(), listOf(cacheFile("a.txt", "a".toByteArray())), noBlob) }
            fail("the insert failure was swallowed")
        } catch (_: IllegalStateException) {
        }
        assertEquals(emptyList<File>(), filesUnder(scheduledRoot))
    }

    @Test fun `a JMAP blob is fetched back into a file at scheduling, and its blob id is not kept`() {
        val bytes = "server-held".toByteArray()
        val blob = EmailBodyPart(blobId = "blob-old", type = "image/png", size = 11, name = "logo.png", disposition = "inline", cid = "logo@x")
        val fetched = mutableListOf<String?>()

        val id = runBlocking {
            sender().insertScheduledSend(row(), listOf(blob)) { fetched += it.blobId; bytes }
        }

        assertEquals(listOf("blob-old"), fetched)
        val a = OutboxAttachments.decode(dao.rows.getValue(id).attachmentsJson).single()
        assertEquals(OutboxAttachments.KIND_IMAP_FILE, a.kind)
        assertEquals(null, a.blobId)
        assertEquals("logo@x", a.cid)
        assertEquals("inline", a.disposition)
        assertArrayEquals(bytes, File(a.path!!).readBytes())
    }

    @Test fun `a blob that cannot be fetched back refuses the schedule with its own error`() {
        val blob = EmailBodyPart(blobId = "blob-old", name = "report.pdf", disposition = "attachment")
        val first = cacheFile("a.txt", "a".toByteArray())

        try {
            runBlocking { sender().insertScheduledSend(row(), listOf(first, blob)) { error("404") } }
            fail("a message whose blob could not be fetched was scheduled")
        } catch (e: ScheduledAttachmentFetchFailed) {
            assertEquals("report.pdf", e.name)
        }

        assertEquals(emptyMap<Long, ScheduledSendEntity>(), dao.rows)
        assertEquals(emptyList<File>(), filesUnder(scheduledRoot))
    }

    // ---- at the hour

    @Test fun `at the hour a JMAP row is uploaded again and names only the new blobs`() {
        val file = "%PDF".toByteArray()
        val image = "png".toByteArray()
        val sender = sender()
        val id = runBlocking {
            sender.insertScheduledSend(
                row(),
                listOf(
                    cacheFile("doc.pdf", file),
                    EmailBodyPart(blobId = "blob-old", type = "image/png", name = "logo.png", disposition = "inline", cid = "logo@x"),
                ),
            ) { image }
        }

        val parts = runBlocking { sender.scheduledSendAttachments(credentials(MailProtocol.JMAP), dao.rows.getValue(id)) }

        assertEquals(listOf("blob-new-1", "blob-new-2"), parts.map { it.blobId })
        assertEquals(listOf(null, null), parts.map { it.partId })
        assertEquals(listOf("attachment", "inline"), parts.map { it.disposition })
        assertEquals(listOf(null, "logo@x"), parts.map { it.cid })
        assertEquals(listOf("doc.pdf", "logo.png"), host.uploads.map { it.second })
        assertArrayEquals(file, host.uploads[0].first)
        assertArrayEquals(image, host.uploads[1].first)
    }

    @Test fun `a failed upload at the hour throws rather than hand over a message short of it`() {
        val sender = sender()
        val id = runBlocking {
            sender.insertScheduledSend(row(), listOf(cacheFile("a.pdf", "a".toByteArray()), cacheFile("b.pdf", "b".toByteArray())), noBlob)
        }
        host.refuseUpload = true

        try {
            val parts = runBlocking { sender.scheduledSendAttachments(credentials(MailProtocol.JMAP), dao.rows.getValue(id)) }
            fail("the upload failure was swallowed and ${parts.size} of 2 attachments were handed over")
        } catch (_: IllegalStateException) {
        }
        assertEquals("the row and its files wait for the retry", 2, filesUnder(scheduledRoot).size)
    }

    @Test fun `an attachment gone from the scheduled tree at the hour throws on IMAP too`() {
        val sender = sender()
        val id = runBlocking { sender.insertScheduledSend(row(), listOf(cacheFile("a.pdf", "a".toByteArray())), noBlob) }
        filesUnder(scheduledRoot).forEach { it.delete() }

        try {
            runBlocking { sender.scheduledSendAttachments(credentials(MailProtocol.IMAP), dao.rows.getValue(id)) }
            fail("an IMAP row was handed over without its attachment")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun `a row without attachments hands over none and uploads nothing`() {
        val sender = sender()
        val id = runBlocking { sender.insertScheduledSend(row(), emptyList(), noBlob) }

        val parts = runBlocking { sender.scheduledSendAttachments(credentials(MailProtocol.JMAP), dao.rows.getValue(id)) }

        assertEquals(emptyList<EmailBodyPart>(), parts)
        assertEquals(0, host.uploads.size)
        assertFalse(scheduledRoot.exists() && filesUnder(scheduledRoot).isNotEmpty())
    }

    @Test fun `two attachments of the same name and an empty one are all kept and all handed over`() {
        val first = byteArrayOf(1, 1)
        val second = byteArrayOf(2, 2, 2)
        fun cached(sub: String, name: String, type: String, bytes: ByteArray): EmailBodyPart {
            val f = File(cache, "$sub/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }
            return EmailBodyPart(partId = f.absolutePath, type = type, size = bytes.size.toLong(), name = name, disposition = "attachment")
        }
        val sender = sender()
        val id = runBlocking {
            sender.insertScheduledSend(
                row(),
                listOf(
                    cached("a", "IMG.jpg", "image/jpeg", first),
                    cached("b", "IMG.jpg", "image/jpeg", second),
                    cached("c", "empty.txt", "text/plain", ByteArray(0)),
                ),
                noBlob,
            )
        }
        val stored = OutboxAttachments.decode(dao.rows.getValue(id).attachmentsJson)
        assertEquals(listOf("IMG.jpg", "IMG.jpg", "empty.txt"), stored.map { it.name })
        assertEquals(listOf(2L, 3L, 0L), stored.map { it.size })

        val imap = runBlocking { sender.scheduledPartsFor(credentials(MailProtocol.IMAP), stored) }
        assertEquals(listOf("IMG.jpg", "IMG.jpg", "empty.txt"), imap.map { it.name })
        assertArrayEquals(first, File(imap[0].partId!!).readBytes())
        assertArrayEquals(second, File(imap[1].partId!!).readBytes())
        assertArrayEquals(ByteArray(0), File(imap[2].partId!!).readBytes())

        val jmap = runBlocking { sender.scheduledPartsFor(credentials(MailProtocol.JMAP), stored) }
        assertEquals(listOf("blob-new-1", "blob-new-2", "blob-new-3"), jmap.map { it.blobId })
        assertEquals(listOf("IMG.jpg", "IMG.jpg", "empty.txt"), host.uploads.map { it.second })
        assertArrayEquals(first, host.uploads[0].first)
        assertArrayEquals(second, host.uploads[1].first)
        assertArrayEquals(ByteArray(0), host.uploads[2].first)
    }

    // ---- removal of the row: the worker's three sites and the cancellation all call it.

    @Test fun `deleting a scheduled row deletes its files and spares every other row's`() {
        val sender = sender()
        val gone = runBlocking { sender.insertScheduledSend(row(), listOf(cacheFile("a.pdf", "a".toByteArray())), noBlob) }
        val kept = runBlocking { sender.insertScheduledSend(row(), listOf(cacheFile("b.pdf", "b".toByteArray())), noBlob) }
        val keptFile = File(OutboxAttachments.decode(dao.rows.getValue(kept).attachmentsJson).single().path!!)
        val goneFile = File(OutboxAttachments.decode(dao.rows.getValue(gone).attachmentsJson).single().path!!)

        runBlocking { sender.deleteScheduledSend(gone) }

        assertEquals(setOf(kept), dao.rows.keys)
        assertFalse("the deleted row's directory is still there", goneFile.parentFile.exists())
        assertTrue("another row's attachment was deleted", keptFile.isFile)
    }

    @Test fun `deleting a row never deletes a path outside the scheduled tree`() {
        val outside = File(tmp.root, "elsewhere/keep.pdf").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val forged = OutboxAttachments.encode(
            listOf(app.sterna.core.data.db.OutboxAttachment(kind = OutboxAttachments.KIND_IMAP_FILE, path = outside.absolutePath)),
        )
        val sender = sender()
        val id = runBlocking { dao.insert(row().copy(attachmentsJson = forged)) }

        runBlocking { sender.deleteScheduledSend(id) }

        assertTrue(outside.isFile)
        assertEquals(emptyMap<Long, ScheduledSendEntity>(), dao.rows)
    }
}
