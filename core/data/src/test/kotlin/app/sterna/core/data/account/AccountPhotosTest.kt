package app.sterna.core.data.account

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The account photo files, executed on a temporary directory: what a change of photo and a removal
 * delete, and what a stored record without the field reads as. The calls `AccountStore` makes are
 * pinned next door by [AccountPhotoStoreWiringTest], the store itself cannot be built on the JVM.
 */
class AccountPhotosTest {

    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { temp.newFolder("account-photos") }
    private val files by lazy { AccountPhotoFiles(dir) }

    private fun account(id: String, photo: String?) =
        StoredAccount(id = id, server = "imap.example.test", username = "$id@example.test", photo = photo)

    private fun onDisk(): Set<String> = dir.list().orEmpty().toSet()

    @Test fun `choosing a new photo deletes the file of the one it replaces`() {
        val old = files.write(byteArrayOf(1))
        val other = files.write(byteArrayOf(2))
        val before = listOf(account("a", old), account("b", other))
        var saved: List<StoredAccount>? = null
        val new = files.write(byteArrayOf(3))

        files.repoint(before, emptyMap(), "a", new) { saved = it }

        assertEquals("the record must point at the new photo", listOf(new, other), saved?.map { it.photo })
        assertEquals(
            "the replaced photo stays on the phone for ever, one more file at every change; " +
                "another account's photo must not go with it",
            setOf(new, other),
            onDisk(),
        )
    }

    @Test fun `removing the photo deletes its file and gives the letter back`() {
        val old = files.write(byteArrayOf(1))
        var saved: List<StoredAccount>? = null

        files.repoint(listOf(account("a", old)), emptyMap(), "a", null) { saved = it }

        assertEquals(listOf<String?>(null), saved?.map { it.photo })
        assertEquals("a removed photo must not stay on the phone", emptySet<String>(), onDisk())
    }

    @Test fun `a photo still named by a remembered share survives the change`() {
        val shared = files.write(byteArrayOf(1))
        val memory = mapOf("login" to listOf(account("pruned", shared)))

        files.repoint(listOf(account("a", shared)), memory, "a", null) {}

        assertEquals(setOf(shared), onDisk())
    }

    @Test fun `an account removed takes its photo, a remaining one and a remembered one keep theirs`() {
        val before = listOf(account("gone", "g.png"), account("kept", "k.png"))
        val memory = mapOf("login" to listOf(account("pruned", "m.png")))
        assertEquals(
            setOf("g.png"),
            photosLeftBehind(before, memory, before.filter { it.id == "kept" }, memory),
        )
        assertEquals(
            "a remembered share forgotten with its removed login takes its photo too",
            setOf("g.png", "m.png"),
            photosLeftBehind(before, memory, before.filter { it.id == "kept" }, emptyMap()),
        )
    }

    @Test fun `a stored name holding a path reaches nothing outside the directory`() {
        assertNull(files.fileOf("../sterna.db"))
        assertNull(files.fileOf("/data/data/app.sterna/shared_prefs/sterna_account.xml"))
        assertNull(files.fileOf(""))
        assertEquals(File(dir, "x.png"), files.fileOf("x.png"))
    }

    @Test fun `an account stored before the photo existed still reads, with no photo`() {
        // The store's own parser, pinned against the source by StoredAccountReferenceRecordTest.
        val json = Json { ignoreUnknownKeys = true }
        val stored = """[{"id":"a","server":"imap.example.test","username":"a@example.test","color":-16711936}]"""
        assertEquals(
            "a record written without the photo key must decode, or the whole account list reads empty",
            listOf("a" to null),
            json.decodeFromString<List<StoredAccount>>(stored).map { it.id to it.photo },
        )
    }
}
