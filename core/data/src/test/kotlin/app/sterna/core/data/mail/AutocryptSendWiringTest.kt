package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH arguments `MailRepository` hands [app.sterna.core.data.account.autocryptHeaderValue] at each
 * of its three send call sites.
 *
 * A source lint, and a last resort — `MailRepository` needs a `Context`, Room and the Keystore, so
 * no JVM test instantiates it. It is the companion of `AutocryptHeaderValueTest`, which EXECUTES the
 * rule: that one proves the rule is right, this one proves the rule is asked the right question. A
 * perfect rule fed `credentials.username` instead of the message's actual `From` would answer "yes,
 * announce" on every delegated send, and every test of the rule would still be green.
 *
 * The arguments are extracted by matching parentheses, not by reading whole lines: reformatting the
 * call must not turn this red, and changing what is passed must.
 */
class AutocryptSendWiringTest {

    private val source = DaoQuerySource.mailSource("MailRepository")

    /** The argument text of every `autocryptHeaderValue(…)` call, whitespace collapsed. */
    private fun callArguments(): List<String> {
        val out = mutableListOf<String>()
        var at = source.indexOf(NAME)
        while (at >= 0) {
            var i = at + NAME.length
            var depth = 1
            val start = i
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out += source.substring(start, i - 1).replace(Regex("\\s+"), " ").trim().trimEnd(',').trim()
            at = source.indexOf(NAME, i)
        }
        return out
    }

    @Test fun `the three send call sites, and their arguments`() {
        assertEquals(
            listOf(
                // IMAP/SMTP submission (and the Sent APPEND, which reuses this same message):
                // `outgoing` builds the From from fromEmail, falling back to the account's own
                // address, and the rule is asked about that very address.
                "sendingAccount, fromEmail?.takeIf { it.isNotBlank() } ?: credentials.username",
                // JMAP with a pre-built entity — a signed or encrypted send, whose raw message is
                // built by OutgoingMime.build.
                "sendingAccount, from.email",
                // JMAP, the ordinary send.
                "sendingAccount, from.email",
            ),
            callArguments(),
        )
    }

    @Test fun `the account handed over is the one this send belongs to`() {
        assertEquals(
            listOf("val sendingAccount = accountStore.account(credentials.id)"),
            source.lines().map { it.trim() }.filter { it.startsWith("val sendingAccount") },
        )
    }

    @Test fun `from is still the address the correspondent will see`() {
        // `from.email` only means what this test claims while THIS is how `from` is built: the
        // message's own sender address, trimmed — not the identity the submission runs under, which
        // on a delegated send belongs to the login.
        assertTrue(
            source.lines().map { it.trim() }.contains(
                "val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail.trim())",
            ),
        )
    }

    private companion object {
        const val NAME = "autocryptHeaderValue("
    }
}
