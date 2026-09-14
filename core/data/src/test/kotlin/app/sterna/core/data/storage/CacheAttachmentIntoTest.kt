package app.sterna.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The attachment cache is flat and shared by every message of every account, and the name on disk
 * is the name a third-party viewer is shown through `FileProvider`. A `content://` already handed
 * out must keep reading the bytes it was handed for, so two attachments with the same name must
 * land in two files.
 */
class CacheAttachmentIntoTest {

    @get:Rule val temp = TemporaryFolder()

    private fun write(dir: File, name: String?, text: String): File =
        cacheAttachmentInto(dir, name, text.toByteArray())

    private fun namesIn(dir: File): Set<String> = dir.listFiles()!!.map { it.name }.toSet()

    @Test fun `two attachments with the same name land in two files, the first untouched`() {
        val dir = temp.newFolder("attachments")

        val first = write(dir, "facture.pdf", "invoice of message A")
        val second = write(dir, "facture.pdf", "invoice of message B")

        assertEquals("facture.pdf", first.name)
        assertEquals(
            "the first attachment was overwritten by a second one of the same name: a viewer " +
                "holding its content:// now reads another message's bytes",
            "invoice of message A",
            first.readText(),
        )
        assertEquals("facture-2.pdf", second.name)
        assertEquals("invoice of message B", second.readText())
        assertEquals(setOf("facture.pdf", "facture-2.pdf"), namesIn(dir))
    }

    @Test fun `a third homonym takes the next number and keeps its extension`() {
        val dir = temp.newFolder("attachments")

        write(dir, "facture.pdf", "one")
        write(dir, "facture.pdf", "two")
        val third = write(dir, "facture.pdf", "three")

        assertEquals("facture-3.pdf", third.name)
        assertEquals("three", third.readText())
        assertEquals(setOf("facture.pdf", "facture-2.pdf", "facture-3.pdf"), namesIn(dir))
        assertEquals("one", File(dir, "facture.pdf").readText())
        assertEquals("two", File(dir, "facture-2.pdf").readText())
    }

    @Test fun `two unnamed attachments do not share the default name`() {
        val dir = temp.newFolder("attachments")

        val first = write(dir, null, "unnamed A")
        val second = write(dir, null, "unnamed B")

        assertEquals("attachment", first.name)
        assertEquals("unnamed A", first.readText())
        assertEquals("attachment-2", second.name)
        assertEquals("unnamed B", second.readText())
    }

    @Test fun `the number goes before the last extension only`() {
        val dir = temp.newFolder("attachments")

        write(dir, "README", "a")
        assertEquals("README-2", write(dir, "README", "b").name)

        write(dir, "archive.tar.gz", "a")
        assertEquals("archive.tar-2.gz", write(dir, "archive.tar.gz", "b").name)

        write(dir, ".env", "a")
        assertEquals(
            "a leading dot is part of the name, not an extension",
            ".env-2",
            write(dir, ".env", "b").name,
        )
    }

    // An empty name, `.` and `..` fall back to `attachment`, because as a child of the directory
    // they name the directory itself or its parent.
    @Test fun `a hostile name stays a flat file inside the cache directory`() {
        val dir = temp.newFolder("attachments")
        val canonicalDir = dir.canonicalFile

        val names = listOf("../x/y.pdf", "../x/y.pdf", "..", ".", "")
        val files = names.mapIndexed { i, name -> write(dir, name, "hostile $i") }

        files.forEachIndexed { i, file ->
            assertEquals("${names[i]} escaped the cache directory", canonicalDir, file.canonicalFile.parentFile)
            assertTrue("${names[i]} is not a regular file", file.isFile)
            assertFalse("${file.name} contains a separator", '/' in file.name)
            assertFalse("${file.name} is a relative segment", file.name == ".." || file.name == ".")
            assertEquals("hostile $i", file.readText())
        }
        assertEquals("every hostile write must get its own file", names.size, files.map { it.name }.toSet().size)
        assertTrue("the cache tree grew a sub-directory", dir.listFiles()!!.none { it.isDirectory })
        assertEquals(setOf("attachments"), namesIn(temp.root))
    }

