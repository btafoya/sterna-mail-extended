package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.JdbcSmimeCertificateDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeCertificates
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.SmimeVerified
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S/MIME read path of the real [MailRepository], executed against a scripted IMAP server that serves
 * messages signed by `openssl cms`, with a recording [SmimeEngine] in place of Bouncy Castle (which lives
 * in `:app`, where `BouncyCastleSmimeEngineTest` runs it on the same messages). `decryptMessage` is called
 * the way the reader calls it over a body it already shows, with the kind [smimeSignalOf] read.
 *
 * An encrypted message goes the same way, opened with a software key from `reader-key.pem` standing in for
 * the device keystore; the recording engine hands back what `openssl cms -decrypt` gives for these fixtures,
 * and `BouncyCastleSmimeEngineTest` decrypts them for real.
 *
 * What this does NOT cover: the JMAP fetch of the source, the Android keystore, and the ViewModel that decides
 * what reaches the screen (`SignatureOverlayTest` runs that decision on the results asserted here).
 */
class SmimeReadPathTest {

    private class RecordingEngine(
        private val answer: SmimeVerified,
        private val recipient: Boolean = true,
        private val clear: ByteArray = ByteArray(0),
    ) : SmimeEngine {
        val detached = mutableListOf<ByteArray>()
        val signatures = mutableListOf<ByteArray>()
        val opaque = mutableListOf<ByteArray>()
        val senders = mutableListOf<String?>()
        val asked = mutableListOf<Pair<ByteArray, X509Certificate>>()
        val decrypted = mutableListOf<Triple<ByteArray, PrivateKey, X509Certificate>>()

        override fun namesRecipient(envelopedData: ByteArray, certificate: X509Certificate): Boolean {
            asked += envelopedData to certificate
            return recipient
        }

        override fun decrypt(envelopedData: ByteArray, key: PrivateKey, certificate: X509Certificate): ByteArray {
            decrypted += Triple(envelopedData, key, certificate)
            return clear
        }

        override fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray = error("a read path never signs")
        override fun encrypt(content: ByteArray, recipients: List<X509Certificate>): ByteArray = error("a read path never encrypts")

        override fun verifyDetached(signedBytes: ByteArray, signature: ByteArray, senderAddress: String?): SmimeVerified {
            detached += signedBytes
            signatures += signature
            senders += senderAddress
            return answer
        }

        override fun verifyOpaque(signedData: ByteArray, senderAddress: String?): SmimeVerified {
            opaque += signedData
            senders += senderAddress
            return answer
        }

        fun untouched() = detached.isEmpty() && opaque.isEmpty() && asked.isEmpty() && decrypted.isEmpty()
    }

    /** An account store holding one account, whose certificate alias is [alias]. */
    private class OneAccount(private val alias: String) : RecordingAccountStorage() {
        override fun account(id: String): StoredAccount? =
            StoredAccount(id = ACCOUNT, server = "scripted", username = "tester", smimeCertAlias = alias).takeIf { id == ACCOUNT }
    }

    private val readerCertificate: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/reader-cert.pem")!!) as X509Certificate

