package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * This test reads source text, the last resort, for the reason [DestroyChecksTheFolderWiringTest].
 * The decision itself runs in [LastResortSkipsPinnedFoldersTest]; this only pins that each of the
 * six last resorts of `archive` and `trash` goes through it, arguments included.
 */
class LastResortSkipsPinnedFoldersWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** Code lines of [body], comments dropped and whitespace normalised, compared whole. */
    private fun codeLines(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLines(body).filter { needle in it }

    private val archiveLastResort =
        """?: lastResortImapFolder("archive", "Archive", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }"""
    private val trashLastResort =
        """?: lastResortImapFolder("trash", "Trash", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }"""

    @Test fun `the IMAP archives create Archive only when no other role holds it`() {
        listOf("archive", "archiveAll").forEach { function ->
            val body = bodyOf(function)
            assertEquals(
                "$function: the literal Archive passes through lastResortImapFolder, and a refusal " +
                    "fails the gesture. Body was:\n$body",
                listOf(
                    """val dest = imapRoleFolder(credentials, "archive", "all")""",
                    archiveLastResort,
                    """?: error("No archive folder, and the name Archive is designated for another role")""",
                ),
                codeLines(body).dropWhile { !it.startsWith("val dest = imapRoleFolder(") }.take(3),
            )
            assertEquals(
                "$function: no literal creation may bypass the rule. Body was:\n$body",
                listOf(archiveLastResort),
                codeLinesNaming(body, "imap.createFolder("),
            )
        }
    }

    @Test fun `the IMAP deletes create Trash only when no other role holds it`() {
        listOf("delete", "deleteAll").forEach { function ->
            val body = bodyOf(function)
            assertEquals(
                "$function: the literal Trash passes through lastResortImapFolder, and a refusal " +
                    "fails the gesture. Body was:\n$body",
                listOf(
                    """val trash = imapRoleFolder(credentials, "trash")""",
                    trashLastResort,
                    """?: error("No trash folder, and the name Trash is designated for another role")""",
                ),
                codeLines(body).dropWhile { !it.startsWith("val trash = imapRoleFolder(") }.take(3),
            )
            assertEquals(
                "$function: no literal creation may bypass the rule. Body was:\n$body",
                listOf(trashLastResort),
                codeLinesNaming(body, "imap.createFolder("),
            )
        }
    }

    @Test fun `the JMAP creations' last resort by server role skips pinned folders`() {
        mapOf("createArchiveFolder" to "archive", "createTrashFolder" to "trash").forEach { (function, role) ->
            val body = bodyOf(function)
            assertEquals(
                "$function: the re-read listing's server role is read through " +
                    "serverRoleHolderNotPinnedElsewhere, never raw. Body was:\n$body",
                listOf("""serverRoleHolderNotPinnedElsewhere(mbs, "$role", ctx.credentials.specialFolders)"""),
                codeLinesNaming(body, "it.role ==") +
                    codeLinesNaming(body, "serverRoleHolderNotPinnedElsewhere("),
            )
        }
    }
}
