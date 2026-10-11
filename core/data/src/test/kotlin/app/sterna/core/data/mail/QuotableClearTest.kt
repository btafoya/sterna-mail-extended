package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class QuotableClearTest {

    private val cache = MemoryLru<MessageBody>(8)

    /** What `fetchEmail` returns of a PGP/MIME message: headers, two crypto parts, no body. */
    private fun fromServer(id: String = "42") = Email(
        id = id,
        subject = "...",
        from = listOf(EmailAddress("Leo", "leo@example.org")),
        to = listOf(EmailAddress("Iris", "iris@example.org")),
        messageId = listOf("<m1@example.org>"),
        attachments = listOf(
            EmailBodyPart(partId = "1", type = "application/pgp-encrypted"),
            EmailBodyPart(partId = "2", type = "application/octet-stream"),
        ),
    )

    private fun decrypted(
        text: String,
        id: String = "42",
        wasEncrypted: Boolean = true,
        smime: Boolean = false,
    ) = MessageBody(
        email = Email(
            id = id,
            subject = "The protected subject",
            textBody = listOf(EmailBodyPart(partId = "pgp:1", type = "text/plain")),
            bodyValues = mapOf("pgp:1" to EmailBodyValue(value = text)),
        ),
        inlineImages = emptyMap(),
        crypto = MessageCrypto.Decrypted(
            signature = PgpSignatureState.NONE,
            signatureUserId = null,
            signatureKeyId = null,
            wasEncrypted = wasEncrypted,
            smime = smime,
        ),
    )

    private fun textOf(email: Email?) = email?.textContent()

    @Test fun `the decrypted body of this account's message is the one quoted`() {
        cache.put(cryptoKey("A", "42"), decrypted("the clear text"))
        assertEquals("the clear text", textOf(quotableClear("A", fromServer(), cache::get)))
    }

    @Test fun `everything but the body stays the server's, the subject first`() {
        cache.put(cryptoKey("A", "42"), decrypted("the clear text"))
        val server = fromServer()
        val quoted = quotableClear("A", server, cache::get)!!
        assertEquals(
            "the reply's subject would be built on the protected subject",
            server.copy(textBody = quoted.textBody, bodyValues = quoted.bodyValues),
            quoted,
        )
        assertEquals("...", quoted.subject)
    }

    @Test fun `a message decrypted under one account is not quoted from another holding the same id`() {
        cache.put(cryptoKey("A", "42"), decrypted("the clear text of account A"))
        assertNull(
            "account B quotes the clear text account A decrypted",
            textOf(quotableClear("B", fromServer(), cache::get)),
        )
    }

    @Test fun `two accounts holding the same id each quote their own`() {
        cache.put(cryptoKey("A", "42"), decrypted("of A"))
        cache.put(cryptoKey("B", "42"), decrypted("of B"))
        assertEquals("of A", textOf(quotableClear("A", fromServer(), cache::get)))
        assertEquals("of B", textOf(quotableClear("B", fromServer(), cache::get)))
    }

    @Test fun `the clear of an SMIME envelope is not quoted`() {
        cache.put(cryptoKey("A", "42"), decrypted("the clear text of an S/MIME envelope", smime = true))
        assertNull(
            "an S/MIME original is quoted from its decrypted copy",
            textOf(quotableClear("A", fromServer(), cache::get)),
        )
    }

    @Test fun `a signed-only original is left to the server's body`() {
        cache.put(cryptoKey("A", "42"), decrypted("signed, never encrypted", wasEncrypted = false))
        assertNull(textOf(quotableClear("A", fromServer(), cache::get)))
    }

    @Test fun `nothing decrypted, nothing to quote`() {
        assertNull(quotableClear("A", fromServer(), cache::get))
    }

    @Test fun `a decrypted html body is carried as html`() {
        val html = MessageBody(
            email = Email(
                id = "42",
                htmlBody = listOf(EmailBodyPart(partId = "pgp:1", type = "text/html")),
                textBody = listOf(EmailBodyPart(partId = "pgp:1", type = "text/html")),
                bodyValues = mapOf("pgp:1" to EmailBodyValue(value = "<p>clear</p>")),
            ),
            inlineImages = emptyMap(),
            crypto = MessageCrypto.Decrypted(PgpSignatureState.NONE, null, null, wasEncrypted = true),
        )
        cache.put(cryptoKey("A", "42"), html)
        assertEquals("<p>clear</p>", quotableClear("A", fromServer(), cache::get)?.htmlContent())
    }

    // `MailRepository` cannot be built on the JVM: its side of the read is pinned as text, arguments included.

    @Test fun `the repository hands the decision its own account and the decrypted cache`() {
        assertEquals(
            listOf(
                "fun decryptedOriginalToQuote(accountId: String, original: Email): Email? =",
                "quotableClear(accountId, original) { decryptedCache.get(it)?.body }",
            ),
            block("fun decryptedOriginalToQuote(", 2),
        )
        assertEquals(
            "a second reader of the decision appeared in MailRepository",
            1,
            repositoryCode().count { "quotableClear(" in it },
        )
    }

    @Test fun `fetchEmail still goes to the server and reads no decrypted copy`() {
        assertEquals(
            "fetchEmail also serves the forward and a reopened draft: neither may be handed clear text",
            listOf(
                "suspend fun fetchEmail(credentials: AccountCredentials, emailId: String): Email {",
                "if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead = false)",
                "val ctx = jmapSession(credentials)",
                "return client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)",
                "}",
            ),
            block("suspend fun fetchEmail(", 5),
        )
    }

    private fun block(prefix: String, count: Int): List<String> {
        val lines = repositoryCode()
        val start = lines.indices.single { lines[it].startsWith(prefix) }
        return lines.subList(start, start + count)
    }

    private fun repositoryCode(): List<String> = locate(REPOSITORY).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val REPOSITORY = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
    }
}
