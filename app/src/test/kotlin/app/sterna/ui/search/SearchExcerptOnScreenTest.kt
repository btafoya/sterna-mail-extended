package app.sterna.ui.search

import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.Email
import app.sterna.ui.components.rowPreviewText
import app.sterna.ui.inbox.SearchUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The passage a server search found, from the repository's answer to the line under a result, on
 * the one surface that draws server results, the inbox's search bar.
 */
class SearchExcerptOnScreenTest {

    @Test fun `each excerpt lands under its row's own key, the same id in two accounts included`() {
        val a = Email(id = "M42", accountId = "accA")
        val b = Email(id = "M42", accountId = "accB")
        val result = MailSearchResult(
            emails = listOf(a, b),
            excerpts = mapOf(("accA" to "M42") to "passage in A", ("accB" to "M42") to "passage in B"),
        )

        val byKey = excerptsByResultKey(result)

        assertEquals("passage in A", byKey[searchResultKey(a)])
        assertEquals("passage in B", byKey[searchResultKey(b)])
        assertEquals(2, byKey.size)
    }

    @Test fun `new criteria in the inbox bar drop the previous search's excerpts, a chip alone included`() {
        val searched = SearchUi(active = true, criteria = SearchQuery(text = "invoice"), excerpts = mapOf("accA|M42" to "the invoice"))

        val next = searched.forCriteria(SearchQuery(text = "receipt"))
        assertEquals("receipt", next.query)
        assertTrue(next.excerpts.isEmpty())

        assertTrue(searched.forCriteria(SearchQuery(text = "invoice", unread = true)).excerpts.isEmpty())
    }

    @Test fun `closing the inbox bar leaves no excerpt behind`() {
        assertTrue(SearchUi().excerpts.isEmpty())
        assertTrue(SearchUi(active = true).excerpts.isEmpty())
    }

    @Test fun `the excerpt takes the preview's place when there is one`() {
        assertEquals("the invoice is due", rowPreviewText(preview = "usual preview", excerpt = "the invoice is due"))
    }

    @Test fun `without an excerpt the row keeps its usual preview`() {
        assertEquals("usual preview", rowPreviewText(preview = "usual preview", excerpt = null))
        assertEquals("usual preview", rowPreviewText(preview = "usual preview", excerpt = " "))
        assertNull(rowPreviewText(preview = null, excerpt = null))
        assertNull(rowPreviewText(preview = "  ", excerpt = ""))
        assertEquals("the invoice", rowPreviewText(preview = null, excerpt = "the invoice"))
    }

    // ---- the wiring the JVM cannot run: the ViewModels and the composables ----

    @Test fun `the inbox bar takes the server leg's excerpts and resets them with each change of criteria`() {
        val run = read("app/src/main/kotlin/app/sterna/ui/inbox/BarSearch.kt")
        val body = run.substring(run.indexOf("fun ask("), run.indexOf("suspend fun refreshLocal("))
        assertEquals("both branches of ask start from forCriteria", 2, Regex("""state\.value\.forCriteria\(criteria\)""").findAll(body).count())
        assertTrue(body.contains("excerpts = excerptsByResultKey(found),"))
        val vm = read("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue(vm.contains("searchExcerpts = search.excerpts,"))
    }

    @Test fun `the bar hands each row its own excerpt, and the row draws it in the preview slot`() {
        val inbox = read("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertTrue(inbox.contains("excerpt = if (fromSearch) ui.searchExcerpts[searchResultKey(email)] else null,"))
        assertTrue(inbox.contains("excerpt = excerpt,"))
        val row = read("app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt")
        assertTrue(row.contains("rowPreviewText(email.preview, excerpt)?.let"))
    }

    @Test fun `the bar's words reach every search row, and only search rows, and the row highlights them`() {
        val inbox = read("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertTrue(inbox.contains("val searchWords = remember(ui.searchQuery) { searchTerms(ui.searchQuery) }"))
        assertTrue(inbox.contains("searchTerms = if (fromSearch) searchWords else emptyList(),"))
        assertTrue(inbox.contains("searchTerms = searchTerms,"))
        val row = read("app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt")
        assertTrue(row.contains("searchTerms: List<String> = emptyList(),"))
        assertTrue(row.contains("highlightTerms(preview, searchTerms, SpanStyle(color = accent, fontWeight = FontWeight.Bold))"))
        assertTrue(row.contains("text = shown,"))
    }

    private fun read(relative: String): String {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
