package app.sterna.docs

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The store cuts a changelog at [LIMIT] characters (fdroidserver's `char_limits.whatsNew`), in the
 * middle of whatever word sits there, and says so nowhere: the release looks fine here and reads
 * as a half sentence on the phone. The full account of a release is the forge's release notes; the
 * fastlane file carries the short version of it.
 *
 * This lint holds the files written from [FIRST_CAPPED_CODE] on. The changelogs already published
 * keep the text they shipped with, so the rule cannot be satisfied by rewriting history.
 */
class StoreChangelogLengthTest {

    /** The lint: today's fastlane tree. */
    @Test
    fun `every changelog written from the capped versionCode on fits the store's window`() {
        val dirs = changelogDirs()
        assertTrue(
            "no locale changelog directory under $CHANGELOG_ROOT — were the store files moved? " +
                "This lint reads them as text and has nothing to measure otherwise.",
            dirs.isNotEmpty(),
        )

        val over = dirs.flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .filter { it.extension == "txt" && (it.nameWithoutExtension.toIntOrNull() ?: 0) >= FIRST_CAPPED_CODE }
            .filter { overLimit(it.readText()) }
            .sortedBy { it.path }

        if (over.isNotEmpty()) {
            fail(
                "the store shows the first $LIMIT characters of a changelog and drops the rest " +
                    "mid word:\n" +
                    over.joinToString("\n") { "  ${it.relativeTo(DeclarationSource.ROOT)}: ${length(it.readText())} characters" } +
                    "\nShorten the FILE, not this test: a handful of lines naming what changed, " +
                    "for someone deciding whether to update. The exhaustive account goes in the " +
                    "release notes published on the forge, which have no such window.",
            )
        }
    }

    /**
     * The positive case. Without it, a sweep that stops finding files leaves the lint above
     * comparing an empty list to an empty list, green for as long as it is broken.
     */
    @Test
    fun `the measurement is executed on pinned text`() {
        assertFalse("$LIMIT characters is the last accepted length", overLimit("a".repeat(LIMIT)))
        assertTrue("one character past the window is over it", overLimit("a".repeat(LIMIT + 1)))
        assertFalse("a trailing newline is not text the store shows", overLimit("a".repeat(LIMIT) + "\n"))
        assertEquals("length is counted in characters, not in bytes", 3, length("éàü"))
    }

    private fun changelogDirs(): List<File> =
        File(DeclarationSource.ROOT, CHANGELOG_ROOT).listFiles().orEmpty()
            .map { File(it, "changelogs") }
            .filter { it.isDirectory }
            .sortedBy { it.path }

    private fun overLimit(text: String): Boolean = length(text) > LIMIT

    /** What the store counts: the text itself, trailing whitespace of the file left out. */
    private fun length(text: String): Int = text.trim().length

    private companion object {
        const val CHANGELOG_ROOT = "fastlane/metadata/android"

        /** fdroidserver truncates `whatsNew` here. */
        const val LIMIT = 500

        /** 174 (1.5.4) and everything before it shipped with long notes and stay as published. */
        const val FIRST_CAPPED_CODE = 175
    }
}
