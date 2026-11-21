package app.sterna.ui.dav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardJsonTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val stored = obj(
        """{"@type":"Card","version":"1.0","uid":"u","addressBookIds":{"b1":true},
        "name":{"full":"Ann Lee","components":[{"kind":"given","value":"Ann"}]},
        "emails":{"e1":{"address":"ann@x.test","contexts":{"work":true}},"e2":{"address":"ann@y.test"}},
        "phones":{"p1":{"number":"+1 555"}},
        "addresses":{"a1":{"components":[{"kind":"locality","value":"Paris"},{"kind":"country","value":"France"}]}},
        "x-vendor:flag":7}"""
    )

    @Test fun `a new card is typed, versioned, in one book, and drops blank lines`() {
        val card = newCard(ContactForm("Bob", listOf("b@x.test", " "), listOf("1"), emptyList(), "b9"))
        assertEquals(obj("""{"@type":"Card","version":"1.0","addressBookIds":{"b9":true},"name":{"full":"Bob"},"emails":{"e1":{"address":"b@x.test"}},"phones":{"p1":{"number":"1"}}}"""), card)
    }

    @Test fun `an untouched form patches nothing`() {
        assertTrue(patchOf(stored, formOf(stored)).isEmpty())
    }

    @Test fun `only changed keys are sent, ids and unknown entry properties survive, a cleared list is null`() {
        val form = formOf(stored).copy(name = "Anna Lee", emails = listOf("ann@x.test", "anna@y.test"), phones = emptyList())
        val patch = patchOf(stored, form)
        assertEquals(setOf("name", "emails", "phones"), patch.keys)
        assertEquals(obj("""{"full":"Anna Lee"}"""), patch["name"])
        assertEquals(obj("""{"e1":{"address":"ann@x.test","contexts":{"work":true}},"e2":{"address":"anna@y.test"}}"""), patch["emails"])
        assertEquals(JsonNull, patch["phones"])
        assertFalse("addresses" in patch || "x-vendor:flag" in patch)
    }

    @Test fun `an address shown from its components is kept until its line is edited`() {
        assertEquals(listOf("Paris, France"), formOf(stored).addresses)
        val patch = patchOf(stored, formOf(stored).copy(addresses = listOf("1 Rue X, Paris")))
        assertEquals(obj("""{"a1":{"full":"1 Rue X, Paris"}}"""), patch["addresses"])
    }

    @Test fun `search finds a name or any line`() {
        assertTrue(matches(stored, "ANNA@") .not() && matches(stored, "ann@y") && matches(stored, "paris") && matches(stored, ""))
    }
}
