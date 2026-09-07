package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sourceCouldNotBeRead], EXECUTED — the verdict standing between an IMAP source that never came
 * back and a composer that would open blank, look usable, and destroy the server original on send.
 *
 * Every case below is a hand-written pair (input → verdict): the test never recomputes the rule it
 * is checking, so inverting the shipped function, or weakening it to `isEmpty()`, has to turn a
 * line red here.
 */
class SourceCouldNotBeReadTest {

    /** THE case: `ImapClient.bodyItem` answers "" when the FETCH carried no body item. */
    @Test fun anEmptySourceCouldNotBeRead() {
        assertTrue(
            "an empty source is a message that did not come back, never an empty draft",
            sourceCouldNotBeRead(""),
        )
    }

    /**
     * The case that forbids `isEmpty()`, and it is a DELIBERATE over-refusal, not an observed
     * producer: every producer known today hands back exactly "" (`ImapParser.readLiteral` returns
     * "" after draining an oversized literal, ImapParser-151 — it does not leave a CRLF
     * behind). Nothing found so far yields a whitespace-only source; the guard refuses it anyway,
     * because it must stay at least as strong as [receiptRequestedInSource], which treats
     * present-but-unreadable as an unknown, and because a bare line ending names no header and no
     * address. Pinning it here is what keeps a later `isEmpty()` from looking harmless.
     */
    @Test fun aSourceOfNothingButALineEndingCouldNotBeRead() {
        assertTrue(
            "a bare CRLF names no header and no address: unknown, refuse",
            sourceCouldNotBeRead("\r\n"),
        )
    }

    /** The same deliberate over-refusal, in the shapes whitespace actually takes. */
    @Test fun aSourceOfNothingButWhitespaceCouldNotBeRead() {
        for (raw in listOf("   \r\n\t ", " ", "\n", "\t", "\r\n\r\n")) {
            assertTrue("raw = \"$raw\" carries no message at all", sourceCouldNotBeRead(raw))
        }
    }

    /** A real message: headers and a body. Nothing here may be refused. */
    @Test fun aRealSourceCanBeRead() {
        assertFalse(
            sourceCouldNotBeRead(
                "Subject: lunch\r\n" +
                    "From: ann@example.org\r\n" +
                    "To: bo@example.org\r\n" +
                    "\r\n" +
                    "one o'clock?\r\n",
            ),
        )
    }

    /**
     * THE control that forbids refusing too much: a draft with an empty text body is ordinary
     * mail — a subject and recipients typed, nothing written yet — and it must keep opening. A
     * guard that judged the parsed body instead of the source would swallow this one, and every
     * such draft would become unopenable in exchange for the defect above.
     */
    @Test fun headersAloneWithAnEmptyBodyCanBeRead() {
        assertFalse(
            "headers with an empty text body are a legitimate draft, not an unknown",
            sourceCouldNotBeRead("Subject: hi\r\nFrom: a@b.c\r\n\r\n"),
        )
    }

    /** Every stored message carries at least a header block, so one header alone is enough. */
    @Test fun oneHeaderAloneCanBeRead() {
        assertFalse(sourceCouldNotBeRead("Subject: hi"))
    }

    /** Not a test that only ever refuses: the two sides, side by side. */
    @Test fun theVerdictSeparatesTheTwoSides() {
        assertTrue(sourceCouldNotBeRead(""))
        assertFalse(sourceCouldNotBeRead("From: a@b.c\r\n\r\nhi"))
    }
}

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DraftReceiptWiringTest] does:
 * `MailRepository` needs Room, a `Context` and a socket, so no JVM test can call `openEmailImap`.
 * The DECISION is EXECUTED next door by [SourceCouldNotBeReadTest]; what nothing else can see is
 * whether the call site still asks it, with the right ARGUMENT, and EARLY ENOUGH. So whole lines
 * are compared, never a `contains`: `contains` is blind to any mutation that lengthens a line.
 *
 * What it exists for: a draft whose source comes back "" opens blank but usable — subject and
 * recipients repainted from the cache — and `reopenedDraftIsLossy` is handed `(false, 0, 0, null)`,
 * i.e. "a plain draft", which authorises destroying the server original behind an empty message.
 * Each mutation below, alone, brings that loss back while every executable test stays green:
 * - the refusal deleted, or moved BELOW `MimeParser.parseBody(raw)` where the parse of "" has
 *   already produced the empty body the composer will show;
 * - `sourceCouldNotBeRead(raw)` → `sourceCouldNotBeRead(body.text)`, which refuses every draft
 *   whose text body is empty and lets the unreadable source through;
 * - the refusal NESTED inside `if (markRead) { … }`, which spares the composer's own path
 *   exactly — `fetchEmail` (the reply/forward/draft-reopen door) calls this function with
 *   `markRead = false` — and reopens the defect whole;
 * - an `.ifBlank { … }` hung on the fetch line itself, which would turn the unknown back into a
 *   value before the guard ever sees it.
 *
 * The trimmed-line assertions cannot see either of the last two: [codeLinesNaming] trims, so
 * nesting is invisible to it by construction, and no assertion looked at the fetch line at all.
 * Hence the two below that read the RAW indentation and the whole fetch line.
 */
class SourceRefusalWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theOpenAsksTheDecisionAboutTheRawSourceItJustFetched() {
        assertEquals(
            "the argument is the SERVER's raw source — `body.text` would refuse legitimate " +
                "drafts with an empty body and let an unreadable source through",
            listOf("if (sourceCouldNotBeRead(raw)) {"),
            codeLinesNaming(bodyOf("openEmailImap"), "sourceCouldNotBeRead("),
        )
    }

    @Test fun theRefusalComesBeforeTheSourceIsParsed() {
        val lines = bodyOf("openEmailImap").lines().map { it.trim() }
        val refusal = lines.indexOf("if (sourceCouldNotBeRead(raw)) {")
        val parse = lines.indexOf("val body = MimeParser.parseBody(raw)")
        assertTrue("the refusal must be in openEmailImap at all", refusal >= 0)
        assertTrue("the parse must still be in openEmailImap: $lines", parse >= 0)
        assertTrue(
            "the refusal must come BEFORE the parse (refusal at $refusal, parse at $parse): " +
                "parsing first produces the very empty body the blank composer then shows",
            refusal < parse,
        )
    }

    /**
     * The line right UNDER the guard, and nothing else. A "does openEmailImap contain a
     * `throw MessageUnavailableException(` somewhere" check is worthless here: the function
     * carried two of them before this work, so it was green on `main` and would stay green with
     * the guard deleted. What has to be pinned is the throw THIS guard raises —
     * `MessageUnavailableException` is reused on purpose, because the reader
     * (status_message_unavailable) and the composer (compose_draft_load_failed) already turn it
     * into a translated sentence, so the fix moves no string and no translation. A
     * `ContentTooLargeException` here would say "too large" to the user and tell the composer
     * something else entirely.
     */
    @Test fun theRefusalIsRaisedAsTheUnavailableMessageTheReadersAlreadyTranslate() {
        val lines = bodyOf("openEmailImap").lines().map { it.trim() }
        val refusal = lines.indexOf("if (sourceCouldNotBeRead(raw)) {")
        assertTrue("the refusal must be in openEmailImap at all", refusal >= 0)
        assertTrue(
            "the guard's own throw must be MessageUnavailableException, not another kind: " +
                "found \"${lines.getOrNull(refusal + 1)}\"",
            lines.getOrNull(refusal + 1)?.startsWith("throw MessageUnavailableException(") == true,
        )
    }

    /**
     * THE hole every trimmed-line assertion is blind to: wrapping the guard in
     * `if (markRead) { … }` leaves it word for word where it is, one level deeper, and disarms it
     * on the one path that costs the data — `fetchEmail` (MailRepository) opens the
     * composer's copy with `markRead = false`. Indentation is the only witness, and it is compared
     * to the PARSE's own indentation rather than to a literal eight spaces: a reformat of the whole
     * function must not turn this red, only a change of block must.
     */
    @Test fun theRefusalSitsInTheSameBlockAsTheParse() {
        val lines = bodyOf("openEmailImap").lines()
        val guard = lines.first { it.trim() == "if (sourceCouldNotBeRead(raw)) {" }
        val parse = lines.first { it.trim() == "val body = MimeParser.parseBody(raw)" }
        assertEquals(
            "same indentation means same block: nesting the guard inside `if (markRead) { … }` " +
                "would spare the COMPOSER path — fetchEmail passes markRead = false — and reopen " +
                "the whole defect while every trimmed-line assertion stayed green",
            parse.takeWhile { it == ' ' },
            guard.takeWhile { it == ' ' },
        )
    }

    /**
     * And the fetch line itself, whole. Nothing else pinned it, so an `.ifBlank { "" }` — or
     * an `.ifBlank { cachedBodyText }` — hung on the end of it would answer the guard with a value
     * that is no longer blank, and the refusal would never fire on a source that never arrived.
     */
    @Test fun theSourceIsTakenFromTheServerAndHandedOnUntouched() {
        assertEquals(
            "the guard judges what the SERVER answered: any `.ifBlank { … }` or fallback on this " +
                "line turns the unknown into a value before the refusal can see it",
            listOf("val raw = imap.fetchSource(credentials, mailboxId, uid)"),
            codeLinesNaming(bodyOf("openEmailImap"), "imap.fetchSource("),
        )
    }

    /**
     * The size guard is a different answer ("too large" is not "unknown") and stays where it is,
     * after the parse: it reads `body.tooLarge`, which does not exist before `parseBody`.
     */
    @Test fun theSizeGuardIsUntouchedAndStillAsksTheParsedBody() {
        assertEquals(
            listOf("if (body.tooLarge) {"),
            codeLinesNaming(bodyOf("openEmailImap"), "tooLarge"),
        )
    }
}
