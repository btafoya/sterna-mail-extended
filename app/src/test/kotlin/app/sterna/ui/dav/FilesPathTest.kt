package app.sterna.ui.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesPathTest {
    @Test fun `the root lists group empty and a folder lists its own id`() {
        assertEquals("", emptyList<Crumb>().groupId())
        assertEquals("b", listOf(Crumb("a", "A"), Crumb("b", "B")).groupId())
    }

    @Test fun `names are refused for the reasons the server would, case-insensitively`() {
        val taken = listOf("Docs", "notes.txt")
        assertEquals(FileNameProblem.EMPTY, fileNameProblem("  ", taken))
        assertEquals(FileNameProblem.FORBIDDEN_CHAR, fileNameProblem("a/b", taken))
        assertEquals(FileNameProblem.FORBIDDEN_CHAR, fileNameProblem("a\\b", taken))
        assertEquals(FileNameProblem.TOO_LONG, fileNameProblem("x".repeat(256), taken))
        assertEquals(FileNameProblem.TAKEN, fileNameProblem("NOTES.txt", taken))
        assertNull(fileNameProblem("new", taken))
    }

    @Test fun `a node may keep or recase its own name but not take a sibling's`() {
        val siblings = listOf("Docs", "Music")
        assertNull(fileNameProblem("docs", siblings, renaming = "Docs"))
        assertEquals(FileNameProblem.TAKEN, fileNameProblem("Music", siblings, renaming = "Docs"))
    }
}
