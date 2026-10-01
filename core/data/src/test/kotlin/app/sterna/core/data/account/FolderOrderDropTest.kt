package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A drop of the folder screen's reorder mode, written once: [withFolderDropped] executed against
 * the one-place moves the arrows write, and the store's call to it read off the source, as in
 * [FolderOrderStoreWiringTest] and for the same reason.
 */
class FolderOrderDropTest {

    private fun account(id: String, order: List<String> = emptyList()) = StoredAccount(
        id = id,
        server = "imap.example.test",
        username = "$id@example.test",
        folderOrder = order,
    )

    /** Five siblings, two of them ranked, among ids of another parent: the order a real account
     *  holds after a few moves, where the listing still places the unranked ones. */
    private val siblings = listOf("A", "B", "C", "D", "E")
    private val stored = listOf("Other/x", "D", "Other/y", "B")

    @Test fun `a drop of k places writes exactly the order of k moves in a row`() {
        val shown = siblingsInFolderOrder(stored, siblings)
        val mismatches = shown.indices.flatMap { from -> shown.indices.map { to -> from to to } }
            .mapNotNull { (from, to) ->
                val folder = shown[from]
                var stepped = listOf(account("a", stored))
                repeat(kotlin.math.abs(to - from)) {
                    stepped = withFolderMoved(stepped, "a", siblings, folder, up = to < from)
                }
                val dropped = withFolderDropped(listOf(account("a", stored)), "a", siblings, folder, to)
                val want = stepped.single().folderOrder
                val got = dropped.single().folderOrder
                if (want == got) null else "$folder from $from to $to: arrows $want, drop $got"
            }
        assertEquals(
            "a drop wrote another stored order than the same number of taps on the arrows: the " +
                "folder lands a place away from where it was let go, or the two ways of " +
                "reordering disagree about the fratrie they write.",
            emptyList<String>(),
            mismatches,
        )
    }

    @Test fun `a drop lands on the record it names, never on a namesake of another account`() {
        val after = withFolderDropped(
            listOf(account("a", listOf("Travail", "Perso", "Loisirs")), account("b", listOf("Travail", "Perso", "Loisirs"))),
            id = "b",
            siblings = listOf("Travail", "Perso", "Loisirs"),
            folderId = "Loisirs",
            toIndex = 0,
        )
        assertEquals(
            "dropping 'Loisirs' first on account b did not write it there alone: on IMAP the ids " +
                "are paths, and another account of one server holds the same ones.",
            listOf(listOf("Travail", "Perso", "Loisirs"), listOf("Loisirs", "Travail", "Perso")),
            after.map { it.folderOrder },
        )
    }

    @Test fun `a drop in place, outside the fratrie, or past its end writes nothing`() {
        assertSame("dropped where it stands", stored, folderOrderAfterDrop(stored, siblings, "D", 0))
        assertSame("a folder outside the fratrie", stored, folderOrderAfterDrop(stored, siblings, "Z", 1))
        assertSame("an index past the end", stored, folderOrderAfterDrop(stored, siblings, "A", 5))
        assertSame("a negative index", stored, folderOrderAfterDrop(stored, siblings, "A", -1))
    }

    @Test fun `the store drops under its lock, through the function executed above`() {
        assertEquals(
            "AccountStore.moveFolderTo changed shape. It must hand withFolderDropped the list it " +
                "just read, under @Synchronized, with the index as it was handed: a list computed " +
                "by the caller on an earlier read loses the move before it.",
            listOf(
                "@Synchronized",
                "fun moveFolderTo(id: String, siblings: List<String>, folderId: String, toIndex: Int) {",
                "saveAccounts(withFolderDropped(accounts(), id, siblings, folderId, toIndex))",
                "}",
            ),
            block("fun moveFolderTo("),
        )
    }

    /** The line of [ACCOUNT_STORE] starting with [prefix], the annotation above it and the two
     *  below. Anything but exactly one match returns a message, so the assertion never passes on
     *  nothing. */
    private fun block(prefix: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, ACCOUNT_STORE).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, ACCOUNT_STORE).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val start = code.indices.filter { code[it].startsWith(prefix) }.singleOrNull()
            ?: return listOf("«not exactly one code line starts with `$prefix`»")
        return code.subList(maxOf(start - 1, 0), minOf(start + 3, code.size))
    }

    private companion object {
        const val ACCOUNT_STORE = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
    }
}
