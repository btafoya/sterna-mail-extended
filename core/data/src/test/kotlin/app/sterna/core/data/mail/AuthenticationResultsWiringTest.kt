package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.AuthenticationResults.DKIM
import app.sterna.core.data.mail.AuthenticationResults.DMARC
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's verdict on the sender (#276) from the account to the body cache: which names the
 * account trusts, what a failed JMAP read leaves, what the cache keeps, all executed; then the call
 * sites in `MailRepository`, read as source text, because `MailRepository` cannot be instantiated in
 * a JVM test.
 *
 * Holes of the source half: an argument name says nothing about what it is bound to; a pinned line
 * says nothing about its position in a control structure nor about execution order.
 */
class AuthenticationResultsWiringTest {

    // --- 1. the IMAP source, through the very parse `openEmailImap` runs -----------------------

    private fun message(headers: String) =
        headers.trimIndent().replace("\n", "\r\n") + "\r\n\r\nHello.\r\n"

    private val imapAccount = AccountCredentials(
        server = "",
        username = "ann@example.org",
        password = "",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint("IMAP.Example.NET", 993, ConnectionSecurity.TLS),
    )

    @Test fun `a folded instance off an IMAP source is read whole, the topmost trusted one`() {
        val raw = message(
            """
            Authentication-Results: imap.example.net;
             dkim=fail header.d=example.com;
             spf=fail smtp.mailfrom=example.com
            Authentication-Results: imap.example.net; dkim=pass; spf=pass; dmarc=pass
            From: boss@example.com
            Subject: Wire the money
            """,
        )

        assertEquals(
            listOf(DKIM, AuthenticationResults.SPF),
            AuthenticationResults.failuresOf(MimeParser.rawHeaders(raw), authservNamesFor(imapAccount, apiUrl = null)),
        )
    }

    @Test fun `an IMAP source with no instance is evaluated and says nothing`() {
        val raw = message("From: boss@example.com")

        assertEquals(
            emptyList<String>(),
            AuthenticationResults.failuresOf(MimeParser.rawHeaders(raw), authservNamesFor(imapAccount, apiUrl = null)),
        )
    }

    // --- 2. the account's trusted names, executed ----------------------------------------------

    @Test fun `an IMAP account trusts its IMAP host and its address domain`() {
        assertEquals(setOf("imap.example.net", "example.org"), authservNamesFor(imapAccount, apiUrl = null))
    }

    /** The entry and the `.well-known` landing differ on a real server: both are the account's. */
    @Test fun `a JMAP account trusts the host it typed, the API host it landed on, and its domain`() {
        val jmap = AccountCredentials(server = "Mail.Example.ORG/", username = "ann@example.org", password = "")

        assertEquals(
            setOf("mail.example.org", "stalwart.example.net", "example.org"),
            authservNamesFor(jmap, apiUrl = "https://stalwart.example.net:443/jmap/"),
        )
    }

    /** A login that is not an address names no domain; nothing is invented for it. */
    @Test fun `a bare login adds no domain`() {
        val jmap = AccountCredentials(server = "https://mail.example.org", username = "ann", password = "")

        assertEquals(setOf("mail.example.org"), authservNamesFor(jmap, apiUrl = null))
    }

    // --- 3. the JMAP read, executed: a failed read is unknown, never "nothing to say" -----------

    @Test fun `a failed JMAP header read leaves the verdict unknown and is not a success`() {
        val failed = originReadOf(headers = null, trustedNames = setOf("mail.example.org"))

        assertNull("a failed read must be null, never an empty list: cached, empty says 'all clear' for ever", failed.authenticationFailures)
        assertNull(failed.header)
        assertFalse(failed.succeeded)
    }

    @Test fun `a JMAP header read that happened carries both what it found`() {
        val read = originReadOf(
            headers = listOf(
                "Authentication-Results" to " mail.example.org; dmarc=fail",
                "X-Original-From" to "ann@example.org",
            ),
            trustedNames = setOf("mail.example.org"),
        )

        assertEquals(listOf(DMARC), read.authenticationFailures)
        assertEquals("ann@example.org", read.header)
        assertTrue(read.succeeded)
    }

    @Test fun `a JMAP header read that found nothing is an empty verdict, not an unknown one`() {
        val read = originReadOf(headers = listOf("From" to "ann@example.org"), trustedNames = setOf("mail.example.org"))

        assertEquals(emptyList<String>(), read.authenticationFailures)
        assertTrue(read.succeeded)
    }

    // --- 4. the body cache keeps the three states apart ----------------------------------------

