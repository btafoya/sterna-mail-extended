package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CryptoRouteTest] executes `cryptoRoute`; `decryptMessage` needs a fetched message no JVM test
 * provides, so this pins that it asks the route with these arguments, after detection, that no engine
 * is consulted before it, and what each route becomes.
 */
class SmimeRouteCallSiteTest {

    private val body = DaoQuerySource.mailFunctionBody("MailRepository", "decryptMessage")
        .lines().map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test fun `the engines are read before any fetch, then by the route after detection`() {
        val early = "if (notAvailableBeforeFetch(lockedKind, pgpEngine)) return PgpResult.NotAvailable"
        val route = "val route = cryptoRoute(envelope?.kind, lockedKind, pgpEngine, smimeEngine)"
        assertEquals(
            "decryptMessage may name an engine on these lines only. Lines found:",
            listOf(early, route),
            body.filter { "pgpEngine" in it || "smimeEngine" in it },
        )
        assertEquals("the early answer must come before the cache and any fetch", early, body[1])
        val detection = body.indexOfFirst { "MimeParser.detectCrypto(raw)" in it }
        assertTrue("the route must follow detection: $body", detection >= 0 && detection < body.indexOf(route))
    }

    @Test fun `each route becomes this result`() {
        assertEquals(
            "decryptMessage must map the routes on exactly these lines. Lines found:",
            listOf(
                "CryptoRoute.OpenPgpMissing -> return PgpResult.NotAvailable",
                "CryptoRoute.NothingFound -> return PgpResult.Error(\"No OpenPGP content found\")",
                "CryptoRoute.Unverifiable -> return PgpResult.Success(unverifiedBody(credentials, emailId, email))",
                "is CryptoRoute.Smime -> smimeVerify(route.engine, envelope!!, sender, keep)",
                "is CryptoRoute.SmimeEncrypted -> smimeDecrypt(credentials, route.engine, envelope!!, sender, keep)",
                "is CryptoRoute.OpenPgp -> route.engine.let { pgp ->",
            ),
            body.filter { "CryptoRoute." in it },
        )
    }

    @Test fun `the S-MIME engine gets the canonical signed octets and the DER`() {
        assertEquals(
            "smimeVerified must hand the engine these arguments. Lines found:",
            listOf(
                "CryptoKind.SMIME_OPAQUE_SIGNED -> engine.verifyOpaque(envelope.signedDataDer!!, sender)",
                "else -> engine.verifyDetached(",
                "canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),",
                "envelope.signatureDer!!,",
            ),
            DaoQuerySource.mailFunctionBody("SmimeDecrypt", "smimeVerified")
                .lines().map { it.trim() }
                .filter { "engine.verify" in it || "!!" in it },
        )
        assertEquals(
            "a signature on its own says nothing of encryption. Lines found:",
            listOf("val checked = withContext(Dispatchers.Default) { smimeVerified(engine, envelope, sender, wasEncrypted = false, found = found) }"),
            DaoQuerySource.mailFunctionBody("MailRepository", "smimeVerify")
                .lines().map { it.trim() }
                .filter { "smimeVerified(" in it },
        )
    }

    @Test fun `what both S-MIME routes find is kept against this message`() {
        assertEquals(
            "decryptMessage must hand both routes this keeper. Lines found:",
            listOf("val keep: (SmimeCertificateFound) -> Unit = { keepSmimeCertificate(credentials, emailId, email, it) }"),
            body.filter { "keepSmimeCertificate" in it },
        )
        assertEquals(
            "the envelope's signature must reach the same keeper. Lines found:",
            listOf("found = found,"),
            DaoQuerySource.mailFunctionBody("MailRepository", "smimeDecrypt").lines().map { it.trim() }.filter { "found" in it && "->" !in it },
        )
    }
}
