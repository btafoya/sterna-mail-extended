package app.sterna.ui.message

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What the body's WebView does with one resource request. */
internal sealed interface ResourceDecision {
    /** Let the WebView load it itself: an inert local source, or any source once remote images are shown. */
    data object PassThrough : ResourceDecision

    /** Answer with an empty response: a broken image, nothing leaves the device. */
    data object Block : ResourceDecision

    /** Answer with the message's own image. [bytes] is shared between responses and never written. */
    class Serve(val mime: String, val bytes: ByteArray) : ResourceDecision
}

/**
 * The message's inline images as the WebView asks for them, keyed like `inlineImages`: the
 * Content-ID without its angle brackets, to a `data:<mime>;base64,<b64>` URI.
 *
 * The document keeps its `cid:` references, each answered here from a single decoded copy of the
 * image, rather than carrying the data URIs: substituted into the HTML, they make the document
 * (references × image) long, and a message pointing at one small image a hundred thousand times
 * runs the app out of memory on opening and on printing.
 *
 * Read from the WebView's resource threads: each image is decoded once, under [ConcurrentHashMap]'s
 * per-key atomicity, and an unreadable one is remembered as unreadable.
 */
internal class InlineImages(private val dataUris: Map<String, String>) {

    private val decoded = ConcurrentHashMap<String, ResourceDecision>()

    /** [ResourceDecision.Serve] for a known and readable image, [ResourceDecision.Block] otherwise. */
    fun lookup(cid: String): ResourceDecision {
        val uri = dataUris[cid] ?: return ResourceDecision.Block
        return decoded.computeIfAbsent(cid) { decodeDataUri(uri) ?: ResourceDecision.Block }
    }

    companion object {
        val EMPTY = InlineImages(emptyMap())
    }
}

/**
 * The decision behind `BlockingWebViewClient.shouldInterceptRequest`, kept free of `android.*`
 * so it runs in a JVM test. [scheme] is the request's parsed scheme, null for a protocol-relative
 * URL; [url] is the whole request URL.
 *
 * `cid` is answered first and whatever [blockRemote] says: a `cid:` is the message's own image,
 * never a remote one, and letting it through when remote images are shown would leave it for the
 * WebView, which cannot resolve it. A `cid` that is unknown or unreadable is blocked, never passed
 * through: whatever the WebView would do with it is not ours to find out.
 *
 * Past `cid`, the default-deny: only inert, local sources are allowed through. Anything else,
 * http(s), protocol-relative URLs (which arrive with a null or empty scheme), ws, ftp, prefetch, is
 * blocked. Keying on "http"/"https" alone let "//evil.com/x.gif" slip past.
 */
internal fun decideResourceRequest(
    scheme: String?,
    url: String?,
    blockRemote: Boolean,
    images: InlineImages,
): ResourceDecision {
    val s = scheme?.lowercase()
    if (s == "cid") {
        val cid = url?.let(::contentIdOf) ?: return ResourceDecision.Block
        return images.lookup(cid)
    }
    if (!blockRemote) return ResourceDecision.PassThrough
    return if (s == "data" || s == "about") ResourceDecision.PassThrough else ResourceDecision.Block
}

/**
 * How many resource references one document may carry, and how many requests it may make, before
 * the reader shows it as text. The cost is the document itself: the engine calls the app far less
 * than once per reference, and a message referencing one image two hundred thousand times freezes
 * the reader all the same. [asksForTooManyResources] judges the HTML before any WebView gets it;
 * [RequestBudget] is the net for what that count misses.
 */
internal const val MAX_RESOURCE_REQUESTS_PER_DOCUMENT = 1_000

/**
 * Whether [html] carries more than [MAX_RESOURCE_REQUESTS_PER_DOCUMENT] resource references, whatever
 * their scheme: `src`, `srcset`, `background`, `poster` and `href` attributes, and CSS `url(`.
 *
 * One pass, no regex, stopping at the first reference past the limit: the HTML can weigh megabytes
 * and this runs before every render of it. An attribute counts only after whitespace, `<`, a quote,
 * `/` or `:` (for `xlink:href`), so `data-src` does not; `url(` counts anywhere, text included, and so
 * does every link's `href`. Counting too many is the safe error, counting too few hands the WebView
 * the document that freezes it.
 *
 * `MessageViewModel.publishBody` asks this, through [givesResourceVerdict], before every body it puts
 * in `_messages`, and gives the verdict first: the first render of such a body is already the text,
 * on the active page, on a warmed neighbour and on paper. The warm-up alone also drops a body whose
 * page settled while its HTML was being read.
 */
internal fun asksForTooManyResources(html: String?): Boolean {
    if (html.isNullOrEmpty()) return false
    var count = 0
    var i = 0
    while (i < html.length) {
        val length = referenceAt(html, i)
        if (length > 0) {
            if (++count > MAX_RESOURCE_REQUESTS_PER_DOCUMENT) return true
            i += length
        } else {
            i++
        }
    }
    return false
}

