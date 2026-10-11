package app.sterna.ui.compose

import app.sterna.core.data.mail.smimeSignalOf
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.inlineArmorKind
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuoteSourceTest {

    private val fetched = Email(
        id = "42",
        subject = "...",
        from = listOf(EmailAddress("Leo", "leo@example.org")),
        attachments = listOf(EmailBodyPart(partId = "1", type = "application/pgp-encrypted")),
    )

    private fun clear(text: String) = fetched.copy(
        textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
        bodyValues = mapOf("text" to EmailBodyValue(value = text)),
    )

    @Test fun `a reply quotes the decrypted copy, and says so`() {
        for (opening in listOf(ComposeOpening.REPLY, ComposeOpening.REPLY_ALL)) {
            val source = quoteSource(opening, fetched, CryptoKind.PGP_ENCRYPTED) { clear("the clear text") }
            assertEquals("$opening quotes nothing of an encrypted original", "the clear text", quotedOriginalText(source.email))
            assertTrue("$opening does not say its quote was decrypted", source.fromDecrypted)
        }
    }

    @Test fun `a forward carries the server's original, and never asks for the decrypted copy`() {
        for (opening in listOf(ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT)) {
            var asked = false
            val source = quoteSource(opening, fetched, CryptoKind.PGP_ENCRYPTED) { asked = true; clear("the clear text") }
            assertSame("$opening carries the clear text of an encrypted original", fetched, source.email)
            assertFalse("$opening claims a decrypted quote", source.fromDecrypted)
            assertFalse("$opening read the decrypted copy", asked)
        }
    }

    private val replies = listOf(ComposeOpening.REPLY, ComposeOpening.REPLY_ALL)

    private fun plain(text: String, vararg parts: EmailBodyPart) = Email(
        id = "7",
        textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
        bodyValues = mapOf("text" to EmailBodyValue(value = text)),
        attachments = parts.toList(),
    )

    @Test fun `a reply to an encrypted original with nothing decrypted quotes nothing, and says so`() {
        for (opening in replies) {
            val source = quoteSource(opening, fetched, CryptoKind.PGP_ENCRYPTED) { null }
            assertTrue("$opening quotes an original it cannot read", source.nothingToQuote)
            assertFalse("$opening claims a decrypted quote it does not hold", source.fromDecrypted)
            assertSame("$opening lost the original its recipients and thread are read from", fetched, source.email)
        }
    }

    @Test fun `a decrypted copy is quoted, never left out`() {
        for (opening in replies) {
            assertFalse(quoteSource(opening, fetched, CryptoKind.PGP_ENCRYPTED) { clear("the clear text") }.nothingToQuote)
        }
    }

    @Test fun `an original in the clear is quoted from the server, an empty one included`() {
        for (opening in replies) {
            for (original in listOf(plain("hello"), plain(""), Email(id = "8"))) {
                assertNull(smimeSignalOf(original))
                val source = quoteSource(opening, original, kind = null) { null }
                assertFalse("$opening left out a readable original", source.nothingToQuote)
                assertFalse(source.fromDecrypted)
                assertSame(original, source.email)
            }
        }
    }

    @Test fun `a forward leaves nothing out`() {
        for (opening in listOf(ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT)) {
            assertFalse("$opening", quoteSource(opening, fetched, CryptoKind.PGP_ENCRYPTED) { null }.nothingToQuote)
        }
    }

    private val armour = "-----BEGIN PGP MESSAGE-----\n\nhQEMA\n-----END PGP MESSAGE-----"

    private fun leftOut(original: Email, kind: CryptoKind?) =
        quoteSource(ComposeOpening.REPLY, original, kind) { null }.nothingToQuote

    @Test fun `every kind is left out with no text, and only the encrypted ones`() {
        val expected = mapOf(
            CryptoKind.PGP_ENCRYPTED to true,
            CryptoKind.SMIME_ENCRYPTED to true,
            CryptoKind.SMIME_OPAQUE_SIGNED to true,
            CryptoKind.PGP_SIGNED to false,
            CryptoKind.SMIME_SIGNED to false,
            CryptoKind.PGP_INLINE to false,
            null to false,
        )
        assertEquals(CryptoKind.entries.toSet() + null, expected.keys)
        for ((kind, encrypted) in expected) {
            for (textless in listOf(Email(id = "8"), plain(""), plain(" \n"))) {
                assertEquals("$kind with no text", encrypted, leftOut(textless, kind))
            }
        }
    }

    @Test fun `no kind leaves out a text that can be read`() {
        for (kind in CryptoKind.entries + null) {
            assertFalse("$kind lost a readable quote", leftOut(plain("hello"), kind))
        }
    }

    @Test fun `a readable text beside an encrypted part is quoted`() {
        val beside = plain("hello", EmailBodyPart(partId = "2", type = "application/pgp-encrypted"))
        assertFalse(leftOut(beside, CryptoKind.PGP_ENCRYPTED))
        assertEquals("hello", quotedOriginalText(beside))
    }

    @Test fun `an encrypted original is not read from its list preview`() {
        assertTrue(leftOut(fetched.copy(preview = "Version: 1"), CryptoKind.PGP_ENCRYPTED))
    }

    @Test fun `a body that is the armoured block is left out, whatever kind it was read as`() {
        for (kind in CryptoKind.entries + null) {
            assertTrue("$kind quotes the armoured block", leftOut(plain(armour), kind))
            assertTrue("$kind quotes the armoured block under blank lines", leftOut(plain("\n  \n$armour"), kind))
        }
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(plain(armour).textContent().orEmpty()))
    }

    @Test fun `an armoured block under an HTML part alone is left out`() {
        val html = Email(
            id = "10",
            htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
            bodyValues = mapOf("html" to EmailBodyValue(value = "<div><pre>${armour.replace("\n", "<br>")}</pre></div>")),
        )
        assertNull(html.textContent())
        assertNull(inlineArmorKind(html.textContent().orEmpty()))
        assertTrue(quotedOriginalText(html).startsWith("-----BEGIN PGP MESSAGE-----"))
        assertTrue("the armoured block is quoted", leftOut(html, kind = null))
    }

    @Test fun `a text that is read before its armoured block is quoted`() {
        val forwarded = plain("See below.\nThanks\n\n$armour")
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(forwarded.textContent().orEmpty()))
        assertFalse("a readable text is left out", leftOut(forwarded, CryptoKind.PGP_INLINE))
        assertFalse(leftOut(forwarded, kind = null))
    }

    @Test fun `an armoured block the original itself quotes is quoted`() {
        val quoting = plain(armour.lines().joinToString("\n") { "> $it" })
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(quoting.textContent().orEmpty()))
        assertFalse("a readable text is left out", leftOut(quoting, CryptoKind.PGP_INLINE))
    }

    @Test fun `a clear-signed text is quoted`() {
        val signed = plain("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nhello\n-----BEGIN PGP SIGNATURE-----")
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(signed.textContent().orEmpty()))
        assertFalse("a clear-signed text is left out", leftOut(signed, CryptoKind.PGP_INLINE))
    }

    @Test fun `an S-MIME envelope is left out, a detached S-MIME signature is quoted`() {
        val envelope = Email(id = "9", attachments = listOf(EmailBodyPart(partId = "1", type = "application/pkcs7-mime", name = "smime.p7m")))
        val signed = plain("hello", EmailBodyPart(partId = "2", type = "application/pkcs7-signature", name = "smime.p7s"))

        assertTrue("an envelope is quoted as an empty line", quoteSource(ComposeOpening.REPLY, envelope, smimeSignalOf(envelope)) { null }.nothingToQuote)
        assertFalse("a signed text is left out", quoteSource(ComposeOpening.REPLY, signed, smimeSignalOf(signed)) { null }.nothingToQuote)
        assertEquals("hello", quotedOriginalText(signed))
    }

    @Test fun `an OpenPGP signature over readable text is quoted`() {
        val signed = plain("hello", EmailBodyPart(partId = "2", type = "application/pgp-signature"))
        assertFalse("a signed text is left out", quoteSource(ComposeOpening.REPLY, signed, CryptoKind.PGP_SIGNED) { null }.nothingToQuote)
    }

    @Test fun `the decrypted text is cut at the sender's signature like any original`() {
        val source = quoteSource(ComposeOpening.REPLY, fetched, CryptoKind.PGP_ENCRYPTED) { clear("kept\n-- \nLeo") }
        assertEquals("kept", quotedOriginalText(source.email).trimEnd())
    }

    @Test fun `a decrypted html body is quoted as text, not as markup`() {
        val html = fetched.copy(
            htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
            bodyValues = mapOf("html" to EmailBodyValue(value = "<p>clear <b>text</b></p>")),
        )
        val source = quoteSource(ComposeOpening.REPLY, fetched, CryptoKind.PGP_ENCRYPTED) { html }
        assertEquals("clear text", quotedOriginalText(source.email).trim())
    }

    // `ComposeViewModel` cannot be built on the JVM: the call is pinned as text, arguments included.

    @Test fun `prepare feeds the decision its opening, this account and the fetched original`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val start = code.indices.single { code[it].startsWith("val fetched = ") }
        assertEquals(
            listOf(
                "val fetched = runCatching { repo.fetchEmail(credentials, replyToId) }.getOrNull()",
                "val source = fetched",
                "?.let { quoteSource(opening, it, repo.cryptoKindOf(it) ?: smimeSignalOf(it)) { repo.decryptedOriginalToQuote(credentials.id, it) } }",
                "?.also { decryptedQuote.offer(it) }",
                "if (source == null) {",
            ),
            code.subList(start, start + 5),
        )
        assertEquals(
            "the composer builds on another original than the one the decision returned",
            listOf("val original = source.email"),
            code.filter { it.startsWith("val original = source") },
        )
        assertEquals(
            "the decrypted copy is read somewhere the opening does not decide",
            1,
            code.count { "decryptedOriginalToQuote" in it },
        )
        assertEquals(
            "something below reads the fetched original around the decision",
            2,
            code.count { Regex("""\bfetched\b""").containsMatchIn(it) },
        )
    }

    @Test fun `prepare says the quote is left out, then builds the body the usual way`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val start = code.indices.single { code[it] == "inReplyTo = original.messageId" }
        assertEquals(
            listOf(
                "inReplyTo = original.messageId",
                "references = original.references + original.messageId",
                "if (source.nothingToQuote) _notices.tryEmit(R.string.compose_quote_not_added_encrypted)",
                "if (cached == null) {",
                "_prefill.value = buildPrefill(original, mode, selves(credentials))",
                "} else if (!quoteAlreadyOnScreen) {",
                "_replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))",
                "}",
                "}",
                "}",
            ),
            code.subList(start, start + 10),
        )
        assertEquals(1, code.count { "compose_quote_not_added_encrypted" in it })
    }

    @Test fun `the body is built after the verdict is kept and the sending identity is settled`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val order = listOf(
            "val original = source.email",
            "quoteLeftOut = source.nothingToQuote",
            "preselectReceivingIdentity(credentials.id, original)",
            "inReplyTo = original.messageId",
        )
        val at = order.map { line -> code.indices.single { code[it] == line } }
        assertEquals("$order are out of order", at.sorted(), at)
        assertEquals(
            listOf("val original = source.email", "quoteLeftOut = source.nothingToQuote", "preselectReceivingIdentity(credentials.id, original)"),
            code.subList(at[0], at[0] + 3),
        )
    }

    @Test fun `a quote left out is an empty one, with neither attribution nor marker`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val start = code.indices.single { code[it] == "private fun quote(o: Email, zone: ZoneId): String {" }
        assertEquals("""if (quoteLeftOut) return """"", code[start + 1])
        assertEquals(
            listOf(
                "private var quoteLeftOut = false",
                "quoteLeftOut = source.nothingToQuote",
                """if (quoteLeftOut) return """"",
            ),
            code.filter { "quoteLeftOut" in it },
        )
        assertEquals(
            listOf(
                "quoteLeftOut = source.nothingToQuote",
                "if (source.nothingToQuote) _notices.tryEmit(R.string.compose_quote_not_added_encrypted)",
            ),
            code.filter { "nothingToQuote" in it },
        )
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error("Cannot find $VIEW_MODEL from ${File("").absolutePath}")
        }
    }
}
