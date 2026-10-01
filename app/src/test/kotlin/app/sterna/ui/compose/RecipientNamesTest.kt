package app.sterna.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import app.sterna.contacts.ContactSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RecipientNamesTest {

    private val jeanne = ContactSuggestion(email = "j.dupont@exemple.org", name = "Jeanne Dupont")
    private val book = recipientNamesWithPick(emptyMap(), jeanne)

    @Test fun `a picked address shows its name`() {
        assertEquals("Jeanne Dupont", chipLabel("j.dupont@exemple.org", book))
    }

    @Test fun `an address never picked shows itself`() {
        assertEquals("m.durand@exemple.org", chipLabel("m.durand@exemple.org", book))
    }

    @Test fun `an invalid chip shows what was typed, even when the book holds it`() {
        val typo = mapOf("jeanne" to "Jeanne Dupont", "j.dupont@exemple" to "Jeanne Dupont")
        assertEquals("jeanne", chipLabel("jeanne", typo))
        assertEquals("j.dupont@exemple", chipLabel("j.dupont@exemple", typo))
    }

    @Test fun `the address is matched whatever its case and surrounding spaces`() {
        assertEquals("Jeanne Dupont", chipLabel(" J.Dupont@Exemple.ORG ", book))
        val picked = recipientNamesWithPick(emptyMap(), ContactSuggestion(email = " J.Dupont@Exemple.org", name = "Jeanne"))
        assertEquals("Jeanne", chipLabel("j.dupont@exemple.org", picked))
    }

    @Test fun `a pick without a name writes nothing and keeps an earlier name`() {
        assertEquals(emptyMap<String, String>(), recipientNamesWithPick(emptyMap(), jeanne.copy(name = null)))
        assertEquals(emptyMap<String, String>(), recipientNamesWithPick(emptyMap(), jeanne.copy(name = "  ")))
        assertEquals(book, recipientNamesWithPick(book, jeanne.copy(name = null)))
    }

    @Test fun `a named chip is read name then address, an unnamed one has no description of its own`() {
        assertEquals("Jeanne Dupont, j.dupont@exemple.org", chipDescription("j.dupont@exemple.org", book))
        assertNull(chipDescription("m.durand@exemple.org", book))
        assertNull(chipDescription("jeanne", mapOf("jeanne" to "Jeanne Dupont")))
    }

    @Test fun `the book survives being saved and restored`() {
        val two = recipientNamesWithPick(book, ContactSuggestion(email = "m.durand@exemple.org", name = "Marc, Durand"))
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(recipientNamesSaver) { scope.save(two) }!!
        assertEquals(two, recipientNamesSaver.restore(saved))
    }

    @Test fun `the remove label is formatted with the chip in every language`() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .first { File(it, "values/strings.xml").isFile }
        val dirs = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-nl", "values-pl", "values-pt", "values-ru", "values-zh-rCN")
        val entry = Regex("""<string name="compose_remove_recipient">([^<]*)</string>""")
        for (dir in dirs) {
            val xml = File(res, "$dir/strings.xml").readText()
            val found = entry.findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals("$dir: one compose_remove_recipient", 1, found.size)
            assertEquals("$dir: ${found[0]}", 1, Regex("""%1\${'$'}s""").findAll(found[0]).count())
            assertEquals("$dir: compose_remove stays", 1, Regex("""<string name="compose_remove">""").findAll(xml).count())
        }
    }
}
