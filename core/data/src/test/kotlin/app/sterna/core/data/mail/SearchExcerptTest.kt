package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The passage a JMAP server found the search words in, from its raw `SearchSnippet/get` preview
 * (HTML-escaped, `<mark>`-tagged) to the plain line under a result, and the (accountId, id) key it
 * travels under up to the screens.
 */
class SearchExcerptTest {

    // ---- cleaning ----

    @Test fun `the highlight tags are removed and the words they wrapped are kept`() {
        assertEquals("the invoice is due", searchExcerpt("the <mark>invoice</mark> is due"))
    }

    @Test fun `entities are decoded`() {
        assertEquals("Tom & Jerry <b> \"été\"", searchExcerpt("Tom &amp; Jerry &lt;b&gt; &quot;&#233;t&#xE9;&quot;"))
    }

    /** Tags come off before entities are decoded: an escaped tag is text the sender wrote. */
    @Test fun `a mark tag the sender typed as text survives as text`() {
        assertEquals("write <mark> here", searchExcerpt("write &lt;mark&gt; here"))
    }

    @Test fun `bidi and control characters are removed, raw or encoded`() {
        assertEquals("pay moc.knab now", searchExcerpt("pay ‮moc.knab‬ now"))
        assertEquals("pay moc.knab now", searchExcerpt("pay &#x202E;moc.knab&#8236; now"))
    }

    @Test fun `line breaks and runs of spaces fold into one space`() {
        assertEquals("first line second line", searchExcerpt("  first line\n\n   second\tline \n"))
    }

    @Test fun `nothing to show is no excerpt`() {
        assertNull(searchExcerpt(null))
        assertNull(searchExcerpt(""))
        assertNull(searchExcerpt("   "))
        assertNull(searchExcerpt("<mark></mark>"))
        assertNull(searchExcerpt("‮"))
    }

    // ---- keyed on the result it belongs to ----

    @Test fun `each returned email gets its cleaned excerpt under its account and id`() {
        val emails = listOf(Email(id = "e1", accountId = "accA"), Email(id = "e2", accountId = "accA"))

        val excerpts = excerptsOf(
            emails,
            mapOf("e1" to "the <mark>invoice</mark>", "e2" to "<mark></mark>", "gone" to "not a result"),
        )

        assertEquals(mapOf(("accA" to "e1") to "the invoice"), excerpts)
    }

    @Test fun `stamping the account moves the excerpts to the stamped key`() {
        val single = MailSearchResult(
            emails = listOf(Email(id = "e1")),
            excerpts = mapOf((null to "e1") to "the invoice"),
        )

        val owned = single.ownedBy("accA")

        assertEquals(listOf("accA"), owned.emails.map { it.accountId })
        assertEquals(mapOf(("accA" to "e1") to "the invoice"), owned.excerpts)
    }

    /** #92: two accounts of one server can both hold M42, two different messages, two passages. */
    @Test fun `the same id in two accounts keeps both excerpts apart through the merge`() {
        val a = MailSearchResult(
            listOf(Email(id = "M42", accountId = "accA", receivedAt = "2026-07-20T10:00:00Z")),
            excerpts = mapOf(("accA" to "M42") to "passage in A"),
        )
        val b = MailSearchResult(
            listOf(Email(id = "M42", accountId = "accB", receivedAt = "2026-07-21T10:00:00Z")),
            excerpts = mapOf(("accB" to "M42") to "passage in B"),
        )

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)

        assertEquals(
            mapOf(("accA" to "M42") to "passage in A", ("accB" to "M42") to "passage in B"),
            merged.excerpts,
        )
    }

    @Test fun `a result the cap left out takes its excerpt with it`() {
        val a = MailSearchResult(
            listOf(
                Email(id = "a1", accountId = "accA", receivedAt = "2026-07-20T10:00:00Z"),
                Email(id = "a2", accountId = "accA", receivedAt = "2026-07-19T10:00:00Z"),
            ),
            excerpts = mapOf(("accA" to "a1") to "kept", ("accA" to "a2") to "dropped"),
        )

        val merged = mergeAccountSearches(listOf(a), limit = 1)

        assertEquals(listOf("a1"), merged.emails.map { it.id })
        assertEquals(mapOf(("accA" to "a1") to "kept"), merged.excerpts)
    }

    /** The count's "at least N" is about the results; having passages or not says nothing about it. */
    @Test fun `excerpts never move the completeness verdict`() {
        val emails = listOf(Email(id = "a1", accountId = "accA", receivedAt = "2026-07-20T10:00:00Z"))
        listOf(true, false).forEach { complete ->
            val without = mergeAccountSearches(listOf(MailSearchResult(emails, complete)), limit = 10)
            val with = mergeAccountSearches(
                listOf(MailSearchResult(emails, complete, excerpts = mapOf(("accA" to "a1") to "x"))),
                limit = 10,
            )
            assertEquals(complete, without.complete)
            assertEquals(without.complete, with.complete)
        }
    }

    // ---- the wiring, read out of the shipped repository ----

    /**
     * `MailRepository` cannot be built on the JVM. What cannot be called is pinned with its
     * arguments: the excerpts are keyed on the emails actually returned, after their folder is
     * resolved, and every account's answer is stamped by [ownedBy], never by a bare `copy`.
     */
    @Test fun `the repository keys the server's snippets on the returned emails and stamps them per account`() {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun search(credentials: AccountCredentials")
        val unified = source.indexOf("suspend fun search(accounts:", start)
        val end = source.indexOf("suspend fun fetchEmail(", unified)
        check(start >= 0 && unified > start && end > unified) { "MailRepository's two search entry points moved" }
        val single = source.substring(start, unified)
        val wrapper = source.substring(unified, end)

        assertTrue(
            "the JMAP answer no longer carries excerptsOf(resolved, hits.snippetPreviews)",
            single.contains("excerpts = excerptsOf(resolved, hits.snippetPreviews)"),
        )
        assertEquals("both unified branches must stamp through ownedBy", 2, Regex("""\.ownedBy\(\w+\.id\)""").findAll(wrapper).count())
        assertFalse("a bare accountId copy would leave the excerpts under a null account", wrapper.contains("it.copy(accountId ="))
    }

    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
