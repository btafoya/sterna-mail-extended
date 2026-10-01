package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentMetaTest {

    @Test fun `kind and size are joined by a middle dot`() {
        assertEquals("PDF · 2 KB", attachmentMeta("PDF", 2048))
    }

    @Test fun `an unknown size leaves the kind alone, without a dangling dot`() {
        assertEquals("PDF", attachmentMeta("PDF", 0))
        assertEquals("PDF", attachmentMeta("PDF", -1))
    }

    @Test fun `an unknown kind leaves the size alone`() {
        assertEquals("512 B", attachmentMeta("", 512))
    }

    @Test fun `nothing known says nothing`() {
        assertEquals("", attachmentMeta("", 0))
    }

    @Test fun `kilobytes round to the nearest, so the rows add up to the total`() {
        assertEquals("4 KB", formatSize(4_009))
        assertEquals("3 KB", formatSize(3_009))
        assertEquals("1 KB", formatSize(1_500))
        assertEquals("2 KB", formatSize(1_536))
        assertEquals("8 KB", formatSize(8_004))
        assertEquals("195 KB", formatSize(200_003))
        assertEquals("199 KB", formatSize(204_012))
    }

    @Test fun `a size that would round to 1024 KB is said in megabytes`() {
        assertEquals("1023 KB", formatSize(1_048_063))
        assertEquals("1.0 MB", formatSize(1_048_064))
        assertEquals("1.0 MB", formatSize(1_048_576))
    }

    @Test fun `bytes stay exact below one kilobyte`() {
        assertEquals("1023 B", formatSize(1_023))
        assertEquals("1 KB", formatSize(1_024))
    }
}
