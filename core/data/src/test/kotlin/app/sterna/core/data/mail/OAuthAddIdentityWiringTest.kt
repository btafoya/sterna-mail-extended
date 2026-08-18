package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. The decision itself — which account is already installed —
 * is EXECUTED next door by `AccountIdentityTest` (`accountKeyOf`, `resolveExistingLogin`). This
 * only reads the plug that carries it into the two OAuth add paths, because `MailRepository` needs
 * Room, an Android `Context` and a live session and cannot be built in a JVM test.
 *
 * What it exists for. Both paths hand the store an account and then tell the screen whether THIS
 * add is what created it. Get that backwards and the sentence is inverted on every single OAuth
 * sign-in: a first, genuine add says "Account already set up. Connection refreshed.", and a
 * re-add — the defect the whole branch is about, seen on the bench — says "Account added" over an
 * account nobody added. Nothing else in the suite would go red: no JVM test builds this class.
 *
 * ⛔ Whole trimmed lines, as a CONTIGUOUS block, never `contains`. A fragment check is blind to
 * everything that lengthens the line, and `… == null || true` is exactly that.
 */
class OAuthAddIdentityWiringTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }

    private fun assertAsks(function: String, why: String, expected: List<String>) {
        val start = bodyLines(function).indexOf(expected.first())
        val found = if (start < 0) emptyList() else bodyLines(function).drop(start).take(expected.size)
        assertEquals(
            "MailRepository.$function must ask the shared identity, BEFORE the store writes, " +
                "whether this add creates the account — on the very values it then hands to " +
                "addOAuth. $why",
            expected, found,
        )
    }

    /**
     * JMAP: the endpoint is the host, the username is the address the request carried. Asked
     * before `accountStore.addOAuth`, because afterwards the answer is always "it exists" — the
     * store has just refreshed the login in place.
     */
    @Test fun `the JMAP OAuth add asks whether the account is already installed`() {
        assertAsks(
            "addOAuthAccount",
            "Asked after the store, or on the wrong pair, and a re-add of the same mailbox ends " +
                "the way a first sign-in does: nothing said, and one row on a phone where the " +
                "reader believes she just added a second account.",
            listOf(
                "val created = resolveExistingLogin(",
                "accountStore.accounts(),",
                """accountKeyOf(MailProtocol.JMAP, host, "", email),""",
                ") == null",
            ),
        )
    }

    /**
     * IMAP: an IMAP account is keyed on its IMAP host (its `server` is blank), and on the address
     * the `id_token` named — the one the store is given — never the alias the user typed.
     */
    @Test fun `the Microsoft OAuth IMAP add asks on the values the store is given`() {
        assertAsks(
            "addOAuthImapAccount",
            "Keyed on the typed address or on a blank server, this path answers about an account " +
                "that is not the one it is about to write, and the Outlook sign-in toast then " +
                "says the opposite of what happened.",
            listOf(
                "val created = resolveExistingLogin(",
                "accountStore.accounts(),",
                """accountKeyOf(MailProtocol.IMAP, "", provider.imap.host, username),""",
                ") == null",
            ),
        )
    }

    /**
     * ⛔ And the answer must leave the function. `return created` is the whole reason these two
     * stopped returning `Unit`; dropped for a literal, the screens above are told the same thing
     * every time and the guard is decorative.
     */
    @Test fun `both OAuth adds return what they found, not a literal`() {
        val offenders = listOf("addOAuthAccount", "addOAuthImapAccount").filterNot { function ->
            bodyLines(function).contains("return created")
        }
        assertEquals(
            "each OAuth add path must end on `return created`. Returning a literal tells every " +
                "screen above the same story on every sign-in — a first add reported as a re-add, " +
                "or a re-add reported as a creation.",
            emptyList<String>(), offenders,
        )
    }
}
