package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, a last resort: `MailRepository` cannot run an IMAP push pass on the JVM. The union
 * itself is executed by [ImapRetainedBaselineTest]; this pins that the IMAP branch of
 * `refreshAccountFolders` hands the notifier that union, read off the cache after the upsert and
 * over the same candidate window as the JMAP branch's `notifyRead`.
 */
class ImapRetainedBaselineWiringTest {

    @Test fun `the IMAP push pass remembers the page widened by the cache's candidate window`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")
        val code = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        assertEquals(
            "the baseline must be imapRetainedBaseline(watchedBaselineIds(load), the cached ids " +
                "received since notifyCandidateFloor(now) in that folder): the page alone forgets " +
                "the 51st message, and a deletion then makes it ring as new",
            listOf(
                "val retained = imapRetainedBaseline(",
                "watchedBaselineIds(load),",
                "emailDao.idsReceivedSince(credentials.id, load.mailboxId, notifyCandidateFloor(System.currentTimeMillis())),",
                "load.mailboxId, load.name, load.role, page, retained,",
            ),
            code.filter {
                it.startsWith("val retained") || it == "watchedBaselineIds(load)," ||
                    "idsReceivedSince(credentials.id, load.mailboxId" in it || it.startsWith("load.mailboxId, load.name")
            },
        )
        val upsert = code.indexOfFirst { it.startsWith("emailDao.upsertAll(") }
        val read = code.indexOfFirst { "idsReceivedSince(credentials.id, load.mailboxId" in it }
        assertEquals("the cache is read before this pass's rows are written", true, upsert in 0 until read)
    }
}