    private val readerKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                javaClass.getResourceAsStream("/smime/reader-key.pem")!!.readBytes().toString(Charsets.US_ASCII)
                    .lines().filterNot { it.startsWith("-----") }.joinToString(""),
            ),
        ),
    )

    /** The keystore, holding the reader's key under [READER_ALIAS] only. */
    private val keystore = SmimeKeySource { alias -> if (alias == READER_ALIAS) SmimeKey(readerKey, listOf(readerCertificate)) else null }

    private fun source(name: String): String =
        javaClass.getResourceAsStream("/smime/$name")!!.readBytes().toString(Charsets.ISO_8859_1)

    private fun serving(source: String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line == "UID FETCH $UID (BODY.PEEK[])" ->
                "* 1 FETCH (UID $UID BODY[] {${source.length}}\r\n$source)\r\n$tag OK fetch completed\r\n"
            line == "UID FETCH $UID (INTERNALDATE)" ->
                "* 1 FETCH (UID $UID INTERNALDATE \"$INTERNALDATE\")\r\n$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private val row = EmailEntity(
        id = ID,
        accountId = ACCOUNT,
        mailboxId = INBOX,
        threadId = "t-1",
        subject = "Signed alice",
        preview = "",
        receivedAt = "2026-09-30T10:00:00Z",
        fromName = null,
        fromEmail = SENDER,
        seen = true,
        flagged = false,
        hasAttachment = true,
        sortKey = 1L,
    )

    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "emailsByIds" -> listOf(row)
            "mailboxOf" -> INBOX
            "toString" -> "scripted EmailDao"
            else -> error("EmailDao.${method.name} was not expected on the read path.")
        }
    } as EmailDao

    private fun repository(
        engine: SmimeEngine,
        store: RecordingAccountStorage = RecordingAccountStorage(),
        keys: SmimeKeySource? = null,
        certificates: SmimeCertificates? = null,
        logWarn: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    ): MailRepository {
        return MailRepository(
            client = JmapClient(),
            emailDao = emailDao(),
            emailFtsDao = unusableDao<EmailFtsDao>(),
            emailBodyDao = unusableDao<EmailBodyDao>(),
            mailboxDao = unusableDao<MailboxDao>(),
            imap = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher()),
            scheduledSendDao = unusableDao<ScheduledSendDao>(),
            snoozedDao = unusableDao<SnoozedDao>(),
            recentContactDao = unusableDao<RecentContactDao>(),
            accountStore = store,
            outboxDao = unusableDao<OutboxDao>(),
            purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
            localDraftDao = unusableDao<LocalDraftDao>(),
            serverContactDao = unusableDao<ServerContactDao>(),
            mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
            outboxFilesDir = File("outbox-never-read"),
            localDraftFilesDir = File("drafts-never-read"),
            smimeEngine = engine,
            smimeKeys = keys,
            smimeCertificates = certificates,
            logWarn = logWarn,
        )
    }

    private fun credentials(host: String, port: Int) = AccountCredentials(
        server = "scripted",
        username = "tester",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(host, port, ConnectionSecurity.NONE),
    )

    private fun check(name: String, engine: RecordingEngine, kind: CryptoKind): PgpResult<MessageBody> =
        checkSource(source(name), engine, kind)

    private fun checkSource(raw: String, engine: RecordingEngine, kind: CryptoKind): PgpResult<MessageBody> =
        ScriptedImapServer(serving(raw)).use { server ->
            runBlocking { repository(engine).decryptMessage(credentials(server.host, server.port), ID, lockedKind = kind) }
        }

    @Test(timeout = 30_000)
    fun `a detached signature is checked on the octets the sender signed, and the signed entity is shown`() {
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null))

        val result = check("a-detached.eml", engine, CryptoKind.SMIME_SIGNED)

        val body = (result as? PgpResult.Success)?.value ?: error("expected a checked body, got $result")
        assertArrayEquals(
            "the engine must get the signed entity's own octets, ISO-8859-1 and CRLF, 8-bit bytes intact",
            SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1),
            engine.detached.single(),
        )
        assertEquals("and the DER of the signature part", 0x30, engine.signatures.single()[0].toInt())
        assertEquals("and the From address to compare the certificate with", listOf<String?>(SENDER), engine.senders)
        val crypto = body.crypto as MessageCrypto.Decrypted
        assertEquals(PgpSignatureState.VALID_CONFIRMED, crypto.signature)
        assertEquals(SENDER, crypto.signatureUserId)
        assertNull("an S/MIME signer has no key id", crypto.signatureKeyId)
        assertEquals("a signature alone does not make the message encrypted", false, crypto.wasEncrypted)
        assertEquals("the verdict is worded for a certificate", true, crypto.smime)
        assertTrue(body.email.textContent().orEmpty(), "Bonjour, café crème à 8 bits." in body.email.textContent().orEmpty())
        assertEquals("the signature part is not a file of the signed entity", emptyList<String?>(), body.email.attachments.map { it.name })
    }

    @Test(timeout = 30_000)
    fun `a source read with bare LF line ends still hands the engine CRLF octets`() {
        val bare = source("a-detached.eml").replace("\r\n", "\n")
        assertTrue("the served source holds no CR at all", '\r' !in bare)
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null))

        val result = checkSource(bare, engine, CryptoKind.SMIME_SIGNED)

        assertTrue("expected a checked body, got $result", result is PgpResult.Success)
        assertArrayEquals(SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1), engine.detached.single())
    }

    @Test(timeout = 30_000)
    fun `an opaque signed message shows the entity it carries, and no smime p7m`() {
        val opened = MimeParser.parseBody(source("e-opaque.eml"))
        assertEquals("as ordinary mail it is a file and no text", listOf("smime.p7m"), opened.attachments.map { it.name })
        assertNull(opened.text)
        val engine = RecordingEngine(
            SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1)),
        )

        val result = check("e-opaque.eml", engine, CryptoKind.SMIME_OPAQUE_SIGNED)

        val body = (result as? PgpResult.Success)?.value ?: error("expected a checked body, got $result")
        assertEquals("the whole signed-data goes to the engine, as DER", 0x30, engine.opaque.single()[0].toInt())
        assertTrue("and nothing as a detached signature", engine.detached.isEmpty())
        assertTrue(body.email.textContent().orEmpty(), "Bonjour, café crème à 8 bits." in body.email.textContent().orEmpty())
        assertEquals(emptyList<String?>(), body.email.attachments.map { it.name })
        val crypto = body.crypto as MessageCrypto.Decrypted
        assertEquals(PgpSignatureState.VALID_CONFIRMED, crypto.signature)
        assertEquals(true, crypto.smime)
    }

    private fun decryptSource(
        name: String,
        engine: RecordingEngine,
        store: RecordingAccountStorage,
        keys: SmimeKeySource? = keystore,
    ): Pair<MailRepository, PgpResult<MessageBody>> {
        val repository = repository(engine, store, keys)
        val result = ScriptedImapServer(serving(source(name))).use { server ->
            runBlocking {
                runCatching { repository.decryptMessage(credentials(server.host, server.port), ID, lockedKind = CryptoKind.SMIME_OPAQUE_SIGNED) }
                    .getOrElse { error("decryptMessage threw instead of answering: $it") }
            }
        }
        return repository to result
    }

    private fun envelopeOf(name: String): ByteArray = MimeParser.detectCrypto(source(name))!!.envelopedDataDer!!

    @Test(timeout = 30_000)
    fun `an encrypted message is opened with the account's certificate and shows what it carries, and no smime p7m`() {
        val raw = source("g-enveloped.eml")
        val parsed = MimeParser.parseBody(raw)
        val listed = Email(id = ID, attachments = parsed.attachments.map { EmailBodyPart(partId = it.section, name = it.name, type = it.type) })
        assertEquals("its part looks like an opaque signature's, so the reader does ask", CryptoKind.SMIME_OPAQUE_SIGNED, smimeSignalOf(listed))
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.INVALID, null, null), clear = MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1))

        val (repository, result) = decryptSource("g-enveloped.eml", engine, OneAccount(READER_ALIAS))

        val body = (result as? PgpResult.Success)?.value ?: error("expected the decrypted message, got $result")
        val (der, key, certificate) = engine.decrypted.single()
        assertArrayEquals("the engine gets the DER of the envelope, as octets", envelopeOf("g-enveloped.eml"), der)
        assertEquals("the DER opens as a ContentInfo", 0x30, der[0].toInt())
        assertTrue("the key is handed over as the keystore gave it, never copied", key === readerKey)
        assertTrue("with the certificate it belongs to", certificate === readerCertificate)
        assertTrue("and the envelope is asked whether it names that certificate first", engine.asked.single().second === readerCertificate)
        val crypto = body.crypto as MessageCrypto.Decrypted
        assertEquals("an envelope alone is encrypted", true, crypto.wasEncrypted)
        assertEquals("and worded for a certificate", true, crypto.smime)
        assertEquals("with no signature", PgpSignatureState.NONE, crypto.signature)
        assertTrue("no signature was checked", engine.detached.isEmpty() && engine.opaque.isEmpty())
        assertTrue(body.email.textContent().orEmpty(), "Bonjour, café crème à 8 bits." in body.email.textContent().orEmpty())
        assertEquals("the file inside is listed, smime.p7m is not", listOf("note.bin"), body.email.attachments.map { it.name })
        val part = body.email.attachments.single()
        assertEquals("pgp:2", part.partId)

        val credentials = credentials("127.0.0.1", 1)
        val bytes = runBlocking { repository.downloadAttachment(credentials, part, ID) }
        assertArrayEquals("the file comes out of the decrypted entity, its 8-bit octets intact", NOTE_BIN, bytes)
        val again = runBlocking { repository.decryptMessage(credentials, ID, lockedKind = CryptoKind.SMIME_OPAQUE_SIGNED) }
        assertEquals("a second open is served from memory, with the server gone", body, (again as? PgpResult.Success)?.value)
        assertEquals("and the key is not used again", 1, engine.decrypted.size)
    }

    @Test(timeout = 30_000)
    fun `a message signed then encrypted shows as encrypted and signed, the signature checked with no second fetch`() {
        val engine = RecordingEngine(
            SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null),
            clear = source("a-detached.eml").toByteArray(Charsets.ISO_8859_1),
        )

        val (_, result) = decryptSource("m-signed-then-enveloped.eml", engine, OneAccount(READER_ALIAS))

        val body = (result as? PgpResult.Success)?.value ?: error("expected the decrypted message, got $result")
        assertArrayEquals(
            "the signature is checked over the signed entity's CRLF octets, inside the envelope",
            SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1),
            engine.detached.single(),
        )
        val crypto = body.crypto as MessageCrypto.Decrypted
        assertEquals("encrypted, though the verdict came from a signature check", true, crypto.wasEncrypted)
        assertEquals(PgpSignatureState.VALID_CONFIRMED, crypto.signature)
        assertEquals(SENDER, crypto.signatureUserId)
        assertEquals(true, crypto.smime)
        assertTrue(body.email.textContent().orEmpty(), "Bonjour, café crème à 8 bits." in body.email.textContent().orEmpty())
        assertEquals("neither smime.p7m nor smime.p7s is a file of the message", emptyList<String?>(), body.email.attachments.map { it.name })
    }

    @Test(timeout = 30_000)
    fun `an account with no certificate is asked to choose one, and the engine is never asked`() {
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.NONE, null, null))
        val store = OneAccount("")

        val (_, result) = decryptSource("g-enveloped.eml", engine, store)

        assertEquals(PgpResult.CertificateToChoose, result)
        assertTrue(engine.untouched())
    }

    @Test(timeout = 30_000)
    fun `a certificate the device will not hand over is asked for again`() {
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.NONE, null, null))
        val store = OneAccount("removed-from-the-device")

        val (_, result) = decryptSource("g-enveloped.eml", engine, store)

        assertEquals(PgpResult.CertificateToChoose, result)
        assertTrue(engine.untouched())
    }

    @Test(timeout = 30_000)
    fun `a message encrypted for another certificate says so, and nothing is decrypted`() {
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.NONE, null, null), recipient = false)
        val store = OneAccount(READER_ALIAS)

        val (_, result) = decryptSource("g-enveloped.eml", engine, store)

        assertEquals(PgpResult.NotEncryptedForCertificate, result)
        assertTrue("the decryption is never tried", engine.decrypted.isEmpty())
    }

    @Test(timeout = 30_000)
    fun `a signed message whose source is out of reach answers an error, and asks nothing of the engine`() {
        val (host, port) = ScriptedImapServer(serving(source("a-detached.eml"))).use { it.host to it.port }
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null))

        val result = runBlocking {
            runCatching { repository(engine).decryptMessage(credentials(host, port), ID, lockedKind = CryptoKind.SMIME_SIGNED) }
                .getOrElse { error("decryptMessage threw instead of answering: $it") }
        }

        assertTrue("expected an error, got $result", result is PgpResult.Error)
        assertTrue(engine.untouched())
    }

    /** A certificate store that records what it is asked to keep, and can be made to fail. */
    private class RecordingCertificates(
        private val failing: Boolean = false,
        private val hanging: Boolean = false,
        private val into: SmimeCertificates? = null,
    ) : SmimeCertificates {
        val kept = CompletableDeferred<Triple<String, ByteArray, Long>>()

        override suspend fun retain(address: String, certificate: ByteArray, messageDate: Long) {
            into?.retain(address, certificate, messageDate)
            kept.complete(Triple(address, certificate, messageDate))
            if (failing) error(DISK_FULL)
            if (hanging) awaitCancellation()
        }

        override suspend fun certificatesFor(addresses: Collection<String>, now: Long): Map<String, ByteArray> = error("not read here")
    }

    /** [name] opened as [kind] by a repository keeping into [certificates], and what it kept, awaited while the
     *  server is still up: the keeping runs after the verdict is back, and asks the server for the date. */
    private fun keptFrom(
        name: String,
        kind: CryptoKind,
        engine: RecordingEngine,
        certificates: RecordingCertificates,
        logWarn: (String, String, Throwable?) -> Unit = { _, _, _ -> },
        afterKept: suspend () -> Unit = {},
    ): Pair<PgpResult<MessageBody>, Triple<String, ByteArray, Long>> =
        ScriptedImapServer(serving(source(name))).use { server ->
            runBlocking {
                val result = repository(engine, OneAccount(READER_ALIAS), keystore, certificates, logWarn)
                    .decryptMessage(credentials(server.host, server.port), ID, lockedKind = kind)
                val kept = withTimeout(20_000) { certificates.kept.await() }
                afterKept()
                result to kept
            }
        }

    @Test(timeout = 30_000)
    fun `a confirmed signature's certificate is kept for the From address, dated by the server`() {
        val der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x07)
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null, der))

        val (result, kept) = keptFrom("a-detached.eml", CryptoKind.SMIME_SIGNED, engine, RecordingCertificates())

        assertTrue("$result", result is PgpResult.Success)
        assertEquals(SENDER, kept.first)
        assertArrayEquals(der, kept.second)
        assertEquals(
            "the date is INTERNALDATE, not the cached row's receivedAt, which on IMAP is the sender's own Date:",
            java.time.Instant.parse("2026-06-01T10:00:00Z").toEpochMilli(),
            kept.third,
        )
    }

    @Test(timeout = 30_000)
    fun `the certificate of a signature found inside an envelope is kept too`() {
        val der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x08)
        val engine = RecordingEngine(
            SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null, der),
            clear = source("a-detached.eml").toByteArray(Charsets.ISO_8859_1),
        )

        val (result, kept) = keptFrom("m-signed-then-enveloped.eml", CryptoKind.SMIME_OPAQUE_SIGNED, engine, RecordingCertificates())

        assertTrue("$result", result is PgpResult.Success)
        assertEquals(SENDER, kept.first)
        assertArrayEquals(der, kept.second)
    }

    @Test(timeout = 30_000)
    fun `a store that never answers leaves the verdict shown at once`() {
        val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null, byteArrayOf(0x30)))

        val (result, _) = keptFrom("a-detached.eml", CryptoKind.SMIME_SIGNED, engine, RecordingCertificates(hanging = true))

        val crypto = ((result as? PgpResult.Success)?.value ?: error("expected a checked body, got $result")).crypto as MessageCrypto.Decrypted
        assertEquals(PgpSignatureState.VALID_CONFIRMED, crypto.signature)
    }

    /**
     * On Android a throw that escapes the repository's background scope kills the app; on the JVM it only reaches the
     * default uncaught-exception handler, which this test watches until the failure has been either logged or let out.
     */
    @Test(timeout = 30_000)
    fun `a store that fails is logged, and lets nothing escape its coroutine`() {
        val settled = CompletableDeferred<String>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (failure.message == DISK_FULL) settled.complete("escaped on ${thread.name}: $failure") else previous?.uncaughtException(thread, failure)
        }
        try {
            val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null, byteArrayOf(0x30)))

            val (result, _) = keptFrom(
                "a-detached.eml", CryptoKind.SMIME_SIGNED, engine, RecordingCertificates(failing = true),
                logWarn = { _, _, failure -> if (failure?.message == DISK_FULL) settled.complete("logged") },
                afterKept = { withTimeout(20_000) { settled.await() } },
            )

            assertEquals("logged", runBlocking { settled.await() })
            val crypto = ((result as? PgpResult.Success)?.value ?: error("expected a checked body, got $result")).crypto as MessageCrypto.Decrypted
            assertEquals(PgpSignatureState.VALID_CONFIRMED, crypto.signature)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test(timeout = 30_000)
    fun `an old signed message delivered again today does not bring its certificate back over a newer one`() {
        val signedIn2020 = java.time.Instant.parse("2020-01-15T09:00:00Z").toEpochMilli()
        val keptIn2025 = java.time.Instant.parse("2025-03-01T00:00:00Z").toEpochMilli()
        val newer = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x25)
        val old = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x20)
        JdbcSmimeCertificateDao().use { dao ->
            runBlocking { dao.retain(SENDER, newer, keptIn2025) }
            val engine = RecordingEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, SENDER, null, old, signedAt = signedIn2020))

            val (_, kept) = keptFrom("a-detached.eml", CryptoKind.SMIME_SIGNED, engine, RecordingCertificates(into = dao))

            assertEquals("ranked at its signing time, not at the 2026 INTERNALDATE", signedIn2020, kept.third)
            assertEquals(listOf(Triple(SENDER, newer.toList(), keptIn2025)), dao.rows())
        }
    }

    @Test fun `only a signature or signed-data part as a mail client writes it makes the reader check`() {
        fun part(type: String?, name: String?) = EmailBodyPart(partId = "2", type = type, name = name)
        fun listed(vararg parts: EmailBodyPart, text: String? = null) = Email(
            id = "1",
            attachments = parts.toList(),
            textBody = if (text == null) emptyList() else listOf(EmailBodyPart(partId = "text", type = "text/plain")),
            bodyValues = if (text == null) emptyMap() else mapOf("text" to EmailBodyValue(value = text)),
        )
        assertNull("ordinary mail", smimeSignalOf(listed(part("application/pdf", "a.pdf"), part(null, null), text = "Hi")))
        assertNull("OpenPGP", smimeSignalOf(listed(part("application/pgp-encrypted", null), part("application/pgp-signature", "signature.asc"))))
        // Named as clients name them, or not named, which IMAP turns into the subtype.
        listOf(
            part("application/pkcs7-signature", "smime.p7s"),
            part("Application/PKCS7-Signature", "SMIME.P7S"),
            part("application/x-pkcs7-signature", "smime.p7s"),
            part("application/pkcs7-signature", null),
            part("application/pkcs7-signature", "pkcs7-signature"),
            part("application/x-pkcs7-signature", "x-pkcs7-signature"),
        ).forEach { signature ->
            assertEquals("$signature", CryptoKind.SMIME_SIGNED, smimeSignalOf(listed(part("text/plain", null), signature, text = "Hi")))
        }
        listOf(part("application/pkcs7-mime", "smime.p7m"), part("application/x-pkcs7-mime", "smime.p7m"), part("application/pkcs7-mime", null))
            .forEach { signed -> assertEquals("$signed", CryptoKind.SMIME_OPAQUE_SIGNED, smimeSignalOf(listed(signed))) }
        assertNull(
            "an invoice signed as a file and attached to ordinary mail costs no download",
            smimeSignalOf(listed(part("application/pkcs7-mime", "facture-2026-09.xml.p7m"), text = "Invoice attached")),
        )
        assertNull("even with no text beside it", smimeSignalOf(listed(part("application/pkcs7-mime", "facture-2026-09.xml.p7m"))))
        assertNull(
            "a smime.p7m next to a text body is a file, the opaque message itself has no text",
            smimeSignalOf(listed(part("application/pkcs7-mime", "smime.p7m"), text = "See the attachment")),
        )
        assertNull(
            "a detached signature kept as a file of its own",
            smimeSignalOf(listed(part("application/pkcs7-signature", "contract.pdf.p7s"), text = "Signed contract")),
        )
    }

    @Test fun `the crypto caches forget the least recently used entry`() {
        val cache = MemoryLru<String>(2)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")
        assertNull("b was the least recently used", cache.get("b"))
        assertEquals("1", cache.get("a"))
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals("3", cache.get("c"))
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"
        const val UID = 7L
        const val SENDER = "alice@example.test"
        const val READER_ALIAS = "reader"
        const val INTERNALDATE = "01-Jun-2026 10:00:00 +0000"
        const val DISK_FULL = "disk full"

        /** The attachment inside `g-enveloped.eml`, every octet class a text reading would damage. */
        val NOTE_BIN = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0xE9.toByte(), 0x0D, 0x0A, 0x41)

        /** What `g-enveloped.eml` carries, as `openssl cms -encrypt -binary` was given it. */
        val MIXED_ENTITY = "Content-Type: multipart/mixed; boundary=\"INNER\"\r\n\r\n" +
            "--INNER\r\nContent-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            "Bonjour, café crème à 8 bits.\r\nSecond line.\r\n" +
            "--INNER\r\nContent-Type: application/octet-stream; name=\"note.bin\"\r\n" +
            "Content-Disposition: attachment; filename=\"note.bin\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            Base64.getEncoder().encodeToString(NOTE_BIN) + "\r\n--INNER--\r\n"
        val ID = ImapMailService.emailId(ACCOUNT, INBOX, UID)

        /** What `openssl cms -sign -binary` signed for these fixtures. */
        const val SIGNED_ENTITY = "Content-Type: text/plain; charset=ISO-8859-1\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n\r\n" +
            "Bonjour, café crème à 8 bits.\r\nSecond line.\r\n"

        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
