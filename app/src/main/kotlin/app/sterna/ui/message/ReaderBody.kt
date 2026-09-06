package app.sterna.ui.message

import app.sterna.core.data.settings.PLAIN_TEXT_DEFAULT
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.bodySource

/**
 * What the reader is about to RENDER for one message — the whole decision behind the reading-mode
 * toggle (#149), pulled out of [buildHtmlDocument] so a test can execute it.
 *
 * Both modes end up in the SAME WebView and the same document: plain text is wrapped in HTML, it
 * is not a second renderer. What changes is which part of the message the document carries, and
 * two consequences the page has to know about:
 *
 *  - [richHtml] is "the document holds the message's own HTML", NOT "the message has HTML". It is
 *    what drives the dark-theme page invert: rich mail carries its own light backgrounds that we
 *    cannot restyle, so it is rendered light and the whole page inverted. Text we paint ourselves
 *    in the app's own colours must NOT be inverted — that is the "white band in dark theme" bug,
 *    seen from the other side.
 *  - [derived] says the text was flattened out of HTML rather than read from a text/plain part.
 *    The reader says so above the body, because the two are not the same thing: [htmlToText] is
 *    regex-based on purpose and gives a readable approximation, never a faithful DOM.
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
 *
 * HTML mode is the historical cascade: the text/html part, else the text part in a `<pre>`, else
 * the one-line preview. Plain-text mode inverts it — the text part first, the HTML flattened
 * behind it — and goes through [bodySource], NOT through `Email.textContent()`, which filters
 * nothing: over JMAP (RFC 8621 §4.1.4) a server with no text/plain part fills `textBody` with the
 * text/html one, and escaping THAT into a `<pre>` under the promise of "plain text" would show the
 * reader the message's markup as if it were the message.
 *
 * [derivedNotice] is the already-localised line to put above a flattened body; it is inserted only
 * when the body really was flattened. [noContent] is the already-localised line for a message that
 * carries no body at all — both are passed IN because this is a pure function and cannot read a
 * resource, which is also what lets a test pin that the reader speaks the user's language here.
 *
 * Every step tests `isNotBlank`, not null. `htmlContent()` and `textContent()` filter nothing: a
 * part that EXISTS with an empty value hands back "", which is not null, so each step claimed the
 * body and skipped the fallback that alone knows what to say. In HTML mode that was a white screen,
 * while plain-text mode said "no content" — the two modes contradicting each other on the same
 * message. The preview has the same trap one level down: the server computes it FROM the body, so
 * an empty body gives an empty preview, and `preview ?: noContent` rendered `<p></p>`, the same
 * white screen. The three sites close together or the defect just moves down the cascade.
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
 * Whether switching reading mode would CHANGE what [email] puts on the screen (#149) — i.e. whether
 * the menu entry is worth offering at all.
 *
 * The predicate is NOT "the message has a text/html part", however natural that reads. Over JMAP
 * (RFC 8621 §4.1.4) a server with no text/plain part fills `textBody` with the text/html one:
 * `htmlContent()` is null, `htmlBody` is empty, and the two modes still differ sharply — HTML mode
 * escapes that markup into a `<pre>` and shows it as if it were the message, plain-text mode refuses
 * the type and flattens it. Hiding the entry there would take the toggle away exactly where it
 * rescues the reader. The other way round, a mailing-list post or a git notification with no HTML
 * part at all renders the SAME `<pre>` in both modes, and "Show HTML" was an entry that changed
 * nothing but its own label — the mail of the very people who turn the setting on.
 *
 * So the question is answered by BUILDING both bodies and comparing them; it cannot be derived from
 * the message's parts, because [readerBody] is what decides, and this is only allowed to read it.
 */
internal fun readingModesDiffer(email: Email, derivedNotice: String, noContent: String): Boolean {
    val asHtml = readerBody(email, plainText = false, derivedNotice, noContent)
    val asText = readerBody(email, plainText = true, derivedNotice, noContent)
    // One document carries the message's own markup, or the other had to flatten it away: two
    // different pages by construction, whatever text they end up holding.
    if (asHtml.richHtml || asText.derived) return true
    // Neither side carries markup any more: both are text WE wrapped ourselves. The only thing left
    // that can differ is the text, so compare the TEXT and not the fragments — a message with no
    // body at all shows the same preview on both sides, in a <p> here and a <pre> there, and a
    // wrapper we chose is not a change the reader asked for. Flattening is safe at this point
    // precisely because there is no message markup left to lose.
    return htmlToText(asHtml.fragment) != htmlToText(asText.fragment)
}

