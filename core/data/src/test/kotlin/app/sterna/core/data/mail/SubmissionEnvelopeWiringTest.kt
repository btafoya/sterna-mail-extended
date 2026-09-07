package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DraftAddressingWiringTest] does:
 * `MailRepository.performDelivery` wants Room, a `Context`, a session and a socket, so no JVM test
 * can run the wiring below. The DECISION it guards is EXECUTED elsewhere — [SubmissionIdentityTest]
 * runs `SubmissionIdentity.choose`, and [app.sterna.core.jmap.EnvelopeOnTheWireTest] reads the
 * octets the envelope becomes on the wire. What is pinned here is only WHERE that decision is
 * plugged in, and it pins the WHOLE LINE of each load-bearing call — `contains` is blind to any
 * mutation that lengthens a line, which is how three of this project's rules were defeated before.
 *
 * What it exists for (#172): a chosen address the server has no identity for used to be swapped in
 * silence for the first server identity, so `smtp.mailfrom` and the `From:` header named two
 * domains and DMARC failed the message. The fix names the envelope instead. Every line below is one
 * that, changed alone, brings back a loss while every executable test stays green:
 * - `rcptTo = recipients.map { … }` (cc and bcc dropped): RFC 8621 §7.5 makes `rcptTo` mandatory
 *   once an envelope is given, so the server does NOT fall back to the message's own recipients —
 *   the copies and blind copies never receive the message, and the send reports success;
 * - `envelope = null` at either call site: that path submits with no envelope, so the PGP/MIME send
 *   (or the ordinary one) leaves again under the wrong envelope, which is the defect itself;
 * - the decision rewritten inline instead of asked of `SubmissionIdentity.choose`: the rule then
 *   lives where nothing can execute it, and inverting it turns nothing red;
 * - `choice.envelopeMailFrom?.let {` narrowed by so much as a `?.takeIf { … }`: THIS is the line
 *   that decides whether an envelope exists at all, and it is the one line of the fix no executable
 *   test can reach — `SubmissionIdentityTest` runs a `choose` this mutation does not touch, and
 *   `EnvelopeOnTheWireTest` is handed an envelope rather than watching one be built. A
 *   `?.takeIf { onBehalf }` here restores the #172 defect for every ordinary account AND poses an
 *   envelope on the delegated path the whole fix is conditioned on avoiding (#31), with the entire
 *   suite green. It was found by counter-expertise, not by the suite; the assertion below is what
 *   closes it.
 */
class SubmissionEnvelopeWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theEnvelopeNamesEveryRecipientTheMessageHas() {
        assertEquals(
            "the envelope's rcptTo must carry to + cc + bcc, like the SMTP path's RCPT TO " +
                "(SmtpClient.kt:334): an amputated list does not fall back to the message's own " +
                "recipients, it silently delivers to nobody but the To (#172)",
            listOf("rcptTo = (recipients + ccAddrs + bccAddrs).map { a -> a.email },"),
            codeLinesNaming(bodyOf("performDelivery"), "rcptTo ="),
        )
    }

    @Test fun bothSubmissionPathsAreHandedTheEnvelope() {
        assertEquals(
            "both submissions must carry it — importAndSendEmail for a PGP/MIME message and " +
                "sendEmail for an ordinary one; a path left without it sends under the identity " +
                "the server picked, which is the defect",
            listOf("envelope = envelope,", "envelope = envelope,"),
            codeLinesNaming(bodyOf("performDelivery"), "envelope = envelope"),
        )
    }

    @Test fun onlyTheAbsenceOfAnEnvelopeAddressCanWithholdTheEnvelope() {
        assertEquals(
            "the envelope must be built whenever choose named an address, on that condition and " +
                "no other: any narrowing here (a ?.takeIf, an extra flag) both brings #172 back " +
                "for ordinary accounts and can pose an envelope on the delegated path (#31), " +
                "while every executable test stays green",
            listOf("val envelope = choice.envelopeMailFrom?.let {"),
            codeLinesNaming(bodyOf("performDelivery"), "choice.envelopeMailFrom"),
        )
    }

    @Test fun theChoiceIsPutToTheFunctionATestCanRun() {
        assertEquals(
            "performDelivery must ask SubmissionIdentity.choose — the pure function a test can " +
                "execute — and hand it the chosen address, the server identities and onBehalf, " +
                "not re-decide it inline where nothing reaches it",
            listOf("val choice = SubmissionIdentity.choose(fromEmail, serverIdentities, onBehalf)"),
            codeLinesNaming(bodyOf("performDelivery"), "SubmissionIdentity.choose("),
        )
    }
}
