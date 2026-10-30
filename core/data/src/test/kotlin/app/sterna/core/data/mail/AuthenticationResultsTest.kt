package app.sterna.core.data.mail

import app.sterna.core.data.mail.AuthenticationResults.DKIM
import app.sterna.core.data.mail.AuthenticationResults.DMARC
import app.sterna.core.data.mail.AuthenticationResults.SPF
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AuthenticationResults.failuresOf], executed: which `Authentication-Results` instance is believed
 * (RFC 8601 § 5), and which results amount to a failure worth saying (#276).
 */
class AuthenticationResultsTest {

    private val trusted = setOf("mail.example.org")

    private fun ar(value: String) = "Authentication-Results" to value

    /** The decoys, before and after: present in every fixture. */
    private fun around(vararg fields: Pair<String, String>): List<Pair<String, String>> =
        listOf("Received" to "from relay.example.test", "X-Spam-Score" to "0.1") +
            fields.toList() +
            listOf("From" to "ann@example.net", "Subject" to "Invoice")

    private fun failures(vararg fields: Pair<String, String>) =
        AuthenticationResults.failuresOf(around(*fields), trusted)

    /**
     * The attack the filter exists for: a sender writes a `pass` for the server's own name below
     * the server's real instance. The topmost trusted instance wins, the forged one is never read.
     */
    @Test fun `an instance forged below the server's own is ignored`() {
        assertEquals(
            listOf(DMARC),
            failures(
                ar("mail.example.org; dmarc=fail header.from=example.net; dkim=pass; spf=pass"),
                ar("mail.example.org; dmarc=pass header.from=example.net"),
            ),
        )
    }

    /** And the other way round: a forged `fail` below a real `pass` alarms nobody. */
    @Test fun `a forged failure below a real pass says nothing`() {
        assertEquals(
            emptyList<String>(),
            failures(
                ar("mail.example.org; dmarc=pass; dkim=pass; spf=pass"),
                ar("mail.example.org; dmarc=fail"),
            ),
        )
    }

    /** A relay's instance above the server's is somebody else's opinion, skipped not believed. */
    @Test fun `an untrusted instance above the trusted one is skipped`() {
        assertEquals(
            emptyList<String>(),
            failures(
                ar("relay.example.test; dmarc=fail; dkim=fail; spf=fail"),
                ar("mail.example.org; dmarc=pass"),
            ),
        )
    }

    @Test fun `an authserv-id under a trusted domain is trusted`() {
        assertEquals(
            listOf(DMARC),
            AuthenticationResults.failuresOf(
                around(ar("MX1.Example.ORG.; dmarc=fail")),
                setOf("example.org"),
            ),
        )
    }

    /** A name that only ends with the same letters is not a subdomain. */
    @Test fun `a lookalike suffix is not a subdomain`() {
        assertEquals(
            emptyList<String>(),
            AuthenticationResults.failuresOf(around(ar("badexample.org; dmarc=fail")), setOf("example.org")),
        )
    }

    /** Gmail writes `mx.google.com`: nothing we know, so nothing said rather than something wrong. */
    @Test fun `an unknown authserv-id says nothing`() {
        assertEquals(
            emptyList<String>(),
            failures(ar("mx.google.com; dmarc=fail (p=REJECT) header.from=example.net; dkim=fail; spf=fail")),
        )
    }

    @Test fun `comments and a version are no obstacle`() {
        assertEquals(
            listOf(DKIM, SPF),
            failures(
                ar(
                    "mail.example.org 1 (Stalwart; checked); dkim/1=fail (bad signature; sorry)\r\n" +
                        "\theader.d=example.net; spf=fail (sender IP is 192.0.2.1) smtp.mailfrom=example.net",
                ),
            ),
        )
    }

    /** A `;` inside a quoted reason does not open a new result. */
    @Test fun `a quoted reason holding a semicolon stays one result`() {
        assertEquals(
            emptyList<String>(),
            failures(ar("mail.example.org; dkim=pass reason=\"ok; dmarc=fail\"; spf=pass")),
        )
    }

    @Test fun `none alone is no method`() {
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; none")))
    }

    @Test fun `dmarc fail is a failure whatever else passed`() {
        assertEquals(listOf(DMARC), failures(ar("mail.example.org; dkim=pass; spf=pass; dmarc=fail")))
    }

    /** A DMARC verdict other than fail or none is the server's last word: `pass` covers a dkim fail. */
    @Test fun `dmarc pass silences a dkim failure`() {
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; dkim=fail; spf=fail; dmarc=pass")))
    }

    /** Any DMARC verdict but fail or none is the last word, an unfinished one included. */
    @Test fun `a dmarc temperror, permerror or policy silences failing dkim and spf`() {
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; dmarc=temperror; dkim=fail; spf=fail")))
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; dmarc=permerror; dkim=fail; spf=fail")))
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; dmarc=policy; dkim=fail; spf=fail")))
    }

    /** The mailing list case: the list rewrote the body, broke DKIM, and its own SPF passes. */
    @Test fun `dkim fail with spf pass and no dmarc says nothing`() {
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; dkim=fail; spf=pass")))
    }

    @Test fun `dkim fail with no spf pass and no dmarc is a failure`() {
        assertEquals(listOf(DKIM), failures(ar("mail.example.org; dkim=fail; spf=neutral")))
        assertEquals(listOf(DKIM, SPF), failures(ar("mail.example.org; spf=fail; dkim=fail; dmarc=none")))
    }

    @Test fun `spf fail alone with no dmarc is a failure`() {
        assertEquals(listOf(SPF), failures(ar("mail.example.org; spf=fail")))
    }

    /** "Not verified" is not "failed". */
    @Test fun `softfail alone says nothing`() {
        assertEquals(emptyList<String>(), failures(ar("mail.example.org; spf=softfail")))
        assertEquals(
            emptyList<String>(),
            failures(ar("mail.example.org; dkim=temperror; spf=permerror; dmarc=none")),
        )
    }

    @Test fun `one passing dkim signature out of two says nothing`() {
        assertEquals(
            emptyList<String>(),
            failures(ar("mail.example.org; dkim=fail header.d=list.example; dkim=pass header.d=example.net; spf=neutral")),
        )
    }

    @Test fun `the header name is matched whatever its casing, and only that name`() {
        assertEquals(listOf(DMARC), failures("authentication-RESULTS" to "mail.example.org; dmarc=fail"))
        assertEquals(
            emptyList<String>(),
            failures("ARC-Authentication-Results" to "i=1; mail.example.org; dmarc=fail"),
        )
    }

    @Test fun `no header at all says nothing`() {
        assertEquals(emptyList<String>(), failures())
    }
}
