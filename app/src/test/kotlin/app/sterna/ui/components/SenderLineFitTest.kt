package app.sterna.ui.components

import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour test: it executes the two decisions the sender line of a list row is drawn with. */
class SenderLineFitTest {

    @Test fun `the address shows when name, gap and address fill the slot exactly`() {
        assertTrue(
            "120 + 8 + 72 is exactly the 200 px offered: everything fits whole, so the address shows",
            senderLineShowsAddress(availablePx = 200, nameWidthPx = 120, addressWidthPx = 72, gapPx = 8),
        )
    }

    @Test fun `one pixel short and the address is left out`() {
        assertFalse(
            "120 + 8 + 73 is one px over the 200 offered: the address would be cut, so it is left out",
            senderLineShowsAddress(availablePx = 200, nameWidthPx = 120, addressWidthPx = 73, gapPx = 8),
        )
    }

    @Test fun `the gap counts against the slot`() {
        assertTrue(
            "with no gap, 120 + 80 fills the 200 px",
            senderLineShowsAddress(availablePx = 200, nameWidthPx = 120, addressWidthPx = 80, gapPx = 0),
        )
        assertFalse(
            "the same pair with an 8 px gap is 208 px and must not fit",
            senderLineShowsAddress(availablePx = 200, nameWidthPx = 120, addressWidthPx = 80, gapPx = 8),
        )
    }

    @Test fun `a slot of no width shows no address`() {
        assertFalse(
            "a 0 px slot holds no address, however short",
            senderLineShowsAddress(availablePx = 0, nameWidthPx = 0, addressWidthPx = 1, gapPx = 0),
        )
        assertFalse(
            "nor a name and its gap",
            senderLineShowsAddress(availablePx = 0, nameWidthPx = 10, addressWidthPx = 10, gapPx = 8),
        )
    }

    @Test fun `only a sender with both a name and an address gets the address beside the name`() {
        assertEquals(
            "ada@example.org",
            listAddressBesideName(EmailAddress("Ada", "ada@example.org"), rowShowsRecipients = false),
        )
        assertNull(
            "a nameless sender already shows their address in the name slot: no second copy",
            listAddressBesideName(EmailAddress(null, "ada@example.org"), rowShowsRecipients = false),
        )
        assertNull(
            "a blank name is no name: `display()` falls back to the address there too",
            listAddressBesideName(EmailAddress("  ", "ada@example.org"), rowShowsRecipients = false),
        )
        assertNull("no address, nothing to add", listAddressBesideName(EmailAddress("Ada", ""), rowShowsRecipients = false))
        assertNull("no address, nothing to add", listAddressBesideName(EmailAddress("Ada", " "), rowShowsRecipients = false))
        assertNull("no sender at all", listAddressBesideName(null, rowShowsRecipients = false))
        assertNull(
            "a name that is the address would show it twice",
            listAddressBesideName(EmailAddress("bob@x.org", "bob@x.org"), rowShowsRecipients = false),
        )
        assertNull(
            "the name equals the address once trimmed and case-folded",
            listAddressBesideName(EmailAddress("  Bob@X.org ", "bob@x.org"), rowShowsRecipients = false),
        )
        assertEquals(
            "only equality drops the address: a name that merely contains it keeps it",
            "bob@x.org",
            listAddressBesideName(EmailAddress("Bob (bob@x.org)", "bob@x.org"), rowShowsRecipients = false),
        )
    }

    @Test fun `a row of a sending folder never gets an address, even when it falls back to the sender`() {
        assertNull(
            "in Sent and Drafts a row shows \"To: …\" or, with no recipient yet, the sender who is yourself",
            listAddressBesideName(EmailAddress("Ada", "ada@example.org"), rowShowsRecipients = true),
        )
    }
}