/**
 * Whether remote images may be fetched for the page as it currently stands.
 *
 * Extracted because the reader decides it TWICE — once at the pager level, for the overflow menu,
 * and once at the page level, for the WebView — and the two must not drift: a menu that still
 * offers "Show images" over a body that cannot show any, or the reverse, is the state on screen
 * lying about the state in force. Plain-text mode has no image to load at all, so it wins over
 * both the per-message override and the sender allowlist.
 *
 * A THIRD place asks the reading-mode half of the same question, and does not call this function:
 * [imagesStripPresent], which decides whether the header carries an images strip at all. It takes
 * `plainText` (the body's mode) where these two take `imageMode`, because its answer is frozen
 * into a `remember` whose key must stay included in the key of the body's HTML document —
 * `plainText` is in that key and `imageMode` is not. Same verdict once DataStore has answered;
 * before that, the two guards differ on purpose (see the overflow menu in `MessageScreen`).
 *
 * This function knows NOTHING about which page the reader is on, and must not be read as if it
 * did. Elsewhere in this reader it has been cited for saying that a warmed neighbour is safe
 * because `manualShow` is false there: it says no such thing. `manualShow` is only ever false on
 * an unsettled page because `MessagePage`'s `everActive` latch makes it so — BOTH terms handed in
 * from the page site are gated on that latch (`MessageScreen`), the manual
 * override every bit as much as the allowlist, since the "Show images" button of a warmed page is
 * composed and live and `load()`, which holds the only reset, never runs there. Take the latch
 * away and `false || true` fetches, on a message NOBODY OPENED.
 */
internal fun showRemoteImages(plainText: Boolean, manualShow: Boolean, senderAllowed: Boolean): Boolean =
    !plainText && (manualShow || senderAllowed)

/**
 * Which mode the reader RENDERS, from this message's own answer and the stored setting (#149).
 *
 * [override] is the reading mode chosen from the overflow menu for THIS message, and null means
 * nobody chose one — follow the setting. It is a DEVIATION from the setting, not an alternative to
 * it, so it wins in BOTH directions: with the setting on, the menu says "Show HTML", and an
 * `override || setting` cannot come back down — the entry would promise a rendering that never
 * arrives, on the one screen that just claimed it did. Leaving the message drops the deviation
 * (`load()` clears it) and the setting decides again.
 *
 * [setting] null is "DataStore has not answered yet", and here it renders [PLAIN_TEXT_DEFAULT]. NOT
 * the text side: `MessagePage` builds one [MessageViewModel] per page of the pager, so that window
 * reopens on every message swiped through — a text-first guess would flash plain text and snap back
 * to HTML on each of them, for everyone who has the switch off. The image question is answered the
 * other way round, and that asymmetry is the whole point of there being two functions; see
 * [plainTextForImages].
 */
internal fun plainTextForBody(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_DEFAULT

/**
 * The same two answers, read for the REMOTE-IMAGE question — where the unread setting counts the
 * other way: not read yet is treated as "text", i.e. as no remote image at all.
 *
 * [plainTextForBody]'s reason for preferring the default does not carry over, because the two
 * mistakes are not the same size. A frame of HTML too many is a frame of HTML; a remote image
 * fetched in that frame has already told the sender the mail was opened, and nothing can call it
 * back. `imageAllowlist` starts empty, but nothing orders it against this read: one frame in which
 * the sender is already allowed and the setting is not yet known is exactly the frame that fires the
 * tracking pixel. See [PLAIN_TEXT_UNLOADED_FOR_IMAGES], and [CONFIRM_LINKS_UNLOADED], which is the
 * same trade on the link the reader cannot un-open.
 *
 * An [override] still wins outright, in both directions: it is a gesture just made on this message.
 */
internal fun plainTextForImages(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_UNLOADED_FOR_IMAGES

/** Text wrapped for the document: line breaks kept, and the "derived text" line above it if any. */
private fun plainFragment(text: String, notice: String?): String {
    val body = "<pre class=\"plain\">${htmlEscape(reflowFormatFlowed(text))}</pre>"
    return if (notice.isNullOrBlank()) body else "<p class=\"s-note\">${htmlEscape(notice)}</p>$body"
}
