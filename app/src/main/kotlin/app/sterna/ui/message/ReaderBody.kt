package app.sterna.ui.message

import app.sterna.core.data.settings.PLAIN_TEXT_DEFAULT
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.bodySource

/**
 * What the reader is about to RENDER for one message (#149). Both modes end up in the same WebView and
 * the same document: plain text is wrapped in HTML, it is not a second renderer.
 *
 * [richHtml] means "the document holds the message's own HTML", not "the message has HTML": it drives
 * the dark-theme invert, and text we paint ourselves must NOT be inverted. [derived] says the text was
 * flattened out of HTML — [htmlToText] is regex-based on purpose and never a faithful DOM.
 */
internal data class ReaderBody(
    /** The HTML fragment to insert into the document's body. */
    val fragment: String,
    /** Whether that fragment is the message's own rich HTML (drives the dark-theme invert). */
    val richHtml: Boolean,
    /** Whether the text in it was flattened out of HTML (drives the "derived text" line). */
    val derived: Boolean,
)

/**
 * The body fragment for [email], in HTML mode ([plainText] false) or plain-text mode.
 * HTML mode is the historical cascade: text/html, else the text part in a `<pre>`, else the preview.
 * Plain-text mode inverts it and goes through [bodySource], NOT `Email.textContent()`: over JMAP a
 * server with no text/plain part fills `textBody` with the text/html one, and escaping THAT into a
 * `<pre>` would show the reader the markup as if it were the message.
 *
 * Every step tests `isNotBlank`, not null: a part that exists with an empty value hands back "", and
 * the preview, computed FROM the body, has the same trap one level down.
 */
internal fun readerBody(
    email: Email,
    plainText: Boolean,
    derivedNotice: String,
    noContent: String,
): ReaderBody {
    if (!plainText) {
        email.htmlContent()?.takeIf { it.isNotBlank() }
            ?.let { return ReaderBody(it, richHtml = true, derived = false) }
        email.textContent()?.takeIf { it.isNotBlank() }
            ?.let { return ReaderBody(plainFragment(it, null), false, false) }
        val fallback = email.preview?.takeIf { it.isNotBlank() } ?: noContent
        return ReaderBody("<p>${htmlEscape(fallback)}</p>", false, false)
    }
    val (raw, isHtml) = bodySource(email)
    val text = (if (isHtml) htmlToText(raw) else raw).ifBlank { noContent }
    return ReaderBody(
        fragment = plainFragment(text, if (isHtml) derivedNotice else null),
        richHtml = false,
        derived = isHtml,
    )
}

/**
 * Whether switching reading mode would CHANGE what [email] puts on the screen (#149).
 *
 * The predicate is NOT "the message has a text/html part": over JMAP a server with no text/plain
 * part fills `textBody` with the text/html one, so `htmlContent()` is null while the two modes differ
 * sharply, and hiding the entry takes the toggle away exactly where it rescues the reader. So both
 * bodies are BUILT and compared: [readerBody] is what decides, and this only reads it.
 */
internal fun readingModesDiffer(email: Email, derivedNotice: String, noContent: String): Boolean {
    val asHtml = readerBody(email, plainText = false, derivedNotice, noContent)
    val asText = readerBody(email, plainText = true, derivedNotice, noContent)
    // One document carries the message's own markup, or the other had to flatten it away: two
    // different pages by construction, whatever text they end up holding.
    if (asHtml.richHtml || asText.derived) return true
    // Neither side carries markup any more: both are text we wrapped ourselves, so compare the TEXT
    // and not the fragments — a message with no body shows the same preview in a <p> here and a
    // <pre> there, and a wrapper we chose is not a change the reader asked for.
    return htmlToText(asHtml.fragment) != htmlToText(asText.fragment)
}

/**
 * Whether remote images may be fetched for the page as it currently stands. The reader decides this
 * TWICE — at the pager level for the menu, at the page level for the WebView — and the two must not
 * drift. Plain-text mode has no image to load, so it wins over both the override and the allowlist.
 * A third place asks the reading-mode half without calling this ([imagesStripPresent]), because its
 * answer is frozen into a `remember` whose key must stay inside the body document's key.
 *
 * This function knows NOTHING about which page the reader is on: it has been cited for saying a
 * warmed neighbour is safe, and it says no such thing — that is `MessagePage`'s `everActive` latch.
 */
internal fun showRemoteImages(plainText: Boolean, manualShow: Boolean, senderAllowed: Boolean): Boolean =
    !plainText && (manualShow || senderAllowed)

/**
 * Which mode the reader RENDERS, from this message's own answer and the stored setting (#149).
 *
 * [override] is a DEVIATION, not an alternative, so it wins in both directions — an `override ||
 * setting` could not come back down and the entry would promise a rendering that never arrives.
 *
 * [setting] null is "DataStore has not answered yet" and renders [PLAIN_TEXT_DEFAULT], not the text
 * side: one [MessageViewModel] is built per page, so a text-first guess would flash plain text on
 * every message swiped through. The image question is answered the other way round.
 */
internal fun plainTextForBody(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_DEFAULT

/**
 * The same two answers, read for the REMOTE-IMAGE question — where the unread setting counts the other
 * way: not read yet is treated as "text", i.e. as no remote image at all.
 *
 * The two mistakes are not the same size: a frame of HTML too many is a frame of HTML, while an image
 * fetched in it has already told the sender the mail was opened. Nothing orders `imageAllowlist`
 * against this read, so one frame where the sender is allowed and the setting is unknown is the frame
 * that fires the tracking pixel. An [override] still wins outright.
 */
internal fun plainTextForImages(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_UNLOADED_FOR_IMAGES

/** Text wrapped for the document: line breaks kept, and the "derived text" line above it if any. */
private fun plainFragment(text: String, notice: String?): String {
    val body = "<pre class=\"plain\">${htmlEscape(reflowFormatFlowed(text))}</pre>"
    return if (notice.isNullOrBlank()) body else "<p class=\"s-note\">${htmlEscape(notice)}</p>$body"
}
