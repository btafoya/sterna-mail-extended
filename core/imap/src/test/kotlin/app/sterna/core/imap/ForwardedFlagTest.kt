package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `\Answered` and `$Forwarded` read off a folder page (#259), against a scripted server on
 * loopback. `$Forwarded` is a keyword (RFC 5550), not a system flag: no backslash.
 */
class ForwardedFlagTest {

    private val flagsByUid = mapOf(
        1L to "\\Seen",
        2L to "\\Seen \\Answered",
        3L to "\\Seen \$Forwarded",
        4L to "\\answered \$forwarded",
        5L to "\\Seen \\Forwarded Forwarded",
    )

    private fun folderServer() = FakeImapServer { tag, line ->
        val flagsOf = { uid: Long -> flagsByUid.getValue(uid) }
        when {
            line.startsWith("SELECT") -> selectResponse(tag, exists = flagsByUid.size)
            line.startsWith("UID SEARCH ALL") -> searchResponse(tag, flagsByUid.keys.toList())
            line.startsWith("SEARCH UNSEEN") -> searchResponse(tag, listOf(4L))
            line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line), flags = flagsOf) { false }
            line.startsWith("FETCH") -> fetchResponse(tag, flagsByUid.keys.toList(), flags = flagsOf) { false }
            else -> ok(tag)
        }
    }

    @Test
    fun `each message says whether it was answered and whether it was forwarded`() {
        folderServer().use { server ->
            val page = server.session().use { session ->
                val status = session.select("INBOX")
                session.fetchPage(status.exists, offset = 0, limit = 50)
            }

            assertEquals(
                "compared without regard to case as every flag is; a backslashed or bare " +
                    "`Forwarded` is not the keyword",
                mapOf(
                    1L to (false to false),
                    2L to (true to false),
                    3L to (false to true),
                    4L to (true to true),
                    5L to (false to false),
                ),
                page.associate { it.uid to (it.answered to it.forwarded) },
            )
        }
    }
}
