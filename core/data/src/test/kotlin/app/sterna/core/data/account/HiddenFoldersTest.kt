package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-account hide registry (#207), executed: the writes `AccountStore` delegates to, run on
 * real account lists rather than read off the source.
 */
class HiddenFoldersTest {

    private fun account(id: String, folders: Set<String> = emptySet(), inbox: String? = null) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        inboxId = inbox,
        hiddenFolders = folders,
    )

    @Test fun `the hide lands on the named account and on NO other`() {
        val after = withHiddenFolder(
            listOf(account("a"), account("b"), account("c")),
            id = "b",
            folderId = "Travail",
            hidden = true,
        )
        assertEquals(
            "hiding a folder on one account reached another. Written without the `if (it.id == id)` " +
                "guard, one switch hides whatever carries the same id everywhere — and on IMAP the " +
                "ids are PATHS, so a namesake folder on the neighbouring account disappears with " +
                "nobody having asked (#92/#121).",
            listOf(emptySet<String>(), setOf("Travail"), emptySet()),
            after.map { it.hiddenFolders },
        )
        assertEquals("the account list was reordered or lost a record", listOf("a", "b", "c"), after.map { it.id })
    }

    @Test fun `showing a folder again drops its key`() {
        val after = withHiddenFolder(
            listOf(account("a", setOf("Travail", "Perso")), account("b", setOf("Travail"))),
            id = "a",
            folderId = "Travail",
            hidden = false,
        )
        assertEquals(
            "showing a folder again left its key in the registry (so it stays hidden), or it " +
                "un-hid the namesake folder of the neighbouring account (#92/#121).",
            listOf(setOf("Perso"), setOf("Travail")),
            after.map { it.hiddenFolders },
        )
    }

    @Test fun `a rename carries the folder and its descendants, and stops at the delimiter`() {
        listOf("/", ".").forEach { delimiter ->
            val hidden = setOf(
                "Travail",
                "Travail${delimiter}2026",
                "Travailleur",
                "Perso",
            )
            val after = withRenamedHiddenFolder(
                listOf(account("a", hidden)),
                id = "a",
                oldId = "Travail",
                newId = "Boulot",
                delimiter = delimiter,
            ).single()
            assertEquals(
                "re-keying a hidden folder over the delimiter '$delimiter' either lost a descendant " +
                    "or dragged a namesake along: the descendant test must be the full prefix PLUS " +
                    "the delimiter, never bare, or `Travailleur` follows `Travail` and a folder " +
                    "nobody touched is hidden under a path that does not exist.",
                setOf("Boulot", "Boulot${delimiter}2026", "Travailleur", "Perso"),
                after.hiddenFolders,
            )
        }
    }

    @Test fun `a rename copies the record instead of rebuilding it`() {
        val settled = account("a", setOf("mbx-2")).copy(
            watchedFolders = setOf("mbx-7"),
            collapsedFolders = mapOf("mbx-3" to true),
            accountName = "Demo",
        )
        val after = withRenamedHiddenFolder(listOf(settled), id = "a", oldId = "mbx-2", newId = "mbx-9", delimiter = "/")
            .single()
        assertEquals(
            "re-keying a hidden folder rebuilt the record instead of copying it: another setting " +
                "moved. The store saves the account list as ONE blob, so a field dropped here is a " +
                "setting lost on disk.",
            settled.copy(hiddenFolders = setOf("mbx-9")),
            after,
        )
    }

    @Test fun `a delete purges the named account's keys and leaves the neighbour alone`() {
        val after = withoutHiddenFolder(
            listOf(account("a", setOf("Travail", "Travail/2026", "Travailleur")), account("b", setOf("Travail"))),
            id = "a",
            folderIds = listOf("Travail/2026", "Travail"),
        )
        assertEquals(
            "purging the keys of a deleted folder either missed one or reached the neighbouring " +
                "account. The ids come from the delete itself, descendants included; a key left " +
                "behind makes a folder recreated at the same path be born hidden, which is " +
                "invisible and so undiagnosable.",
            listOf(setOf("Travailleur"), setOf("Travail")),
            after.map { it.hiddenFolders },
        )
    }

    @Test fun `an unknown account hides nothing, and a known one answers with its own Inbox`() {
        val accounts = listOf(account("a", setOf("Travail"), inbox = "INBOX"), account("b", setOf("Perso")))
        assertEquals(
            "an id resolving to no record must hide nothing at all.",
            FolderHiding("zz", emptySet(), null),
            folderHidingOf("zz", accounts),
        )
        assertEquals(
            "the registry and the Inbox id must be read off the SAME record, or one account's " +
                "hidden ids are judged against another account's Inbox (#92/#121).",
            FolderHiding("a", setOf("Travail"), "INBOX"),
            folderHidingOf("a", accounts),
        )
    }

    @Test fun `a registry is applied to its own account's list and to no other`() {
        val hiding = FolderHiding("a", setOf("Travail"), "INBOX")
        assertEquals(
            "the registry was applied to a list that is not the account's. The two reach the drawer " +
                "on separate inputs of one combine, so an account switch pairs the new registry with " +
                "the list still on screen, and on IMAP the ids are paths that collide (#92/#121).",
            FolderHiding("b", emptySet(), null),
            hiding.forList("b"),
        )
        assertEquals(
            "the guard emptied a registry that DOES belong to the list on screen, so the switch " +
                "never hides anything at all.",
            hiding,
            hiding.forList("a"),
        )
        assertEquals(
            "a list nobody has loaded yet must not be filtered by the account's registry.",
            FolderHiding(null, emptySet(), null),
            hiding.forList(null),
        )
    }

    // ── what a record already on disk decodes to ────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private val preBranch =
        """[{"id":"a1","server":"imap.example.test","username":"demo@example.test",
           |"accountName":"Demo","protocol":"IMAP","imapHost":"imap.example.test",
           |"smtpHost":"smtp.example.test"}]""".trimMargin().replace("\n", "")

    @Test fun `a record written before this field decodes to nothing hidden`() {
        val decoded = json.decodeFromString<List<StoredAccount>>(preBranch).single()
        assertEquals(
            "an account stored before 'hiddenFolders' existed must decode to an EMPTY registry, " +
                "i.e. every folder drawn exactly as it is today. There is no migration and there " +
                "must not be one.",
            emptySet<String>(),
            decoded.hiddenFolders,
        )
    }

    @Test fun `a stored registry is read back as it was written`() {
        val stored = preBranch.replace(
            "\"id\":\"a1\"",
            "\"id\":\"a1\",\"hiddenFolders\":[\"Travail\",\"Travail/2026\"]",
        )
        val decoded = json.decodeFromString<List<StoredAccount>>(stored).single()
        assertEquals(
            "the key on disk is the field's name; renaming the field drops every user's hidden " +
                "folders, silently, because the parser ignores unknown keys.",
            setOf("Travail", "Travail/2026"),
            decoded.hiddenFolders,
        )
    }
}