    @Test fun `past the numbered names it falls back to a unique prefix, still overwriting nothing`() {
        val dir = temp.newFolder("attachments")
        File(dir, "x.pdf").writeText("old 1")
        for (n in 2..CACHED_ATTACHMENT_NAME_ATTEMPTS) File(dir, "x-$n.pdf").writeText("old $n")

        val file = write(dir, "x.pdf", "new")

        assertTrue("fallback name was ${file.name}", Regex("""\d+-x\.pdf""").matches(file.name))
        assertEquals("new", file.readText())
        assertEquals(CACHED_ATTACHMENT_NAME_ATTEMPTS + 1, dir.listFiles()!!.size)
        assertEquals("old 1", File(dir, "x.pdf").readText())
        for (n in 2..CACHED_ATTACHMENT_NAME_ATTEMPTS) assertEquals("old $n", File(dir, "x-$n.pdf").readText())
    }

    // ext4 and f2fs limit a name to 255 bytes, and a sanitized name is ASCII, so its length in chars
    // is its length in bytes. Only numbered and prefixed names are bounded; the first try keeps the
    // name as given.
    @Test fun `a numbered name is shortened to fit the file system's 255-byte name limit`() {
        val dir = temp.newFolder("attachments")
        val longName = "a".repeat(251) + ".pdf"
        assertEquals(255, longName.length)

        val first = write(dir, longName, "first")
        val second = write(dir, longName, "second")

        assertEquals(longName, first.name)
        assertEquals("first", first.readText())
        assertTrue("second name is ${second.name.length} long", second.name.length <= 255)
        assertTrue("second name lost its suffix or extension: ${second.name}", second.name.endsWith("-2.pdf"))
        assertEquals("second", second.readText())
    }

    @Test fun `an extension too long to keep is dropped, the number is kept`() {
        val dir = temp.newFolder("attachments")
        val longExtension = "a." + "b".repeat(253)

        write(dir, longExtension, "first")
        val second = write(dir, longExtension, "second")

        assertTrue("second name is ${second.name.length} long", second.name.length <= 255)
        assertTrue("second name lost its number: ${second.name}", second.name.endsWith("-2"))
        assertEquals("second", second.readText())
        assertEquals("first", File(dir, longExtension).readText())
    }

    @Test fun `the fallback name of a long attachment also fits in 255 bytes`() {
        val dir = temp.newFolder("attachments")
        val longName = "a".repeat(251) + ".pdf"
        repeat(CACHED_ATTACHMENT_NAME_ATTEMPTS) { write(dir, longName, "old $it") }

        val file = write(dir, longName, "new")

        assertTrue("fallback name is ${file.name.length} long", file.name.length <= 255)
        assertTrue("fallback name lost its prefix: ${file.name}", Regex("""\d+-a+.*""").matches(file.name))
        assertEquals("new", file.readText())
        assertEquals(CACHED_ATTACHMENT_NAME_ATTEMPTS + 1, dir.listFiles()!!.size)
        assertEquals("old 0", File(dir, longName).readText())
    }

    // Each name is claimed with `createNewFile`, which is atomic on the file system. An `exists()`
    // check followed by a write would let two concurrent writers of one name both pass the check.
    @Test fun `concurrent writers of the same name each get their own file`() {
        val dir = temp.newFolder("attachments")
        val writers = 16
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        try {
            val results = (0 until writers).map { i ->
                pool.submit<Pair<Int, File>> {
                    start.await()
                    i to write(dir, "photo.jpg", "writer $i")
                }
            }
            start.countDown()
            val files = results.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(writers, files.map { it.second.name }.toSet().size)
            files.forEach { (i, file) -> assertEquals("writer $i", file.readText()) }
            assertEquals(writers, dir.listFiles()!!.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
