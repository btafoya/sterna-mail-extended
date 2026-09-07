package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `draftFlagIsCarried`, EXECUTED: the read the composer's fifth leg is fed, with the server side
 * played by a lambda. False is the answer that spares a message, so every unknown must reach it.
 */
class DraftFlagIsCarriedTest {

    @Test fun onJmapTheAnswerIsTheKeywordTheDraftItselfCarries() {
        assertTrue(carried(MailProtocol.JMAP, jmap = true))
        assertFalse(
            "a JMAP message without the \$draft keyword is not a draft, whatever folder it sits in",
            carried(MailProtocol.JMAP, jmap = false),
        )
    }

    @Test fun onJmapNothingIsAskedOfTheImapSide() {
        var reads = 0
        val answer = runBlocking {
            draftFlagIsCarried(MailProtocol.JMAP, jmapFlag = true) { reads++; false }
        }

        assertTrue(answer)
        assertEquals("the JMAP branch must not open an IMAP connection to answer", 0, reads)
    }

    @Test fun onImapTheAnswerIsTheOneTheServerFetchGaveBack() {
        assertTrue(carried(MailProtocol.IMAP, imap = { true }))
        assertFalse(carried(MailProtocol.IMAP, imap = { false }))
    }

    @Test fun onImapTheJmapKeywordIsNotConsulted() {
        assertFalse(
            "an IMAP row carries no keywords, so its isDraft is a hard-coded false — reading it " +
                "here instead of the fetch would keep every IMAP draft undestroyed forever, and " +
                "reading it as a `true` default would destroy every received mail filed in Drafts",
            carried(MailProtocol.IMAP, jmap = true, imap = { false }),
        )
    }

    @Test fun aServerThatAnswersNothingReadableSparesTheMessage() {
        assertFalse(
            "null is `no readable answer`, never `not a draft`: it must not license a destroy",
            carried(MailProtocol.IMAP, imap = { null }),
        )
    }

    @Test fun aFailedReadSparesTheMessage() {
        assertFalse(carried(MailProtocol.IMAP, imap = { throw java.io.IOException("connection reset") }))
        assertFalse(carried(MailProtocol.IMAP, imap = { error("Not an IMAP message.") }))
    }

    @Test fun aCancellationTravelsOnThroughInsteadOfBeingReadAsAnUnknown() {
        val thrown = runCatching {
            runBlocking {
                draftFlagIsCarried(MailProtocol.IMAP, jmapFlag = false) {
                    throw CancellationException("account gone")
                }
            }
        }.exceptionOrNull()

        assertTrue(
            "AccountGoneException IS a CancellationException: swallowed here, the job that was " +
                "cancelled keeps running. Got $thrown",
            thrown is CancellationException,
        )
    }

    private fun carried(
        protocol: MailProtocol,
        jmap: Boolean = false,
        imap: suspend () -> Boolean? = { null },
    ): Boolean = runBlocking { draftFlagIsCarried(protocol, jmap, imap) }
}
