package app.sterna.core.data.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHICH arguments `MailRepository` hands the Autocrypt import at each of its three call sites, and
 * that the JMAP read still asks the server for nothing new.
 *
 * ⛔ A source lint, and a last resort — `MailRepository` needs a `Context`, Room and the Keystore, so
 * no JVM test instantiates it. Its companion is [AutocryptPeerImportTest], which EXECUTES the rule
 * and the provider call: that one proves the decision is right, this one proves it is asked the
 * right question. A perfect rule fed the ACCOUNT's address instead of the message's `From` would
 * import every correspondent's key under the reader's own address, and every test of the rule would
 * still be green.
 *
 * Arguments are extracted by matching parentheses, not by reading whole lines: reformatting a call
 * must not turn this red, and changing what is passed must.
 */
class AutocryptImportWiringTest {

    private val source = DaoQuerySource.mailSource("MailRepository")

    /** The argument text of every `importAutocryptKey(…)` CALL (the declaration excluded). */
    private fun callArguments(): List<String> {
        val out = mutableListOf<String>()
        var at = source.indexOf(NAME)
        while (at >= 0) {
            val declaration = source.lastIndexOf("fun ", at).let { it >= 0 && it + 4 == at }
            var i = at + NAME.length
            var depth = 1
            val start = i
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            if (!declaration) {
                out += source.substring(start, i - 1).replace(Regex("\\s+"), " ").trim().trimEnd(',').trim()
            }
            at = source.indexOf(NAME, i)
        }
        return out
    }

    @Test fun `the three call sites, and their arguments`() {
        assertEquals(
            "a call site changed what it hands over. Each must pass THAT message's own headers, " +
                "the address ITS From names (never the account's, or every key is filed under the " +
                "reader's own address) and its own date.",
            listOf(
                // JMAP, one message opened: the headers of the origin read already in flight.
                "headers, email.from.firstOrNull()?.email, email.receivedAt",
                // JMAP, the inbox prefetch: one grouped read, each message judged on its own.
                "headers.map { it.name to it.value }, email.from.firstOrNull()?.email, email.receivedAt",
                // IMAP, on opening: the headers come off the source that had to be fetched anyway.
                "headers, envelope.from.firstOrNull()?.email, envelope.receivedAt",
            ),
            callArguments(),
        )
    }

    @Test fun `the IMAP side reads rawHeaders, and the same list feeds both readers`() {
        // ⛔ `MimeParser.headerOf` is a MAP: it keeps the LAST `Autocrypt` of a message carrying
        // two, and the rule that ignores such a message could then never see the second one.
        assertEquals(
            listOf(
                "val headers = MimeParser.rawHeaders(raw)",
                "importAutocryptKey(headers, envelope.from.firstOrNull()?.email, envelope.receivedAt)",
            ),
            block(REPO_FILE, prefix = "val headers = MimeParser.rawHeaders(raw)", count = 2),
        )
    }

    @Test fun `the gesture is fire-and-forget, and cannot fail an open`() {
        assertEquals(
            "importAutocryptKey changed shape. What the line is holding up: the work leaves the " +
                "opening path onto bgScope, anything thrown is swallowed, and the engine is passed " +
                "as it is — null included, which the pure function answers to by doing nothing.",
            listOf(
                "private fun importAutocryptKey(",
                "headers: List<Pair<String, String>>,",
                "fromAddress: String?,",
                "receivedAt: String?,",
                ") {",
                "bgScope.launch { runCatching { importAutocryptPeer(pgpEngine, headers, fromAddress, receivedAt) } }",
                "}",
            ),
            block(REPO_FILE, prefix = "private fun importAutocryptKey(", count = 7),
        )
    }

    @Test fun `the JMAP header read still asks for nothing new`() {
        // ⛔ A server that refuses ONE requested property rejects the WHOLE Email/get, and then no
        // message opens at all on that account. The header read already returns every field, so
        // this feature adds no property anywhere.
        assertEquals(
            listOf("internal val HEADER_FIELDS_PROPERTIES = listOf(\"id\", \"headers\")"),
            block(JMAP_FILE, prefix = "internal val HEADER_FIELDS_PROPERTIES", count = 1),
        )
    }

    /** [count] CONSECUTIVE code lines of [path] from the single one starting with [prefix]; a prefix
     *  matching anything but exactly one line returns a message, so nothing passes by matching
     *  nothing. Comments and blank lines are dropped, the rest trimmed. */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        val code = File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }

    private companion object {
        const val NAME = "importAutocryptKey("
        const val REPO_FILE = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
        const val JMAP_FILE = "core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt"
    }
}
