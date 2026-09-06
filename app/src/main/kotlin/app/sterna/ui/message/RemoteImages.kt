package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R

/**
 * Whether a message body asks for anything the reader's WebView will refuse to load — the question
 * behind the "images are blocked" strip.
 * It copies the ALLOWLIST, not the scheme list: `shouldInterceptRequest` is default-deny, so keyed
 * on http/https instead, a protocol-relative `//host/x.gif` would be announced as "nothing remote
 * here" while the load was refused.
 *
 * An admitted heuristic reading `src` attributes as text, so it misses `url(...)`, `srcset` and
 * anything assembled by script. Both errors are survivable, and nothing here loads or blocks anything.
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
 * A `src` attribute, quoted, single-quoted or bare. The leading whitespace keeps it off `data-src=`
 * (a lazy-loading placeholder the WebView never fetches), and `srcset` does not match at all.
 */
private val SRC_ATTR =
    Regex("""\ssrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/** A URI scheme per RFC 3986 §3.1, or no match at all when the reference carries none. */
private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

/**
 * Whether the header carries an images strip at all for this page.
 *
 * Answered on [plainText], the mode the BODY is rendered in, not on `plainTextForImages`: the
 * strip's height feeds the key the body's document is built on, so changing this answer mid-read
 * costs two loads of that document, and the image-side mode flips on its own when DataStore answers.
 *
 * The debt that buys: the two modes disagree until DataStore answers, so for a few frames the button
 * is live and does nothing. In text mode there is no strip at all.
 */
internal fun imagesStripPresent(plainText: Boolean, senderAllowed: Boolean, html: String?): Boolean =
    !plainText && !senderAllowed && hasRemoteRefs(html)

/**
 * What the images strip SAYS, and whether it can still be pressed.
 * The two states are one button wearing one label or the other, live in [ACTION] and greyed in
 * [SHOWN]: the measured header height keys the `remember` that builds the body's document, so a strip
 * that changes SIZE cancels the body load in flight.
 *
 * Two shapes under `heightIn(min = ButtonDefaults.MinHeight)` do not hold level — that is a floor, a
 * `TextButton` standing above it while a bare `Text` sits on it — and one shape is one height only
 * while its label never wraps, hence `maxLines = 1`.
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
