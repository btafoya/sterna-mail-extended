package app.sterna.ui.compose

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientChipFaultTest {

    private val keyLookups = mutableListOf<String>()
    private val noKey: (String) -> Boolean = { keyLookups += it; true }
    private val hasKey: (String) -> Boolean = { keyLookups += it; false }

    @Test fun `a chip that is not an address is an invalid address`() {
        assertEquals(RecipientChipFault.INVALID_ADDRESS, recipientChipFault("iris@", hasKey))
    }

    @Test fun `a valid address with no key to encrypt to is a missing key`() {
        assertEquals(RecipientChipFault.NO_KEY, recipientChipFault("iris@example.test", noKey))
        assertEquals(listOf("iris@example.test"), keyLookups)
    }

    @Test fun `a valid address with its key has no fault`() {
        assertNull(recipientChipFault("iris@example.test", hasKey))
    }

    @Test fun `an invalid address is said invalid even where no key would be found, and no key is looked up`() {
        assertEquals(RecipientChipFault.INVALID_ADDRESS, recipientChipFault("iris@", noKey))
        assertEquals(
            "a typo is what has to be fixed first: once it is, the key lookup has an address to run on",
            emptyList<String>(),
            keyLookups,
        )
    }

    @Test fun `each fault carries its own words`() {
        assertEquals(R.string.compose_recipient_invalid, RecipientChipFault.INVALID_ADDRESS.message)
        assertEquals(R.string.compose_recipient_no_key, RecipientChipFault.NO_KEY.message)
    }
}
