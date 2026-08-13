package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R

/**
 * Whether a message body asks for anything the reader's WebView will refuse to load — the question
 * behind the "images are blocked" strip, kept out of `MessageScreen.kt` so it can be run.
 *
 * **It copies the allowlist, not the scheme list.** `BlockingWebViewClient.shouldInterceptRequest`
 * is DEFAULT-DENY: it lets through `data:`, `cid:` and `about:`, and blocks everything else. This
 * function has to answer for the same rule, so it asks the same question — is the scheme one of
 * those three? — instead of asking whether it is `http` or `https`. That second form is the defect
 * the client itself was corrected for: a protocol-relative `//host/x.gif` arrives with NO scheme
 * and is fetched over the network all the same, and an exotic scheme (`ftp:`, `ws:`) is blocked.
 * Keyed on http/https, both of those would be announced as "nothing remote here" while the load
 * was in fact being refused — the reader would see a hole in the message and be told nothing.
 *
 * ⚠ **It is an admitted heuristic, and it is allowed to be one.** It reads `src` attributes out of
 * the HTML as text: it therefore MISSES `url(...)` in CSS and in a `style=`, `@import`, `srcset`,
 * the legacy `background=`, `poster=`, anything hidden behind an HTML entity or assembled by
 * script, and anything in a part it is not handed. It counts no `<a href>` — a link is not a
 * resource, nothing about it is loaded, and a strip over a message whose only remote thing is a
 * link would be a strip about nothing.
 *
 * Both errors are survivable, and that is why the heuristic is acceptable:
 *  - a FALSE NEGATIVE means no strip. The images stay blocked, the overflow entry
 *    (`message_show_images`) still shows them, and no privacy promise is weakened — this is the
 *    reason that entry is not removed.
 *  - a FALSE POSITIVE means a cosmetic strip offering to unblock nothing.
 *
 * Nothing here loads, fetches or decides anything: the actual blocking stays where it was, in the
 * client. This function only decides whether to SAY so.
 */
internal fun hasRemoteRefs(html: String?): Boolean {
    if (html.isNullOrEmpty()) return false
    return SRC_ATTR.findAll(html).any { match ->
        val value = (match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "").trim()
        value.isNotEmpty() && isRemoteRef(value)
    }
}

/**
 * The allowlist of `BlockingWebViewClient.shouldInterceptRequest`, applied to a raw attribute
 * value rather than to a parsed `WebResourceRequest`.
 *
 * A URL with no scheme (`//host/x.gif`, `/x.gif`, `x.gif`) falls through to the `else` and counts
 * as remote — which is what the client does with it too.
 */
private fun isRemoteRef(value: String): Boolean {
    val scheme = SCHEME.find(value)?.groupValues?.get(1)?.lowercase()
    return scheme != "data" && scheme != "cid" && scheme != "about"
}

/**
 * A `src` attribute, quoted, single-quoted or bare. The leading whitespace is required so this
 * does not fire on `data-src=` (a lazy-loading placeholder the WebView never fetches), and `src`
 * followed directly by `set=` does not match at all — `srcset` is one of the misses listed above,
 * a miss and not a wrong answer.
 */
private val SRC_ATTR =
    Regex("""\ssrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/** A URI scheme per RFC 3986 §3.1, or no match at all when the reference carries none. */
private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

/**
 * What the images strip SAYS, and whether it can still be pressed — the decision taken out of the
 * `@Composable` so a JVM test can RUN it, on the pattern of [UnsubscribeStripBody].
 *
 * ⭐ **The two states are not two shapes: they are one button wearing one label or the other**,
 * live in [ACTION] and greyed in [SHOWN]. That is not tidiness, it is the whole risk of this
 * feature: the measured height of the header is part of the key of the `remember` that builds the
 * body's HTML document, so a strip that changes size **cancels the body load in flight**. Before
 * that body has ever been revealed that costs the reveal — the poll that lifts its alpha is never
 * re-armed, and the reader waits behind the spinner until the failsafe; after it, the alpha stays
 * latched and the damage is visible instead: the message reloads under her eyes and her scroll
 * position is lost. Showing the images is a gesture made in the header, on a page whose body is
 * the very thing being reloaded — so the strip does not disappear when it is done, it says so.
 *
 * Two shapes were tried first, under a `heightIn(min = ButtonDefaults.MinHeight)` believed to hold
 * them level. It does not: that is a FLOOR, and a `TextButton` stands well above it (Material3
 * wraps it in `minimumInteractiveComponentSize()` and its vertical inset grows with the font
 * scale) while a bare `Text` sits back down on it. The bench measured the header LOSING 8 dp at
 * the tap in the normal font and 13 dp at `font_scale 2.0`. One shape cannot diverge from itself —
 * on the condition that its label never wraps, which is why the strip pins `maxLines = 1`: these
 * two strings differ in length in every locale (de 15 → 23 characters, nl 18 → 24, fr 19 → 16), and
 * a wrap on one state and not the other would move the header by a whole LINE.
 *
 * The [label] lives here rather than in the strip for the same reason [acts] does: what the reader
 * is told is then a decision a test can execute, instead of a branch inside a composable that
 * nothing in the JVM can lay out.
 */
internal enum class ImagesStripBody(@StringRes val label: Int) {
    /** The pictures are held back: the button is live and offers to fetch them. */
    ACTION(R.string.message_show_images),

    /** They are there: the same button, in the same place, greyed and saying so. */
    SHOWN(R.string.message_images_shown),
    ;

    /** Whether the strip still has something to unblock — the button's `enabled`. */
    val acts: Boolean get() = this == ACTION
}

/** See [ImagesStripBody]. */
internal fun imagesStripBody(blocked: Boolean): ImagesStripBody =
    if (blocked) ImagesStripBody.ACTION else ImagesStripBody.SHOWN
