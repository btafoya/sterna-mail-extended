package app.sterna.core.data.account

import app.sterna.core.data.settings.SettingsBackup
import app.sterna.core.data.settings.SettingsBackupCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The per-account folder order, executed: the writes `AccountStore` delegates to and the move the
 * folder screen will ask for, run on real lists rather than read off the source.
 */
class FolderOrderTest {

    private fun account(id: String, order: List<String> = emptyList()) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        folderOrder = order,
    )

    // ── the writes ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a rename carries the folder and its descendants in place, and stops at the delimiter`() {
        listOf("/", ".").forEach { delimiter ->
            val order = listOf("Perso", "Travail", "Travailleur", "Travail${delimiter}2026")
            val after = withRenamedFolderOrder(
                listOf(account("a", order), account("b", order)),
                id = "a",
                oldId = "Travail",
                newId = "Boulot",
                delimiter = delimiter,
            )
            assertEquals(
                "re-keying the folder order over '$delimiter' lost the renamed folder itself, lost a " +
                    "descendant, dragged `Travailleur` along with `Travail`, moved an entry out of " +
                    "its place, or reached the neighbouring account. A folder whose id left the " +
                    "registry falls back to the listing's place: the rename silently undoes the " +
                    "order the user chose.",
                listOf(
                    listOf("Perso", "Boulot", "Travailleur", "Boulot${delimiter}2026"),
                    order,
                ),
                after.map { it.folderOrder },
            )
        }
    }

    @Test fun `a rename copies the record instead of rebuilding it`() {
        val settled = account("a", listOf("mbx-2")).copy(
            hiddenFolders = setOf("mbx-7"),
            watchedFolders = setOf("mbx-7"),
            accountName = "Demo",
        )
        val after = withRenamedFolderOrder(listOf(settled), id = "a", oldId = "mbx-2", newId = "mbx-9", delimiter = "/")
            .single()
        assertEquals(
            "re-keying the folder order rebuilt the record instead of copying it: another setting " +
                "moved. The store saves the account list as ONE blob.",
            settled.copy(folderOrder = listOf("mbx-9")),
            after,
        )
    }

    @Test fun `a delete drops the destroyed ids from the named account and leaves the neighbour alone`() {
        val after = withoutFolderOrder(
            listOf(
                account("a", listOf("Travail", "Perso", "Travail/2026", "Travailleur")),
                account("b", listOf("Travail")),
            ),
            id = "a",
            folderIds = listOf("Travail/2026", "Travail"),
        )
        assertEquals(
            "purging a deleted folder from the order either missed an id or reached the " +
                "neighbouring account. A key left behind names a path a new folder can be created " +
                "at, and that folder would be born with the deleted one's place.",
            listOf(listOf("Perso", "Travailleur"), listOf("Travail")),
            after.map { it.folderOrder },
        )
    }

    // ── the move ────────────────────────────────────────────────────────────────────────────────

    @Test fun `siblings the registry knows come first in its order, the others after in listing order`() {
        assertEquals(
            "an id absent from the registry must keep the listing's order and come after every " +
                "ordered one; a fresh account (empty registry) must be drawn exactly as listed.",
            listOf("C", "A", "B", "D"),
            siblingsInFolderOrder(listOf("X", "C", "Y", "A"), listOf("B", "A", "D", "C")),
        )
        assertEquals(listOf("B", "A"), siblingsInFolderOrder(emptyList(), listOf("B", "A")))
    }

    @Test fun `moving up swaps with the sibling shown above and pins the whole fratrie`() {
        val after = folderOrderAfterMove(
            order = listOf("Other/x", "A"),
            siblings = listOf("A", "B", "C"),
            folderId = "C",
            up = true,
        )
        assertEquals(
            "moving `C` up past `B` must draw it between `A` and `B`.",
            listOf("A", "C", "B"),
            siblingsInFolderOrder(after, listOf("A", "B", "C")),
        )
        assertEquals(
            "the move must keep the other parent's ids and write the whole fratrie in its new " +
                "order, `B` included although only the listing had placed it.",
            listOf("Other/x", "A", "C", "B"),
            after,
        )
    }

    @Test fun `moving down swaps with the sibling shown below`() {
        val after = folderOrderAfterMove(
            order = listOf("B", "A"),
            siblings = listOf("A", "B", "C"),
            folderId = "B",
            up = false,
        )
        assertEquals(listOf("A", "B", "C"), siblingsInFolderOrder(after, listOf("A", "B", "C")))
    }

    @Test fun `the first cannot go up nor the last down, and an unknown folder moves nothing`() {
        val order = listOf("B", "A")
        val siblings = listOf("A", "B", "C")
        assertSame("the first sibling moved up", order, folderOrderAfterMove(order, siblings, "B", up = true))
        assertSame("the last sibling moved down", order, folderOrderAfterMove(order, siblings, "C", up = false))
        assertSame("a folder outside the fratrie moved", order, folderOrderAfterMove(order, siblings, "Z", up = true))
    }

    // ── what a record on disk and a backup decode to ────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private val preBranch =
        """[{"id":"a1","server":"imap.example.test","username":"demo@example.test",
           |"accountName":"Demo","protocol":"IMAP","hiddenFolders":["Travail"]}]""".trimMargin().replace("\n", "")

    @Test fun `a record written before this field decodes to the listing's order`() {
        assertEquals(
            "an account stored before 'folderOrder' existed must decode to an EMPTY order, i.e. " +
                "every folder drawn exactly as it is today.",
            emptyList<String>(),
            json.decodeFromString<List<StoredAccount>>(preBranch).single().folderOrder,
        )
    }

    @Test fun `a settings backup carries the order out and back, and an older one reads without it`() {
        val backup = SettingsBackup(accounts = listOf(account("a", listOf("Perso", "Travail"))))
        val back = SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup))
        assertEquals(
            "the chosen order did not survive a settings export then import: the key on disk is " +
                "the field's name, and the codec ignores unknown keys, so a rename loses it silently.",
            listOf("Perso", "Travail"),
            back?.accounts?.single()?.folderOrder,
        )
        val older = SettingsBackupCodec.decode("""{"version":1,"accounts":$preBranch}""")
        assertEquals(
            "a backup written before 'folderOrder' existed must still import, with no order.",
            listOf(emptyList<String>()),
            older?.accounts?.map { it.folderOrder },
        )
    }
}
