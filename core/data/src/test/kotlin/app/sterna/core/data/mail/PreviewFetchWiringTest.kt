package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the preview fetch is PLUGGED IN — read from the shipped source, because `ImapMailService`
 * cannot be built on the JVM (its constructor takes an `OAuthTokenRefresher`, which takes an
 * `AccountStore`, which needs a `Context` and a DataStore).
 *
 * Everything this path DECIDES is executed elsewhere and is not restated here: which section is
 * read ([app.sterna.core.imap.firstTextPart], executed in `:core:imap`), what the command says
 * (`BodyPeekOnTheWireTest`, off a real socket), and what the octets become (`BodyPreviewTest`).
 * What is left is the wiring, and it is exactly the part a green suite would not notice going
 * wrong. Whole lines, compared — never a substring search.
 */
class PreviewFetchWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("ImapMailService", function)

    /** Every CODE line of [function]'s body, in order, trimmed — comments and blank lines dropped.
     *  Unlike [codeLinesNaming] this keeps the lines nobody thought to name. */
    private fun codeLinesOf(function: String): List<String> =
        bodyOf(function).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * ⭐⛔ THE WHOLE BODY, LINE FOR LINE, IN ORDER — the only assertion here that can see a line
     * that was ADDED.
     *
     * Every other test in this file filters the body by a needle it names, so it judges the lines
     * it already expects and is blind to a new one. That is not theoretical: inserting
     * `session.setFlag(uid, "\\Seen", true)` inside the block below marks every announced message
     * READ on the server, on every device the account is open on, with no way back — and it carries
     * none of the needles, so it left this suite entirely green.
     *
     * This is a last resort and it is read as source text, because `ImapMailService` cannot be
     * built on the JVM. It is deliberately brittle: a line that changes here must be changed here
     * too, and that is the point — nothing may be added to this function silently.
     */
    @Test fun `the preview path is exactly these lines, and no others`() {
        val lines = codeLinesOf("fetchPreview")

        // The lookup itself fails loudly (mailFunctionBody errors when the function is gone), and
        // a body that is not a block would leave nothing to compare — say so rather than pass.
        assertEquals("the body must be a block, or this test is comparing nothing", "{", lines.firstOrNull())
        assertEquals(
            "no line may be added to the preview path without being written down here — an extra " +
                "command in this block runs against the user's mailbox on every notification",
            listOf(
                "{",
                "if (part == null) return null",
                "return try {",
                "onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->",
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
                "}",
                "} catch (cancelled: CancellationException) {",
                "throw cancelled",
                "} catch (failed: Throwable) {",
                "android.util.Log.w(\"ImapPreview\", \"no preview for \$mailboxId:\$uid: \${failed.message}\")",
                "null",
                "}",
                "}",
            ),
            lines,
        )
    }

    /**
     * ⛔ The read is BOUNDED, and the bound is a parameter with no default.
     *
     * Without it `onMailbox` takes `budgetMs = 0`, which is `ImapBudget.NO_BUDGET`, which is
     * `soTimeout = 0` — block until the OS gives up. And it cannot be repaired from the outside:
     * the block is blocking with no suspension point and holds the account's mutex, so a
     * `withTimeout` around the call would cancel the coroutine while the thread stayed on the socket
     * read, with the mutex taken and every other IMAP operation on that account behind it.
     */
    @Test fun `the preview read carries a deadline, and cannot be called without one`() {
        assertEquals(
            "the budget must reach onMailbox — a call without it blocks for ever on a silent server",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(bodyOf("fetchPreview"), "onMailbox("),
        )
        // No default value: a caller cannot forget the deadline, because there is nothing to forget.
        val signature = DaoQuerySource.mailSource("ImapMailService")
            .substringAfter("suspend fun fetchPreview(")
            .substringBefore("): String?")
        assertEquals(
            "budgetMs must have no default: a defaulted deadline is a deadline a caller drops by " +
                "writing nothing at all",
            listOf("budgetMs: Int,"),
            signature.lines().map { it.trim() }.filter { it.startsWith("budgetMs") },
        )
    }

    /**
     * ⛔ Through `onMailbox`, which is the chokepoint that SELECTs the folder and refuses a UID
     * whose numbering has changed (Codeberg #99). `withSession` would fetch just as well and skip
     * the check — and a UID from a renumbered folder names ANOTHER message, whose opening line
     * would then be posted as this notification's.
     */
    @Test fun `the preview is read through the numbering chokepoint, never around it`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the fetch must go through onMailbox: it is what SELECTs the folder and refuses a " +
                "renumbered one before a single UID goes out",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(body, "onMailbox("),
        )
        assertEquals(
            "and never around it — withSession fetches without checking the numbering",
            emptyList<String>(),
            codeLinesNaming(body, "withSession("),
        )
    }

    /**
     * ⛔ The bounded, peeked read, with the section the SERVER named — and no other fetch. Reaching
     * for `fetchSource` here would pull the whole message (megabytes, in a foreground service) to
     * show two lines of it, and `fetchSection` would pull the whole part.
     */
    @Test fun `the preview reads one bounded section, and nothing else`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the section comes from what the envelope fetch learned, and the read is bounded by " +
                "IMAP_PREVIEW_FETCH_BYTES",
            listOf(
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
            ),
            codeLinesNaming(body, "fetchSectionPartial("),
        )
        assertEquals(
            "no whole-message read on the notification path",
            emptyList<String>(),
            codeLinesNaming(body, "fetchSource(") + codeLinesNaming(body, "fetchSection(uid"),
        )
    }

    /**
     * ⛔ No text part means no round trip AT ALL. A message that is one PDF has no opening line;
     * connecting to learn that costs a LOGIN and a SELECT per notification, for an answer already
     * in hand.
     */
    @Test fun `a message with no text part is answered without touching the server`() {
        assertEquals(
            "the null part must short-circuit before the session is taken",
            listOf("if (part == null) return null"),
            codeLinesNaming(bodyOf("fetchPreview"), "part == null"),
        )
    }

    /**
     * A preview is decoration on a notification whose job is to say that mail arrived: every
     * failure costs the preview and nothing else. A cancellation is not a failure — it means the
     * caller is gone — and must go through.
     */
    @Test fun `a failed preview costs the preview, and a cancellation still propagates`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "a cancellation must be re-thrown, never turned into 'no preview'",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            codeLinesNaming(body, "cancelled"),
        )
        assertEquals(
            "and every other failure yields no preview rather than taking the notification down",
            listOf("} catch (failed: Throwable) {"),
            codeLinesNaming(body, "catch (failed"),
        )
    }

    /**
     * ⛔ THE ONE THING THAT MUST NOT HAPPEN: persisting it. Writing the fetched preview into the
     * cache would light up the message list for exactly the messages that happened to be notified
     * and leave every other row blank — a list that is half true, which is worse than one that
     * never promises an opening line.
     *
     * Read over the whole file rather than one function: the claim is that NO line of this layer
     * writes a preview, and only a file-wide read can state that.
     */
    @Test fun `the IMAP cache still stores no preview at all`() {
        assertEquals(
            "the IMAP cache row must keep writing a null preview: the fetched one is transient",
            listOf("preview = null,"),
            codeLinesNaming(DaoQuerySource.mailSource("ImapMailService"), "preview ="),
        )
    }
}
