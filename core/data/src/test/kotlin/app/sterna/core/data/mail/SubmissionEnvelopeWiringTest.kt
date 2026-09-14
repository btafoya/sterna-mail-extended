package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * This test reads source text, the last resort, exactly as [DraftAddressingWiringTest] does:
 */
class SubmissionEnvelopeWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailSender", function)

    /** The code lines of [body] naming [needle], comments dropped, so prose can neither satisfy
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

    private fun codeLinesFrom(body: String, first: String, last: String): List<String> {
        val code = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val start = code.indexOfFirst { it.startsWith(first) }
        if (start < 0) return emptyList()
        val end = (start until code.size).firstOrNull { code[it].startsWith(last) } ?: return code.drop(start)
        return code.subList(start, end + 1)
    }

    @Test fun onlyTheAbsenceOfAnEnvelopeAddressCanWithholdTheEnvelope() {
        assertEquals(
            "the envelope must be built whenever choose named an address, on that condition and " +
                "no other: any narrowing of choice.envelopeMailFrom (a ?.takeIf, an extra flag) " +
                "brings #172 back for ordinary accounts. The one widening is the pre-built send " +
                "with a blind copy, whose imported bytes carry no Bcc: and whose hidden recipients " +
                "only the envelope can name; it takes the submitting identity's address.\nFound:",
            listOf(
                "val mailFrom = choice.envelopeMailFrom",
                "?: identity.email.takeIf { prebuiltEntity != null && bccAddrs.isNotEmpty() }",
                "val envelope = mailFrom?.let {",
            ),
            codeLinesFrom(bodyOf("performDelivery"), "val mailFrom =", "val envelope ="),
        )
        assertEquals(
            "choice.envelopeMailFrom must be read on that one line and nowhere else in performDelivery",
            listOf("val mailFrom = choice.envelopeMailFrom"),
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
