package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/** [EmailLabels.union], which the reader's label sheet lists, and [EmailLabels.with], which `setLabel` writes. */
class EmailLabelsUnionTest {

    @Test fun `the union of an account is every id once, sorted with the case ignored`() {
        val blobs = listOf(
            EmailLabels.encode(listOf("work", "Read later")),
            EmailLabels.encode(listOf("archive/2024", "work")),
            EmailLabels.encode(listOf("Banking", "")),
        )

        assertEquals(listOf("archive/2024", "Banking", "Read later", "work"), EmailLabels.union(blobs))
    }

    @Test fun `two ids that differ by case are two labels, the sort alone ignores it`() {
        assertEquals(
            listOf("Work", "work"),
            EmailLabels.union(listOf(EmailLabels.encode(listOf("work")), EmailLabels.encode(listOf("Work")))),
        )
    }

    @Test fun `a blob that cannot be read adds nothing`() {
        assertEquals(listOf("a"), EmailLabels.union(listOf("not json", EmailLabels.encode(listOf("a")))))
    }

    @Test fun `with adds an id once, at the end, and takes it off without touching the others`() {
        assertEquals(listOf("a", "b", "c"), EmailLabels.with(listOf("a", "b"), "c", set = true))
        assertEquals(listOf("a", "b"), EmailLabels.with(listOf("a", "b"), "b", set = true))
        assertEquals(listOf("a", "c"), EmailLabels.with(listOf("a", "b", "c"), "b", set = false))
        assertEquals(listOf("a"), EmailLabels.with(listOf("a"), "z", set = false))
    }
}