/**
 * Whether a body with this [html] gives the too-many-resources verdict: never when one is already
 * kept for its message ([verdictKept]), since a verdict is only ever given, never taken back, and
 * one [RequestBudget] gave stands whatever this count says.
 */
internal fun givesResourceVerdict(verdictKept: Boolean, html: String?): Boolean =
    !verdictKept && asksForTooManyResources(html)

// `srcset` before `src`: both match the same start, only the longer one is followed by its `=`.
private val REFERENCE_ATTRIBUTES = arrayOf("srcset", "src", "background", "poster", "href")

private const val ATTRIBUTE_DELIMITERS = "<\"'/:"

/** The length of the reference starting at [i] in [html], up to its `=` or `(`; 0 when none does. */
private fun referenceAt(html: String, i: Int): Int {
    when (html[i]) {
        'u', 'U' -> return if (html.regionMatches(i, "url(", 0, 4, ignoreCase = true)) 4 else 0
        's', 'S', 'b', 'B', 'p', 'P', 'h', 'H' -> Unit
        else -> return 0
    }
    if (i > 0) {
        val before = html[i - 1]
        if (!before.isWhitespace() && before !in ATTRIBUTE_DELIMITERS) return 0
    }
    for (name in REFERENCE_ATTRIBUTES) {
        if (!html.regionMatches(i, name, 0, name.length, ignoreCase = true)) continue
        var j = i + name.length
        while (j < html.length && html[j].isWhitespace()) j++
        if (j < html.length && html[j] == '=') return j + 1 - i
    }
    return 0
}

/** One request as answered under a [RequestBudget]. */
internal class BudgetedRequest(
    val decision: ResourceDecision,
    /** True for exactly one request per load: the first one past the limit. */
    val crossed: Boolean,
)

/**
 * The resource requests of one document load, counted whatever their scheme, and [reset] by whoever
 * starts the next load. Called from the WebView's resource threads, possibly in parallel: the count
 * is one atomic update, so exactly one caller sees the limit crossed.
 *
 * A request from the previous document that arrives after [reset] counts against the next one, and
 * may be the one that crosses: a budget serves one reader page, whose documents are all of one message.
 */
internal class RequestBudget(private val limit: Int = MAX_RESOURCE_REQUESTS_PER_DOCUMENT) {

    // Saturates at `limit + 1` so a count that never stops cannot wrap round to negative.
    private val count = AtomicInteger(0)

    fun reset() = count.set(0)

    /**
     * [decideResourceRequest] while under the limit; past it, [ResourceDecision.Block] without
     * reading [images], since resolving the request is the cost being refused.
     */
    fun admit(scheme: String?, url: String?, blockRemote: Boolean, images: InlineImages): BudgetedRequest {
        val before = count.getAndUpdate { if (it > limit) it else it + 1 }
        if (before >= limit) return BudgetedRequest(ResourceDecision.Block, crossed = before == limit)
        return BudgetedRequest(decideResourceRequest(scheme, url, blockRemote, images), crossed = false)
    }
}

/**
 * Whether `load` of ([emailId], [accountId]) keeps the verdict given for the entry [verdictFor]: only
 * for that same entry. A warmed page reports before `load` runs, then `load` replays for the same
 * message, whose text is on screen; clearing there puts the HTML and its requests back. Not `warm`:
 * a page never warmed is loaded again after a failure, same message, same verdict.
 */
internal fun keepsResourceVerdict(verdictFor: String?, emailId: String, accountId: String?): Boolean =
    verdictFor != null && verdictFor == MessagePaging.entryKey(emailId, accountId)

/**
 * The key a `cid:` request URL names in `inlineImages`: percent-decoded, since the WebView hands
 * `cid:<x>` over as `cid:%3Cx%3E`, then trimmed of spaces and angle brackets the way the map's keys
 * were when it was built. Null when [url] is not a `cid:` URL.
 */
internal fun contentIdOf(url: String): String? {
    if (!url.regionMatches(0, "cid:", 0, 4, ignoreCase = true)) return null
    return percentDecode(url.substring(4)).trim().trim('<', '>')
}

/** `%XX` sequences decoded as UTF-8; a malformed escape is kept as written. `+` stays a `+`. */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
        val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
        if (hi >= 0 && lo >= 0) {
            out.write(hi * 16 + lo)
            i += 3
        } else {
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

/** A `data:<mime>;base64,<b64>` URI as the image to serve, or null when it cannot be read. */
private fun decodeDataUri(uri: String): ResourceDecision.Serve? {
    if (!uri.regionMatches(0, "data:", 0, 5, ignoreCase = true)) return null
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring(5, comma)
    if (!meta.endsWith(";base64", ignoreCase = true)) return null
    val mime = meta.substringBefore(';').trim()
    if (mime.isEmpty()) return null
    val bytes = try {
        Base64.getDecoder().decode(uri.substring(comma + 1))
    } catch (_: IllegalArgumentException) {
        return null
    }
    return ResourceDecision.Serve(mime, bytes)
}
