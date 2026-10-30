package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [senderUnverifiedMethods], executed: whether the open message gets the unverified-sender strip,
 * and which checks it names (#276). The field carries the lowercase method names the data layer
 * writes; the strip shows the acronyms.
 */
class SenderUnverifiedMethodsTest {

    /** Evaluated, nothing failed: the overwhelming case, and it must stay silent. */
    @Test fun `an empty verdict draws no strip`() {
        assertNull(senderUnverifiedMethods(emptyList()))
    }

    /** Not evaluated: a body cached before the check existed, or a header read that failed. */
    @Test fun `an unknown verdict draws no strip`() {
        assertNull(senderUnverifiedMethods(null))
    }

    @Test fun `one failed method is named as its acronym`() {
        assertEquals("DMARC", senderUnverifiedMethods(listOf("dmarc")))
        assertEquals("SPF", senderUnverifiedMethods(listOf("spf")))
    }

    @Test fun `three failed methods are named together`() {
        assertEquals("DMARC, DKIM, SPF", senderUnverifiedMethods(listOf("dmarc", "dkim", "spf")))
    }

    /** The order is the strip's own, whatever order the field holds them in. */
    @Test fun `the methods are named in a fixed order`() {
        assertEquals("DMARC, DKIM, SPF", senderUnverifiedMethods(listOf("spf", "dkim", "dmarc")))
        assertEquals("DKIM, SPF", senderUnverifiedMethods(listOf("spf", "dkim")))
    }

    /** A name the strip does not know is not shown raw, and alone it draws nothing. */
    @Test fun `an unknown method name is not shown`() {
        assertEquals("DKIM", senderUnverifiedMethods(listOf("arc", "dkim")))
        assertNull(senderUnverifiedMethods(listOf("arc")))
    }
}