    @Test fun `a body cached before the field existed decodes as unknown`() {
        assertNull(decodeCachedEmail("""{"id":"m1","subject":"Hi"}""").authenticationFailures)
    }

    @Test fun `the three states survive the body cache`() {
        for (state in listOf(null, emptyList(), listOf(DMARC, DKIM))) {
            val json = Json.encodeToString(Email.serializer(), Email(id = "m1", authenticationFailures = state))
            assertEquals("round trip of $state through $json", state, decodeCachedEmail(json).authenticationFailures)
        }
    }

    @Test fun `an empty verdict in the cache decodes as empty, not as unknown`() {
        assertEquals(
            emptyList<String>(),
            decodeCachedEmail("""{"id":"m1","authenticationFailures":[]}""").authenticationFailures,
        )
    }

    // --- 5. read as source text: the call sites -------------------------------------------------

    @Test fun `the IMAP open poses the verdict off the headers of the source it holds`() {
        assertEquals(
            listOf(
                "val headers = MimeParser.rawHeaders(raw)",
                "authenticationFailures = AuthenticationResults.failuresOf(headers, authservNamesFor(credentials, apiUrl = null)),",
            ),
            codeLines(bodyOf("openEmailImap"))
                .filter { it.startsWith("authenticationFailures") || it.startsWith("val headers =") },
        )
    }

    /** The IMAP branch hands back what `openEmailImap` found; otherwise `openMessage` wipes it. */
    @Test fun `the origin read keeps the IMAP verdict and builds every JMAP outcome through originReadOf`() {
        val body = codeLines(bodyOf("originReadFor"))

        assertEquals(
            listOf("return OriginRead(email.originalSender, email.authenticationFailures, succeeded = true)"),
            body.filter { "OriginRead(" in it },
        )
        assertEquals(
            listOf(
                "originReadOf(headers, authservNamesFor(credentials, ctx.session.apiUrl))",
                "originReadOf(headers = null, trustedNames = emptySet())",
            ),
            body.filter { "originReadOf(" in it },
        )
    }

    @Test fun `the open, the unverified open and the prefetch all show the verdict they read`() {
        assertEquals(
            listOf("val shown = email.copy(originalSender = origin.header, authenticationFailures = origin.authenticationFailures)"),
            codeLines(bodyOf("openMessage")).filter { "email.copy(" in it },
        )
        assertEquals(
            listOf("val shown = email.copy(originalSender = origin.header, authenticationFailures = origin.authenticationFailures)"),
            codeLines(bodyOf("unverifiedBody")).filter { "email.copy(" in it },
        )
        assertEquals(
            listOf("val withOrigin = email.copy(originalSender = origin?.header, authenticationFailures = origin?.authenticationFailures)"),
            codeLines(bodyOf("prefetchInboxBodies")).filter { "email.copy(" in it },
        )
    }

    /** What is copied is not necessarily what leaves: the object handed on is pinned too. */
    @Test fun `the prefetch caches and the unverified open returns the copy that carries the verdict`() {
        assertEquals(
            listOf("persistBody(credentials.id, email.id, withOrigin, emptyMap())"),
            codeLines(bodyOf("prefetchInboxBodies")).filter { it.startsWith("persistBody(") },
        )
        assertEquals(
            listOf("return MessageBody(shown, inline)"),
            codeLines(bodyOf("unverifiedBody")).filter { "MessageBody(" in it && !it.startsWith("private") },
        )
    }

    /** The prefetch's grouped read has an expression body, out of reach of `bodyOf`: the whole file
     *  is read instead, and the four lines are pinned in their file order. */
    @Test fun `every origin read is built by originReadOf, the prefetch's included`() {
        assertEquals(
            listOf(
                "internal fun originReadOf(headers: List<Pair<String, String>>?, trustedNames: Set<String>): OriginRead =",
                "originReadOf(headers, authservNamesFor(credentials, ctx.session.apiUrl))",
                "originReadOf(headers = null, trustedNames = emptySet())",
                "byId.mapValues { (_, headers) -> originReadOf(headers.map { it.name to it.value }, trusted) }",
            ),
            codeLines(DaoQuerySource.mailSource("MailRepository")).filter { "originReadOf(" in it },
        )
        assertEquals(
            listOf("val trusted = authservNamesFor(ctx.credentials, ctx.session.apiUrl)"),
            codeLines(DaoQuerySource.mailSource("MailRepository")).filter { it.startsWith("val trusted =") },
        )
    }

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }
}
