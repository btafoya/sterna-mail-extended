package app.sterna.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlEscapeMultiline
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.send.SendOutbox
import app.sterna.util.isValidEmail
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Heuristic "forgot the attachment?" check: does [text] (subject + body) mention
 * an attachment in one of the app's languages? Substrings are lower-cased and
 * matched loosely (stems cover inflections). Kept conservative to avoid false
 * alarms — pure Kotlin so it is unit-tested.
 */
internal val ATTACHMENT_HINTS = listOf(
    // en
    "attach",
    // fr (stems cover plurals/inflections via substring match)
    "pièce jointe", "pièces jointes", "ci-joint",
    // de
    "anhang", "angehängt", "anbei", "beigefügt",
    // es
    "adjunt", // adjunto/adjunta/adjuntar
    // it
    "allegat", // allegato/allegata/allegati
    // pt
    "anexo", "anexa", "em anexo",
    // nl
    "bijlage", "bijgevoegd",
    // ru
    "вложени", "прикреп",
    // pl
    "załącznik", "załączeniu", "załączam",
)

internal fun mentionsAttachment(text: String): Boolean {
    val haystack = text.lowercase()
    return ATTACHMENT_HINTS.any { haystack.contains(it) }
}

/**
 * The prebuilt "forwarded message" blocks for a forward: the original is carried to send time
 * (rather than flattened into the editable body), so the recipient keeps its formatting. The two
 * blocks are appended, identically, below the user's note in the outgoing text/plain and text/html
 * alternatives. See [buildForwardedBlocks].
 */
internal data class ForwardedBlocks(val text: String, val html: String)

private const val FORWARD_HEADER = "---------- Forwarded message ----------"

/**
 * The four field labels of the forwarded-message header, translated. The dashed line and the field
 * order are a de-facto standard other clients recognise and are deliberately NOT translated; the
 * labels are just text a human reads, so they follow the app's language (D7). Defaults keep the
 * English wording for tests and any caller without a Context.
 */
internal data class ForwardLabels(
    val from: String = "From",
    val subject: String = "Subject",
    val date: String = "Date",
    val to: String = "To",
)

/**
 * Build the text + HTML "forwarded message" blocks for a forwarded original. [originalText] is the
 * original already flattened to plain text (for the text/plain alternative); [originalHtml], when
 * non-null, is the original's HTML, preserved verbatim except that script/style/head blocks are
 * stripped and inline (cid:) images are neutralised (see [cleanForwardedHtml]). When the original
 * has no HTML part, its plain text is escaped into HTML so the html alternative still carries it.
 */
internal fun buildForwardedBlocks(
    from: String,
    subject: String,
    date: String,
    to: String,
    originalText: String,
    originalHtml: String?,
    /** Content-IDs whose inline image is actually carried by the forward; their `<img cid:>` is kept. */
    carriedCids: Set<String> = emptySet(),
    labels: ForwardLabels = ForwardLabels(),
): ForwardedBlocks {
    val text = buildString {
        append(FORWARD_HEADER).append('\n')
        append(labels.from).append(": ").append(from).append('\n')
        append(labels.subject).append(": ").append(subject).append('\n')
        append(labels.date).append(": ").append(date).append('\n')
        append(labels.to).append(": ").append(to).append("\n\n")
        append(originalText)
    }
    val html = buildString {
        append(FORWARD_HEADER).append("<br>")
        append(htmlEscape(labels.from)).append(": ").append(htmlEscape(from)).append("<br>")
        append(htmlEscape(labels.subject)).append(": ").append(htmlEscape(subject)).append("<br>")
        append(htmlEscape(labels.date)).append(": ").append(htmlEscape(date)).append("<br>")
        append(htmlEscape(labels.to)).append(": ").append(htmlEscape(to)).append("<br><br>")
        append(if (originalHtml != null) cleanForwardedHtml(originalHtml, carriedCids) else htmlEscapeMultiline(originalText))
    }
    return ForwardedBlocks(text, html)
}

/**
 * Sanitise an original HTML body for inclusion in a forward: drop script/style/head blocks. An
 * inline image referenced by Content-ID (a `cid:` src) is KEPT when its Content-ID is in
 * [carriedCids] (the image is being re-attached to the forward, so the recipient sees it); any
 * other `cid:` image is neutralised to "[image]" so it does not render broken. Everything else
 * (structure, lists, bold, links, inline styles, http(s) images) is kept verbatim. Regex-based and
 * tolerant of attribute order/quoting, to match the codebase's existing HTML handling.
 */
internal fun cleanForwardedHtml(html: String, carriedCids: Set<String> = emptySet()): String {
    val normalized = carriedCids.mapNotNull { it.trim().trim('<', '>').takeIf(String::isNotBlank) }.toSet()
    val noScripts = html.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
    return Regex("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?\\s*cid:[^>]*>").replace(noScripts) { match ->
        val cid = imgTagCid(match.value)
        if (cid != null && cid in normalized) match.value else "[image]"
    }
}

/** The Content-ID referenced by an `<img src="cid:...">` tag (angle brackets stripped), or null. */
internal fun imgTagCid(imgTag: String): String? =
    Regex("(?i)\\bsrc\\s*=\\s*[\"']?\\s*cid:([^\"'>\\s]+)").find(imgTag)
        ?.groupValues?.get(1)?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }

/**
 * The message's body as plain text: its text/plain part, else its HTML converted to text,
 * else the one-line preview as a last resort. Used for quoting a reply and for reopening a
 * draft in the plain-text editor (#63).
 */
internal fun originalPlainText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return if (isHtml) htmlToText(raw) else raw
}

/**
 * The body to work from, and whether it is HTML. HTML-only mail makes the server synthesise
 * textBody = the HTML part, so the "text" body can actually be HTML; a genuine text/plain part is
 * used as-is, and the one-line preview is the last resort.
 *
 * `internal`, not file-private, since #149: the reader's plain-text mode needs exactly this
 * decision ([app.sterna.ui.message.readerBody]) and a second copy of it would be free to drift —
 * the whole point is that "the text body" can be HTML, and a copy that forgot it would show
 * escaped markup under a "plain text" label.
 */
internal fun bodySource(o: Email): Pair<String, Boolean> {
    val textPart = o.textBody.firstOrNull()
    val raw = textPart?.partId?.let { o.bodyValues[it]?.value }
    if (!raw.isNullOrBlank()) return raw to textPart?.type.equals("text/html", ignoreCase = true)
    o.htmlContent()?.takeIf { it.isNotBlank() }?.let { return it to true }
    return o.preview.orEmpty() to false
}

/**
 * The original as it should be QUOTED in a reply:
 *  - an HTML original keeps its quoting depth: the `<blockquote>` nesting becomes ">" markers
 *    ([htmlToQuotedText]), so replying to a reply reads ">" then ">>" instead of flattening the
 *    whole history to a single level (B1);
 *  - a plain-text original already carries its own ">" markers and is taken as-is;
 *  - either way it is cut at the sender's signature delimiter (D4) — standard netiquette, and the
 *    fix for signatures piling up over a three-message exchange. The cut is strict (a line that is
 *    exactly "-- " or "--"), so a decorative "----------" or a "--- end ---" never truncates.
 *
 * Only the QUOTE is treated this way: reopening a draft ([draftFieldsOf]) and forwarding both keep
 * the whole body, since there the signature is the user's own text or the message being passed on.
 */
internal fun quotedOriginalText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return cutAtSignatureDelimiter(if (isHtml) htmlToQuotedText(raw) else raw)
}

/**
 * HTML→plain text for QUOTING, keeping the quoting depth the original carried: text inside one
 * `<blockquote>` comes out prefixed with "> ", inside two with ">> ", and so on. Without this the
 * whole history flattens to a single level and, once [quote] adds its own marker, a three-message
 * exchange is indistinguishable from a fresh reply (B1).
 *
 * One regex pass over the blockquote open/close tags, splitting the HTML into depth-tagged
 * segments — deliberately NOT an HTML parser: mail HTML is hostile, and unbalanced tags only ever
 * cost a level of indentation here. Covers the Gmail/Apple/Proton shapes, which all nest plain
 * `<blockquote>` elements.
 */
internal fun htmlToQuotedText(html: String): String {
    val out = StringBuilder()
    var depth = 0
    var last = 0

    fun emit(segment: String, at: Int) {
        val text = htmlToText(segment)
        if (text.isBlank()) return
        if (out.isNotEmpty()) out.append('\n')
        val marker = ">".repeat(at)
        out.append(text.lineSequence().joinToString("\n") { if (at == 0) it else "$marker $it" })
    }

    for (tag in BLOCKQUOTE_TAG.findAll(html)) {
        emit(html.substring(last, tag.range.first), depth)
        depth = if (tag.value.startsWith("</")) (depth - 1).coerceAtLeast(0) else depth + 1
        last = tag.range.last + 1
    }
    emit(html.substring(last), depth)
    return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

private val BLOCKQUOTE_TAG = Regex("(?i)<blockquote\\b[^>]*>|</blockquote\\s*>")

/**
 * Add one quoting level to [line]: an already-quoted line gets a tighter ">" (so a reply to a reply
 * reads ">>", not "> >"), a fresh line the usual "> ".
 */
internal fun deepenQuote(line: String): String = if (line.startsWith(">")) ">$line" else "> $line"

/** [text] up to (excluding) the first line that is exactly "-- " or "--", trailing blanks trimmed. */
internal fun cutAtSignatureDelimiter(text: String): String {
    val lines = text.split("\n")
    val at = lines.indexOfFirst { it == SIGNATURE_DELIMITER || it == "--" || it == "-- \r" || it == "--\r" }
    if (at < 0) return text
    return lines.take(at).joinToString("\n").trimEnd()
}

/** The field a freshly opened composer puts the keyboard in. */
internal enum class ComposeFocus { RECIPIENTS, SUBJECT, BODY }

/**
 * Which field opens focused. The single rule, in one place — the composable only obeys it.
 *
 * A reopened draft and a reply already have their recipients, so they open on the body (#63);
 * everything else opened from inside the app — a fresh mail, a forward, a restored undo-send —
 * opens on the recipients, which is what still has to be typed.
 *
 * A composer opened from OUTSIDE with fields already filled in is the exception (#83): a
 * `mailto:` link addresses the mail for you, so landing on To means everything typed goes into a
 * field that is already correct. The focus goes to the first field the link left empty — the
 * subject when the link only names a recipient, the body once it carries a subject too. A link
 * with no recipient at all (and a system "Share", which reuses this path with an empty To) still
 * opens on the recipients, since that is genuinely the first thing missing.
 *
 * [linkTo] and [linkSubject] are the raw prefill values the composer was opened with, empty or
 * null when it was not opened from a link.
 */
internal fun initialComposeFocus(
    isDraft: Boolean,
    isReply: Boolean,
    linkTo: String?,
    linkSubject: String?,
): ComposeFocus = when {
    isDraft || isReply -> ComposeFocus.BODY
    linkTo.isNullOrBlank() -> ComposeFocus.RECIPIENTS
    linkSubject.isNullOrBlank() -> ComposeFocus.SUBJECT
    else -> ComposeFocus.BODY
}

/**
 * Whether a composer opened with [restore] is really holding a queued outbox row — the INPUT the
 * historical defect lived in, so it is a function of its own rather than an expression buried in the
 * ViewModel (#96).
 *
 * [restore] is the navigation argument and survives the app being killed. [restored] is the draft
 * handed over in memory and does NOT: after a process death it is null while the argument still says
 * `true`, which is precisely the state that titled an empty composer "Edit". Both have to agree.
 *
 * A draft handed over with no [SendOutbox.ComposeDraft.editingOutboxId] is an undone send: its row
 * was dropped when the send was undone, so there is nothing in the outbox to be editing either.
 */
internal fun holdsQueuedOutboxRow(restore: Boolean, restored: SendOutbox.ComposeDraft?): Boolean =
    restore && restored?.editingOutboxId != null

/**
 * Whether a draft saved on the SERVER survives leaving this composer — the input [discardWording]
 * calls `editingDraft`, and the thing the dialog may not announce as destroyed (#35).
 *
 * Two ways in, and the second is the one that was missing. [draftId] is the navigation argument: the
 * composer was opened on a draft from the Drafts list. It is preferred to whether the draft could
 * actually be READ, because an offline reopen whose fetch failed still has its draft sitting in
 * Drafts, untouched.
 *
 * [restoredDraftId] is `SendOutbox.ComposeDraft.draftEmailId`, carried by an undone send — and the
 * hole. A send started from a draft keeps that draft in Drafts until the message is DELIVERED; Undo
 * only takes the queued row away, so the draft is still there. The composer is then reopened with
 * `restore = true` and NO draftId in the route, so a rule reading the route alone answered "nothing
 * behind this screen" and the dialog said "Discard message?" over a draft that exists — the reported
 * symptom, reached by another path. Pass it only when [restore] is set: like the outbox row above,
 * the handed-over draft does not survive the app being killed, and the argument alone must not be
 * allowed to describe an empty composer.
 */
internal fun savedDraftBehindScreen(draftId: String?, restoredDraftId: String?): Boolean =
    draftId != null || restoredDraftId != null

/** What the composer's top bar calls this message. Mapped to a string by the screen. */
internal enum class ComposeTitle { NEW, REPLY, FORWARD, DRAFT, OUTBOX_EDIT }

/**
 * What the composer is titled — the whole rule, out of the composable so it can be tested (#96).
 * It used to live inside the `TopAppBar`, where nothing could reach it, and it was wrong in a case
 * nobody could see: see [editingOutbox] below.
 *
 * The order matters and is not alphabetical:
 *  - a reopened draft says "Draft", the same word the list badges it with (#96);
 *  - a forward carries the SAME [replyTo] as a reply, so it must be caught before it or it reads
 *    "Reply" (#96);
 *  - then a reply;
 *  - then a queued message pulled back out of the Outbox: "Edit", because it is a message already
 *    waiting to go, not a new one.
 *
 * [restore] is the navigation argument, [editingOutbox] whether a queued row is actually parked
 * behind this composer. BOTH are needed, and this is the fix (#96, defect 2): the argument survives
 * the app being killed while the in-memory draft does not, so `restore=true` alone titled an EMPTY
 * composer "Edit" after a process death. Only a real row still held says "Edit".
 *
 * An undone send deliberately says "New mail", not "Edit": undoing dropped its queued row, so there
 * is nothing left to go back to and nothing being edited. That difference is intended — the two
 * screens are genuinely not the same message any more.
 */
internal fun composeTitle(
    draftId: String?,
    mode: String?,
    replyTo: String?,
    restore: Boolean,
    editingOutbox: Boolean,
): ComposeTitle = when {
    draftId != null -> ComposeTitle.DRAFT
    // Both forwards (inline and as attachment): the top bar says the word the subject carries.
    composeOpening(mode).subjectPrefix == "Fwd:" -> ComposeTitle.FORWARD
    replyTo != null -> ComposeTitle.REPLY
    restore && editingOutbox -> ComposeTitle.OUTBOX_EDIT
    else -> ComposeTitle.NEW
}

/**
 * Which wording the "you are leaving without saving" dialog uses. [fromOutbox] is what the title and
 * the discard button key on — those two read the same whether or not a draft is on offer.
 */
internal enum class DiscardWording(val keepsMessage: Boolean, val fromOutbox: Boolean) {
    PLAIN(keepsMessage = false, fromOutbox = false),
    ENCRYPTED(keepsMessage = false, fromOutbox = false),

    /** A draft already saved on the server is behind this screen: leaving drops the edits only. */
    DRAFT(keepsMessage = true, fromOutbox = false),

    /** That same draft, with the padlock closed by hand: leaving still drops the edits only, and
     *  the body still has to explain why this one cannot be re-saved as a draft. */
    ENCRYPTED_DRAFT(keepsMessage = true, fromOutbox = false),

    /** Out of the outbox, with "Save draft" offered beside "Discard changes". */
    OUTBOX(keepsMessage = true, fromOutbox = true),

    /** Out of the outbox while encrypting, so only "Discard changes" and "Cancel" are offered. */
    OUTBOX_ENCRYPTED(keepsMessage = true, fromOutbox = true),
}

/**
 * What the leave dialog must say, which is not always "this message will be lost" (#70).
 *
 * A message reopened from the Outbox is the case that was lying: its row never left the outbox, it
 * only sat in `EDITING`, so leaving hands it straight back. The dialog nevertheless offered
 * "Discard message? You haven't saved your changes." — and the user tapping Discard believed they
 * had destroyed a mail that was still there. Only the EDITS are dropped; the message survives, on
 * purpose (deleting it is the Outbox screen's own delete, which asks separately).
 *
 * What the wording may NOT promise is delivery. Where the row lands is
 * `OutboxLogic.stateAfterEdit`: a send whose auto-retry was already spent goes back to FAILED and
 * is left parked for manual Retry, not re-queued. "It will still be sent" would therefore be false
 * on exactly the message the user is most likely to have opened — the one that failed. "It stays in
 * the outbox" is true on all three paths (queued, still held, failed) and is the fact that matters:
 * the message was not destroyed.
 *
 * [mayKeepDraft] is [draftSaveAllowed]: an encrypted message cannot be parked in Drafts, and the
 * dialog says why instead of offering a button that would upload the plaintext (#35). Where the
 * message GOES outranks why it cannot become a draft — an encrypted message out of the outbox is
 * still not destroyed by leaving — so [editingOutbox] is tested first.
 *
 * The outbox case splits on [mayKeepDraft] all the same, because the sentence has to be true of BOTH
 * buttons in front of the user, not only the one it is describing. With "Save draft" offered, that
 * button CONSUMES the queued row (`consumeEditingOutbox`): the message leaves the outbox for Drafts,
 * so a flat "it stays in the outbox" is false of the button sitting right next to it. Without it,
 * only leaving is possible and the shorter sentence is the true one — and it may then point at the
 * outbox's own delete, which is the only way left to be rid of the message.
 *
 * [editingDraft] is the second thing that can survive this screen (#35, #127): a draft already
 * saved on the server, reopened from the Drafts list. Leaving destroys nothing of it — `cancel()`
 * calls `abandon()`, a no-op outside the outbox — so "Discard message?" was literally false there,
 * while the body under it said "changes" all along. It is the navigation argument, not whether the
 * draft could actually be READ: an offline reopen whose fetch failed still has its draft sitting in
 * Drafts, untouched. Ranked below the encrypted case, which has a Save button to account for and
 * is the question in front of the user, and below the outbox, which says where the message went.
 *
 * A draft being ENCRYPTED has to answer both halves at once, which is why it has a wording of its
 * own: the copy in Drafts survives (so nothing may be announced as destroyed) AND the Save button is
 * gone (so the dialog still owes the user the reason — a draft is uploaded exactly as typed). Before
 * it existed, `!mayKeepDraft` outranked the draft and the user was told, over a draft still sitting
 * on the server, that "leaving now discards the message".
 *
 * What is left saying "Discard message?" is a message that exists nowhere but on this screen — and
 * there the title is true. [DiscardWording.keepsMessage] is that fact, carried on the enum, so the
 * title and the discard button both key on it rather than on a list of variants that has to be
 * kept in step by hand.
 */
internal fun discardWording(
    editingOutbox: Boolean,
    mayKeepDraft: Boolean,
    editingDraft: Boolean,
): DiscardWording = when {
    editingOutbox && mayKeepDraft -> DiscardWording.OUTBOX
    editingOutbox -> DiscardWording.OUTBOX_ENCRYPTED
    !mayKeepDraft && editingDraft -> DiscardWording.ENCRYPTED_DRAFT
    !mayKeepDraft -> DiscardWording.ENCRYPTED
    editingDraft -> DiscardWording.DRAFT
    else -> DiscardWording.PLAIN
}

/**
 * A button the leave dialog offers, in the order they are laid out.
 *
 * A list rather than a shape in the composable, because both things this decides are guarantees
 * somebody has already gone looking for. [CANCEL] is #35/#127: the reporter's screenshot shows
 * [Discard] [Save draft] and nothing that goes back to the message he was writing — tapping outside
 * the dialog always did, but no button said so, and the one that reads like a way out is the one
 * that throws the editing away. [SAVE_DRAFT] is 1.4.3's plaintext guarantee, the other way round:
 * an encrypted message may never be offered a way into Drafts, because a draft is uploaded exactly
 * as typed. That one used to be held by an `if` around a branch of the composable, where nothing
 * could run it.
 */
internal enum class DiscardChoice { CANCEL, DISCARD, SAVE_DRAFT }

/** One answer of the leave dialog: the button, and whether it may be tapped at all. */
internal data class DiscardAnswer(val choice: DiscardChoice, val enabled: Boolean)

/**
 * Cancel first, the confirming answer last — the order the settings screens' own three-answer exit
 * already uses (`SaveChangesDialog`), so the two do not read differently.
 *
 * The two conditions are the toolbar's two conditions, and they are not the same question — which
 * is why the dialog needs both (#35):
 *
 *  - [mayKeepDraft] is [draftSaveAllowed], the value the toolbar HIDES its Save icon on. An
 *    encrypted message may not be offered a way into Drafts at all, so the button is absent, and the
 *    dialog's body then owes the user the reason ([discardWording]);
 *  - [hasContent] is [draftHasContent], the value the toolbar GREYS its Save icon on. Empty, the
 *    save is not a save: `ComposeViewModel.saveDraft` reads the same rule, finds nothing worth
 *    persisting, and DELETES the draft the composer was opened on (the 1.3.12 behaviour — an
 *    emptied draft must not linger as a shell in Drafts). Reached from this dialog, that is a
 *    button labelled "Save draft" destroying the copy on the server with no word said. Open a
 *    saved draft, clear the subject and the body, tap the X, tap Save draft: the draft is gone.
 *
 * Greyed rather than dropped, deliberately. Dropping it would keep the dialog's own precedent for
 * "not on offer" (the encrypted case) but make two sentences false: the OUTBOX body says the
 * message "moves to Drafts if you save it", beside a button that would no longer be there, and the
 * toolbar the user just came from shows a greyed icon rather than none. Same state, same shape, in
 * both places.
 */
internal fun discardChoices(mayKeepDraft: Boolean, hasContent: Boolean): List<DiscardAnswer> =
    listOfNotNull(
        DiscardAnswer(DiscardChoice.CANCEL, enabled = true),
        DiscardAnswer(DiscardChoice.DISCARD, enabled = true),
        DiscardAnswer(DiscardChoice.SAVE_DRAFT, enabled = hasContent).takeIf { mayKeepDraft },
    )

/**
 * Whether the composer offers to delete the draft it is editing (#127, #95).
 *
 * Four terms, each of them a way of not lying about what the button would do:
 *
 *  - [draftId], the navigation argument: this composer was opened FROM a saved draft. A new mail,
 *    a reply and a forward have nothing to delete;
 *  - not [restore]: a message pulled back out of the send queue carries a draft id too, and what is
 *    expected of it is the outbox's own gesture, on a row parked in `EDITING` that must be handed
 *    back or consumed. That screen is titled "Edit"; this button is not for it;
 *  - [draftInHand]: the SERVER draft was actually READ and its row is in the cache, so there is a
 *    message to move to the Trash. Offline, a reopen whose fetch failed has a draft id and nothing
 *    behind it — and a button that would silently do nothing is worse than no button;
 *  - [localDraftHeld]: this composer holds the lease on a draft the phone is KEEPING and no server
 *    has ever seen (#95). It has no cached row and never will, so [draftInHand] is false for it
 *    for ever — and until this term existed the only way out of such a draft was to empty it by
 *    hand and save, which nothing on screen said. ⛔ Not `draftOpenRoute(draftId) == LOCAL`: on a
 *    row the upload worker consumed between the list being drawn and the finger landing, the route
 *    still answers LOCAL while the ViewModel holds nothing. The LEASE is the truth.
 *
 * The two last are an OR, and everything above them still holds: whichever store the draft came
 * from, a restore and a composer with no draft id are still offered nothing.
 */
internal fun draftDeleteOffered(
    restore: Boolean,
    draftId: String?,
    draftInHand: Boolean,
    localDraftHeld: Boolean,
): Boolean = !restore && draftId != null && (draftInHand || localDraftHeld)

/**
 * Where the caret starts in a prefilled body, or null when [focus] is not the body (that field
 * takes the keyboard instead). [bodyLength] is the whole prefilled body, signature included;
 * [linkBodyLength] how much of it a `mailto:` link supplied, 0 when it supplied none.
 *
 * The caret always goes where the writing continues. Reopening a draft resumes after its last
 * character, so writing carries on where it stopped. A `mailto:` link that carries a body resumes
 * after the text it supplied (#83): one writes AFTER what the link wrote, and since the prefill
 * appends the signature below, that offset also sits just above the signature. Everything else
 * body-focused starts at the very top: a reply sits above the quoted original, and a link with no
 * body of its own opens on a body that is nothing but the signature block, where offset 0 is what
 * keeps the user from typing under their own signature.
 */
internal fun initialBodyCaret(
    bodyLength: Int,
    focus: ComposeFocus,
    isDraft: Boolean,
    linkBodyLength: Int = 0,
): Int? = when {
    focus != ComposeFocus.BODY -> null
    isDraft -> bodyLength
    linkBodyLength > 0 -> linkBodyLength.coerceAtMost(bodyLength)
    else -> 0
}

/**
 * Where a tap on a header row (To / Cc / Bcc / Subject) puts the caret, or null to leave the caret
 * where it is and let the field decide. [tapX] is the horizontal position of the press and
 * [textStartX] the left edge of the editable text, both in the row's own coordinates;
 * [textLength] is how long that text is.
 *
 * A tap before the text — on the field's label, or in the empty space that precedes the first
 * character — means "put the cursor at the very beginning", which otherwise takes pixel-perfect
 * aim just left of the first glyph (#26). A tap on the text never reaches this decision (the field
 * consumes it and the caret lands under the finger), and a tap past the end of the text forces
 * nothing either, so it keeps the caret at the end. An empty field has no beginning to aim at, and
 * geometry that isn't known yet (a field not laid out) can't be judged: neither forces anything.
 */
internal fun headerTapCaret(tapX: Float, textStartX: Float, textLength: Int): Int? = when {
    textLength <= 0 -> null
    !tapX.isFinite() || !textStartX.isFinite() -> null
    tapX < textStartX -> 0
    else -> null
}

/** Split a recipient string into trimmed, non-empty address tokens. */
internal fun recipientTokens(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A recipient field is a single comma-joined string, and that string is the only state there is:
 * everything before the last separator is a committed address (shown as a chip), the trailing token
 * is what is still being typed. [splitRecipients] reads a field that way, [joinRecipients] writes
 * it back.
 */
internal fun splitRecipients(value: String): Pair<List<String>, String> {
    val cut = value.lastIndexOfAny(charArrayOf(',', ';'))
    val chips = recipientTokens(if (cut >= 0) value.substring(0, cut) else "")
    val input = (if (cut >= 0) value.substring(cut + 1) else value).trimStart()
    return chips to input
}

/** Put a recipient field back together from its committed addresses and its still-typed token. */
internal fun joinRecipients(chips: List<String>, input: String): String =
    chips.joinToString("") { "$it, " } + input

/**
 * The recipient field after a tap on the chip at [index] (#94): that address stops being a chip and
 * becomes the field's editable text again, so a typo is fixed by tapping the address rather than
 * deleting it and retyping the whole thing. It lands as the trailing token, which the field opens
 * with the caret at its end; from there a tap anywhere inside it moves the caret there, exactly as
 * before the address was committed.
 *
 * What was already being typed is not thrown away: it is committed as a chip of its own, at the end
 * of the list, which is where it already sat in the string. An [index] that names no chip (a stale
 * tap on a field that changed underneath) leaves the field untouched.
 *
 * Note the model's own limit: the field is comma-joined, so an address is a comma-free token. A
 * display name holding a comma is already two tokens before any tap, and stays two afterwards.
 */
internal fun recipientsWithChipEdited(value: String, index: Int): String {
    val (chips, input) = splitRecipients(value)
    if (index !in chips.indices) return value
    val kept = chips.filterIndexed { i, _ -> i != index }
    val pending = input.trim()
    return joinRecipients(if (pending.isEmpty()) kept else kept + pending, chips[index])
}

/**
 * Compose's initial fields when reopening a saved draft for editing (#63): every addressing
 * field as typed-out addresses, the subject verbatim, and the body flattened to the plain-text
 * editor's format. Cc/Bcc are revealed when the draft used them.
 *
 * [o] is what the server just handed back, [cached] the row this app kept for that same draft — the
 * row the Drafts list itself is drawn from — or null when there is none. See [draftRecipientField]
 * for what the cached row is allowed to do, which is very little.
 */
internal fun draftFieldsOf(o: Email, cached: Email?): DraftFields {
    val to = draftRecipientField(o.to, cached?.to)
    val cc = draftRecipientField(o.cc, cached?.cc)
    val bcc = draftRecipientField(o.bcc, cached?.bcc)
    return DraftFields(
        to = to,
        cc = cc,
        bcc = bcc,
        subject = o.subject.orEmpty(),
        body = originalPlainText(o),
        expand = cc.isNotBlank() || bcc.isNotBlank(),
    )
}

/**
 * One addressing field of a reopened draft, comma-joined: for each stored recipient, the address
 * whenever there is one, and only otherwise the display name (#96).
 *
 * The address has to win wherever it exists, because this string is not a list of models: it is
 * what the send path parses and mails. Reopening `Bob <bob@example.com>` as "Bob" would address the
 * message to `Bob`, which is worse than the loss this repairs.
 *
 * The fallback is for the recipient a server hands back with an empty address, the string typed
 * sitting in the name (`aa`, no @): the draft list row already shows it, since it reads display(),
 * while the composer used to open with the field silently emptied — and an emptied Cc also folded
 * the Cc/Bcc row shut, so nothing on screen said anything had gone. Giving the name back puts the
 * exact typed string in the field, where the user can fix it or send it as they meant to.
 *
 * An entry carrying neither is not a recipient and contributes nothing, not even a separator.
 *
 * [cachedAddresses] is the same field of the row this app cached for that draft, and it is used in
 * ONE case: the server gave nothing for this field and the cache has something. Measured on the
 * bench, a server can drop an address it considers invalid outright — the field then comes back
 * empty and the typed string survives nowhere but in our own row, the one the list is drawn from.
 *
 * It is deliberately not "take the cache whenever it holds more". The cache can be stale: another
 * client may have deliberately REMOVED a recipient, and bringing it back would re-address a message
 * behind the user's back. So a field the server filled is never touched, whatever the cache holds —
 * a partial loss is left uncorrected rather than risk resurrecting a deleted recipient. A null
 * cached row (no row, or one cached before schema v17, which never got a backfill) simply changes
 * nothing.
 */
private fun draftRecipientField(
    addresses: List<EmailAddress>,
    cachedAddresses: List<EmailAddress>?,
): String {
    val fromServer = recipientFieldOf(addresses)
    return if (fromServer.isNotBlank()) fromServer else recipientFieldOf(cachedAddresses.orEmpty())
}

/** [addresses] as the composer's comma-joined field: the address if there is one, else the name. */
private fun recipientFieldOf(addresses: List<EmailAddress>): String =
    addresses
        .map { it.email.ifBlank { it.name.orEmpty() } }
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * Split an addressing field into its trimmed, non-empty address tokens (comma or semicolon
 * separated). A field holding only blanks or stray separators yields nothing — the one place the
 * app decides what counts as "an address was typed here", so the send path, the draft save and the
 * #69 content rule below cannot disagree.
 */
internal fun parseAddrs(s: String): List<String> =
    s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The recipients the encryption state is derived from (#35): the addressing tokens that already ARE
 * addresses, deduplicated.
 *
 * The composer's lock answers "will this leave encrypted?", and that needs a public key for every
 * recipient. A token still being typed (`b`, `bob@`, `bob@exa`) is not a recipient yet: a key lookup
 * for it can only come back empty, so counting it would have the lock announce "not encrypted"
 * about an address the user has not finished writing. Only what reads as an address counts, judged
 * by the app's single [isValidEmail] rule — the same one the send gate and the per-chip warning use,
 * so the three cannot drift apart.
 */
internal fun encryptionRecipients(to: String, cc: String, bcc: String): List<String> =
    (parseAddrs(to) + parseAddrs(cc) + parseAddrs(bcc)).filter(::isValidEmail).distinct()

/**
 * Whether the encryption state has to be recomputed: [previous] is the recipient set it was last
 * computed for (null when it never was, or when something else invalidated the answer), [current]
 * the set [encryptionRecipients] reads now.
 *
 * This is the whole anti-flicker rule (#35), and it is deliberately NOT a timer. Recomputing "once
 * typing stops" would tie a security indicator to typing speed; the condition here is that the set
 * of real addresses changed — one completed, added, or deleted. In between, however long the token
 * in progress takes, the displayed state is left strictly alone: it goes on describing the
 * recipients that are actually there. A completed address that has no key still flips the state at
 * once, which is the case where the indicator earns its keep.
 *
 * Order is irrelevant: the answer depends on which addresses are present, not on where they sit.
 */
internal fun recipientKeysStale(previous: List<String>?, current: List<String>): Boolean =
    previous == null || previous.toSet() != current.toSet()

/**
 * The single #69 rule for "this draft is worth saving": at least one real recipient in To, Cc or
 * Bcc, a non-blank subject, a non-blank body, or an attachment.
 *
 * A typed address IS content — that is the #69 bug: a compose holding only a recipient was judged
 * empty, so tapping "Save draft" in the leave dialog closed the screen and persisted nothing, a
 * silent loss dressed up as a successful save. Blanks still do not count (hence [parseAddrs], not
 * isNotBlank), and neither does a wholly empty compose: opening one and leaving must go on leaving
 * no trace anywhere, and an existing draft emptied out must go on being deleted (the 1.3.12 fix).
 *
 * Shared by the [ComposeViewModel] save gate and the ComposeScreen toolbar, so the greyed-out Save
 * icon and the actual save-or-skip decision can never drift apart.
 */
internal fun draftHasContent(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    hasAttachment: Boolean,
): Boolean =
    parseAddrs(to).isNotEmpty() || parseAddrs(cc).isNotEmpty() || parseAddrs(bcc).isNotEmpty() ||
        subject.isNotBlank() || body.isNotBlank() || hasAttachment

/**
 * The single rule for "may this message be kept as a draft at all?" (#35): everything but an
 * encrypted one. A draft is stored on the mail server as it stands in the composer, so saving one
 * while encrypting would hand the provider the very plaintext the encryption is there to withhold.
 * The question asked is [PgpMode.encrypts], never `== PgpMode.ENCRYPT`: both encrypting modes are
 * caught (a message encrypted without a signature leaks exactly the same plaintext through a draft
 * as a signed one). [PgpMode.SIGN] is NOT caught: a signed message travels and is stored in the
 * clear by design, so its draft is no worse than an unsigned one.
 *
 * It depends on the composer's current mode and on nothing else, which is the point: the mode is
 * the same field whether the user closed the lock by hand or the account's encrypt-by-default put
 * it there, so every route to "this is encrypted" answers identically. Shared by the toolbar's
 * Save/Schedule actions, the leave dialog and the [ComposeViewModel] save itself, so no exit from
 * the composer can offer what another forbids.
 */
internal fun draftSaveAllowed(pgpMode: PgpMode): Boolean = !pgpMode.encrypts

/**
 * The single rule for "may this message be SCHEDULED for later send?" — stricter than
 * [draftSaveAllowed] on two counts, each of which would otherwise send silently short of what the
 * composer showed (audit A2/A3):
 *  - a scheduled send is fired by a headless worker that can neither sign nor encrypt, so ANY
 *    [PgpMode] but OFF is refused (not just ENCRYPT): a SIGN send scheduled would leave unsigned;
 *  - the scheduled table carries no attachments yet, so any staged attachment refuses scheduling
 *    rather than firing the message with its attachments stripped.
 *
 * A draft is still allowed under both conditions (a draft is not sent) — that is [draftSaveAllowed].
 * Shared by the toolbar's Schedule button and the [ComposeViewModel] schedule gate so the greyed-out
 * icon and the actual schedule-or-refuse decision can never drift apart.
 */
internal fun scheduleSendAllowed(pgpMode: PgpMode, hasAttachment: Boolean): Boolean =
    pgpMode == PgpMode.OFF && !hasAttachment

/** The quick "send later" choices, in the order the composer's menu draws them. */
internal enum class SchedulePreset { IN_1_HOUR, THIS_EVENING, TOMORROW_MORNING, TOMORROW_EVENING }

/**
 * The presets still offerable at [nowMillis], read in [zone], in menu order — those STRICTLY in the
 * future. Pure so the 6 PM boundary can be played without moving the bench's clock; the composer's
 * wrapper only resolves the labels.
 *
 * A lapsed candidate is DROPPED, never folded to the next day. Folding used to correct the instant
 * and leave the label, so past 6 PM the "This evening, 6 PM" entry scheduled TOMORROW while saying
 * so at the present tense (the confirmation toast repeats the label, never the instant) and became
 * an exact duplicate of the "Tomorrow, 6 PM" entry below it. Dropping keeps the shown state true
 * (WYSIWYG) and makes the duplicate impossible by construction: three entries after 6 PM.
 */
internal fun schedulePresetsAt(nowMillis: Long, zone: ZoneId): List<Pair<SchedulePreset, Long>> {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    fun at(day: ZonedDateTime, hour: Int) = day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    return listOf(
        SchedulePreset.IN_1_HOUR to now.plusHours(1),
        SchedulePreset.THIS_EVENING to at(now, 18),
        SchedulePreset.TOMORROW_MORNING to at(now.plusDays(1), 8),
        SchedulePreset.TOMORROW_EVENING to at(now.plusDays(1), 18),
    ).filter { (_, time) -> time.isAfter(now) }
        .map { (preset, time) -> preset to time.toInstant().toEpochMilli() }
}

/**
 * What a tap on [preset] at [nowMillis] is worth, given [drawnMillis] — the instant that entry was
 * DRAWN for when the menu opened — or null when the tap must be REFUSED.
 *
 * ⛔ The same danger as [pickedScheduleMillis], one tap earlier. The menu can stand open for
 * minutes with the composer in the background: an entry drawn at 5:55 PM still reads "This evening,
 * 6 PM" when it is tapped at 6:05. That instant handed to `ScheduledSends.enqueue` becomes a delay
 * coerced to zero, which is to say the message goes out AT ONCE while the confirmation still names
 * the evening — and a send has no way back. Hence a refusal, never a correction.
 *
 * ⚠ ONE preset is asked again and THREE are not, and the difference is what each entry promised:
 *  - [SchedulePreset.IN_1_HOUR] is RELATIVE — "an hour from the finger". Only re-reading the rule
 *    with the tap's clock keeps that promise, and it can never lapse.
 *  - the three others are ABSOLUTE — 6 PM, or 8 AM tomorrow, OF THE DAY THE ENTRY WAS DRAWN. Their
 *    answer is [drawnMillis] itself. ⛔ Re-reading them does not correct them, it moves them by a
 *    whole day at midnight: a menu drawn on the 18th at 11:50 PM shows "Tomorrow, 8 AM" for the
 *    19th, and re-read five minutes later, on the 19th, that same entry means the 20th. The user
 *    reads 8 AM and the message leaves 32 hours later.
 *
 * ⛔ The refusal of an absolute preset is read on [drawnMillis], NEVER on the preset still being in
 * a fresh [schedulePresetsAt] list. That list happily offers THIS_EVENING to an entry drawn
 * yesterday morning, and scheduling it would hand over an instant fourteen hours gone — the
 * immediate send back through the side door. The drawn instant answers "behind us", which is true.
 *
 * [zone] is only the relative branch's, handed to [schedulePresetsAt] so the arithmetic and the
 * STRICTLY-after filter stay in the single place they are unit-tested. An absolute entry already
 * carries the zone it was drawn in, baked into its instant.
 */
internal fun presetMillisAtTap(
    preset: SchedulePreset,
    drawnMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
): Long? = when (preset) {
    SchedulePreset.IN_1_HOUR ->
        schedulePresetsAt(nowMillis, zone).firstOrNull { it.first == preset }?.second
    // Named one by one rather than an `else`: a fifth preset added one day must not be swallowed
    // into "carry what was drawn" in silence — the compiler has to ask which of the two kinds it is.
    SchedulePreset.THIS_EVENING, SchedulePreset.TOMORROW_MORNING, SchedulePreset.TOMORROW_EVENING ->
        drawnMillis.takeIf { it > nowMillis }
}

/**
 * The instant a hand-picked day + time is worth in [zone], or null when it is not STRICTLY after
 * [nowMillis] (#161).
 *
 * ⛔ A lapsed instant must NEVER reach the schedule: `ScheduledSends.enqueue` coerces a negative
 * delay to zero, so a time already gone fires the message AT ONCE, and a send has no way back.
 * Nothing between the menu and that coercion looks at whether the instant is ahead, so the refusal
 * is here — one function, so the greyed-out confirm button and the instant actually scheduled read
 * the same decision and cannot drift apart. The screen calls it TWICE: once for `enabled`, once
 * inside the tap with a fresh clock, because the minute can turn between the two.
 *
 * ⚠ [dayUtcMidnightMillis] is what Material's `DatePicker` hands back: UTC midnight, never local
 * midnight (`SearchScreen` says the same about its own picker). So the CIVIL day is read off it in
 * UTC and rebuilt in [zone]; comparing that millis to a local instant is the trap — east of UTC it
 * makes today look like yesterday for the first hours of every night, west of UTC it does the
 * reverse in the evening.
 */
internal fun pickedScheduleMillis(
    dayUtcMidnightMillis: Long,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    nowMillis: Long,
): Long? {
    val day = Instant.ofEpochMilli(dayUtcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val picked = ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    return picked.takeIf { it > nowMillis }
}

/**
 * The wall time the "pick date and time" clock opens on, once a day has been chosen (#161): an
 * hour ahead, or the last minute the chosen day still has when an hour ahead is not an instant
 * that day can take.
 *
 * ⛔ Material's default of midnight is what this exists to avoid, and the naive fix reopened the
 * same wound at the other end of the day. Taking only the HOUR and MINUTE of `now + 1 h` and
 * pasting them onto the chosen day throws the roll over to tomorrow away: at 23:20 the clock opened
 * on 00:20 TODAY, some twenty-three hours in the past, [pickedScheduleMillis] refused it, and OK
 * was grey from the very first frame. There is deliberately no error wording anywhere in this path
 * — the greyed button IS the message — so the user was left in front of a dead dialog with nothing
 * to read.
 *
 * ⛔ Hence the one invariant, and it is asked of [pickedScheduleMillis] ITSELF rather than
 * reasoned about here: what comes out of this function, handed straight back with the same day,
 * zone and clock, is accepted. A rule of our own — "clamp when the civil date rolls over" — looked
 * equivalent and was not: on the night the clocks GO BACK, `now + 1 h` stays on the same civil
 * date at the earlier offset, and rebuilding that wall time lands on the first pass of the
 * repeated hour, which is `now` again or before it. One hour a year, in the small hours, the dead
 * dialog was back. Asking the very function the OK button asks is the only formulation that cannot
 * drift from it.
 *
 * ⛔ 23:59, not "now + 1 minute", for the fallback: a minute from now goes stale in sixty seconds,
 * while the last minute of the day is the same answer for the whole evening — up to 23:58, where
 * the two finally meet and the day is nearly out anyway.
 *
 * ⚠ In the very last minute of a day (23:59:xx) the chosen day has no future minute left at all,
 * so OK is grey and stays grey. That is honest and it is left alone: the only way out would be to
 * move the send to a day the user did not pick. Same for the 1970 a clock opened over no day at
 * all is handed.
 *
 * ⚠ The UTC-midnight trap of [pickedScheduleMillis] is not repeated here, and must not be: the day
 * is passed through untouched, in the language Material's `DatePicker` speaks it.
 */
internal fun scheduleClockOpensAt(dayUtcMidnightMillis: Long, nowMillis: Long, zone: ZoneId): LocalTime {
    val ahead = Instant.ofEpochMilli(nowMillis).atZone(zone).plusHours(1)
    val anHourAhead = LocalTime.of(ahead.hour, ahead.minute)
    val offerable = pickedScheduleMillis(dayUtcMidnightMillis, anHourAhead.hour, anHourAhead.minute, zone, nowMillis)
    return if (offerable != null) anHourAhead else LocalTime.of(23, 59)
}

/**
 * Whether BOTH "pick date and time" pickers must show Material's KEYBOARD entry instead of their
 * dial and their grid (#161). `true` = typed fields; `false` = the dial and the month grid, which
 * are the nicer faces and stay the default. One rule for the two dialogs, because one shape of
 * window breaks them both.
 *
 * ⛔ The comparison is not ours and must not be replaced by a dp constant: it is character for
 * character the one `TimePicker_androidKt.getDefaultTimePickerLayoutType` makes, read from the
 * Material 1.3.1 bytecode — height below width and Material lays its own dial out `Horizontal`,
 * i.e. the dial BESIDE the time display. That layout is far wider than the dialog, so whatever
 * does not fit is simply not on screen. ⚠ And a scroll does not save it — the clock's shell has one
 * now, and it is VERTICAL, while this cut is sideways. Any other threshold puts the two decisions
 * out of step and either brings the clipping back or takes the dial away from a screen that shows
 * it perfectly.
 *
 * ⚠ The CLOCK's damage, measured on `emu` in landscape at 914×411 dp, 2026-08-20: the dial's
 * right HALF was off screen, and the digits still drawn (12, 11, 10, 9, 8, 7, 6) landed on top of
 * the AM/PM selector — "8" over "PM". The `uiautomator` dump was down to `[12, 28, AM, Cancel,
 * OK]`: `PM` had no node at all, so half the day was unreachable, and two hour positions of twelve
 * were left. Nothing on screen said a word about it; only Cancel and OK still answered.
 *
 * ⚠ The CALENDAR's damage, same bench, 2026-08-21, opening the picker and THEN turning:
 * `DatePickerDialog` asks for `requiredWidth(ContainerWidth).heightIn(max = ContainerHeight)` —
 * 360 dp wide and at most 568 dp tall, a MAX and never a min — so turned, the ceiling is the
 * SCREEN's 411 dp instead, and Material's content box has no scroll of its own: what the month
 * grid does not fit into is CLIPPED. August 2026 stopped at Saturday the 29th, that cell itself
 * truncated; the 30th and the 31st had no node at all; Saturday the 1st was gone off the top; and
 * Cancel/OK were 37 px tall instead of 53, drawn over the 23–29 row. Nothing scrolled: a swipe up
 * uncovered neither the 30th nor the 31st.
 *
 * ⛔ Scrolling is not the answer for either, and was rejected twice over. For the clock the cut is
 * HORIZONTAL and a vertically scrolling slot moves nothing sideways. For the calendar's GRID a
 * `verticalScroll` would measure the year chevron's `LazyVerticalGrid` in infinite height, which
 * THROWS — a clipped grid traded for the app stopping. ⚠ That is why the shell both TYPED faces
 * are drawn in, which does scroll, is guarded against ever composing that grid. Nor is widening
 * either dialog past Material's default with geometry of our own.
 *
 * ⚠ `screenHeightDp >= screenWidthDp` keeps the dial and the grid, equality included: a square
 * screen is where Material still lays out `Vertical`, and both fit there.
 */
internal fun schedulePickerAsInput(screenHeightDp: Int, screenWidthDp: Int): Boolean =
    screenHeightDp < screenWidthDp

/**
 * Whether a day the calendar offers may be chosen at all: today and after, read in [zone] (#161).
 * This is the FIRST of the two locks in front of a send into the past; the second is
 * [pickedScheduleMillis], which alone covers "today, at an hour already gone".
 *
 * ⛔ Lower bound only. No horizon exists anywhere in the scheduled-send code, and inventing one
 * here would be a limit to carry forever.
 *
 * ⚠ Same UTC-midnight trap as [pickedScheduleMillis], and this is where it bites hardest: both
 * sides are reduced to a CIVIL day, the candidate in UTC (the picker's language) and "now" in
 * [zone]. Comparing the candidate's millis to a local day boundary is wrong by a whole day
 * whenever the zone offset is not zero — in Los Angeles after 5 PM it would refuse TODAY.
 */
internal fun scheduleDaySelectable(dayUtcMidnightMillis: Long, nowMillis: Long, zone: ZoneId): Boolean {
    val day = Instant.ofEpochMilli(dayUtcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return !day.isBefore(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
}

/**
 * The FIRST year the "pick date and time" calendar offers in its year list, read in [zone] (#161).
 *
 * ⚠ A DISPLAY bound, not a lock. Nothing here refuses a send into the past — that is
 * [scheduleDaySelectable]'s job, day by day, and it stays where it is. What this closes is a
 * different damage, measured on `emu` 2026-08-20: tapping the year chevron opened the list on
 * 1900 and the user had to scroll a hundred and twenty-six dead years to reach the current one.
 * Material's `DatePickerDefaults.YearRange` is 1900..2100 and it, not the selectable-dates object,
 * decides which years are DRAWN.
 *
 * ⛔ Lower bound only, like [scheduleDaySelectable]: the upper end stays exactly Material's. No
 * horizon exists anywhere in the scheduled-send code and inventing one here would be a limit to
 * carry forever.
 *
 * ⚠ The year is read in [zone] and nowhere else. On 31 December at 23:00 west of UTC the UTC year
 * has ALREADY turned: reading it there would strike the year the user is still living in off the
 * list, and the calendar would open on a year the user cannot pick a December day in. East of UTC
 * on 1 January the mirror image — UTC is still on the 31st, and the list would start on a year
 * that is over.
 */
internal fun scheduleFirstYear(nowMillis: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(nowMillis).atZone(zone).year

/**
 * The lock toggle's cycle: off → sign → encrypt → off. Pure so the hand-set path can be tested
 * against [draftSaveAllowed] — closing the lock by hand must forbid the draft exactly as the
 * account default does (#35).
 *
 * ⛔ THREE STOPS, and [PgpMode.ENCRYPT_UNSIGNED] is not one of them: a short tap must not walk
 * anyone into sending unsigned mail they did not ask for. That mode is reached only by choosing it
 * in the long-press menu. It still has a branch here because a tap must always be able to get OUT
 * of it, and out means OFF — the same exit [PgpMode.ENCRYPT] has.
 */
internal fun nextPgpMode(current: PgpMode): PgpMode = when (current) {
    PgpMode.OFF -> PgpMode.SIGN
    PgpMode.SIGN -> PgpMode.ENCRYPT
    PgpMode.ENCRYPT -> PgpMode.OFF
    PgpMode.ENCRYPT_UNSIGNED -> PgpMode.OFF
}

/**
 * The face each mode wears, in the top bar AND in the long-press menu — one function, so the icon
 * the menu offers is the icon the bar will show.
 *
 * ⛔ [PgpMode.ENCRYPT_UNSIGNED] does NOT wear `Icons.Outlined.Lock`, the obvious "same padlock as
 * [PgpMode.ENCRYPT], hollow". It was tried and MEASURED: compose's `Icons.Filled.LockOpen` — the
 * face of [PgpMode.OFF] — is itself an outline-bodied drawing, so the outlined padlock differed
 * from "not encrypted at all" by 82 rasterised pixels out of 9216, a notch in the shackle. The
 * hollow padlock does not read as "encrypted, unsigned"; it reads as "off".
 *
 * ⚠ So the unsigned mode wears a KEY: the message is just as encrypted, and what is missing is the
 * signature — which is not drawn at all. The absence of the signature is told by the absence of the
 * padlock's pen, never by a fainter padlock. [PgpModeIconFacesTest] executes this function and
 * measures that the four faces stay pairwise distinct.
 */
internal fun pgpModeIcon(mode: PgpMode): ImageVector = when (mode) {
    PgpMode.OFF -> Icons.Filled.LockOpen
    PgpMode.SIGN -> Icons.Filled.Draw
    PgpMode.ENCRYPT -> Icons.Filled.Lock
    PgpMode.ENCRYPT_UNSIGNED -> Icons.Filled.VpnKey
}

/**
 * The two arguments of `PgpEngine.signAndEncrypt`, which are NOT the same thing and must never be
 * derived from one another: [signKeyId] is who signs (null = do not sign at all), and
 * [recipientKeyIds] is who can decrypt.
 *
 * ⛔ The account's own key plays BOTH roles today, and that is the whole reason this is a function.
 * It is the signing key, and it is also a recipient — the encrypt-to-self that keeps the copy filed
 * in Sent readable. Making the signer nullable and filtering "the sign key" out of the recipients
 * would take the sender's own key out of [recipientKeyIds] and make every unsigned encrypted
 * message in Sent permanently unreadable, on the sender's own device, with no error anywhere. The
 * two roles are separated by NAME (`selfKeyId` vs `signKeyId`), never by a condition.
 */
internal data class PgpEncryptArgs(val signKeyId: Long?, val recipientKeyIds: LongArray) {
    // Hand-written because a data class holding an array compares it by reference, which would make
    // every test that compares two PgpEncryptArgs pass without looking at a single key id.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PgpEncryptArgs && signKeyId == other.signKeyId &&
                    recipientKeyIds.contentEquals(other.recipientKeyIds)
                )

    override fun hashCode(): Int = 31 * (signKeyId?.hashCode() ?: 0) + recipientKeyIds.contentHashCode()

    override fun toString(): String =
        "PgpEncryptArgs(signKeyId=$signKeyId, recipientKeyIds=${recipientKeyIds.toList()})"
}

/**
 * Who signs and who can read, for an encrypting [mode]. The ONLY thing the unsigned mode changes in
 * the send path: no signer.
 *
 * [selfKeyId] (the account's PGP key) is in the recipients in BOTH modes — that is the
 * encrypt-to-self, and it is what lets the sender reopen their own Sent copy. It is the signer only
 * in [PgpMode.ENCRYPT].
 *
 * Pure, and called with the composer's live mode, so the decision the send makes is the decision a
 * JVM test executes.
 *
 * ⛔ A non-encrypting [mode] is a programming error and says so. Answering "no signer" for
 * [PgpMode.OFF] or [PgpMode.SIGN] would be the quiet kind of wrong: the send site reaches this only
 * from its encrypting branch, so a mode arriving here that does not encrypt means the mode moved
 * under the send, and the honest answer to that is a crash, not an unsigned message.
 */
internal fun pgpEncryptArgs(mode: PgpMode, selfKeyId: Long, recipientKeys: List<Long>): PgpEncryptArgs {
    require(mode.encrypts) { "pgpEncryptArgs called with $mode, which does not encrypt" }
    return PgpEncryptArgs(
        signKeyId = selfKeyId.takeIf { mode == PgpMode.ENCRYPT },
        recipientKeyIds = (recipientKeys + selfKeyId).distinct().toLongArray(),
    )
}

/** Why a message stopped being signed/encrypted, when the composer had to give the mode up. */
internal enum class PgpDropReason { NONE, NOT_CONFIGURED, NO_PROVIDER }

/** The mode the composer settles on after re-checking OpenPGP, and what it owes the user. */
internal data class PgpRefreshOutcome(val mode: PgpMode, val dropped: PgpDropReason)

/**
 * Re-arbitrate the lock after anything that can change what OpenPGP can do here: opening the
 * composer, restoring a signed/encrypted message, switching the "From" identity (#35).
 *
 * [available] is "this account has OpenPGP set up AND the provider answers"; it implies
 * [accountConfigured], which only picks WHICH explanation is owed. Three outcomes:
 *  - nothing can encrypt or sign here → the mode goes OFF. If it was not OFF, something the user
 *    could see just went away, so it is [dropped] with a reason and the composer says so. This is
 *    the case that was silent: a message queued as ENCRYPT, reopened after OpenKeychain was
 *    uninstalled, came back with no padlock at all and no word said — it looked like an ordinary
 *    message and would have gone out in the clear;
 *  - the account encrypts by default and the user has not touched the lock → ENCRYPT, the opening
 *    intent; [deriveAutoMode] backs off from there, visibly, when a recipient has no key;
 *  - otherwise the mode stands: a hand-set lock, or one restored with the message, is the user's
 *    and is never quietly rewritten.
 */
internal fun pgpRefresh(
    current: PgpMode,
    available: Boolean,
    accountConfigured: Boolean,
    encryptByDefault: Boolean,
    userSet: Boolean,
): PgpRefreshOutcome = when {
    !available -> PgpRefreshOutcome(
        PgpMode.OFF,
        when {
            current == PgpMode.OFF -> PgpDropReason.NONE
            !accountConfigured -> PgpDropReason.NOT_CONFIGURED
            else -> PgpDropReason.NO_PROVIDER
        },
    )
    encryptByDefault && !userSet -> PgpRefreshOutcome(PgpMode.ENCRYPT, PgpDropReason.NONE)
    else -> PgpRefreshOutcome(current, PgpDropReason.NONE)
}

// --- Signature (pure text, living in the body — WYSIWYG) -----------------------------------------
// The signature is ordinary text in the editable body, inserted when compose opens, exactly like
// K-9 and Thunderbird. It is NOT appended at send time any more: what the composer shows is what
// leaves. Everything here is pure so the insertion, the "is it still intact?" test and the HTML
// substitution are unit-tested off-device.

/** The standard signature delimiter line (RFC 3676 §4.3): two hyphens and a space. */
internal const val SIGNATURE_DELIMITER = "-- "

/**
 * The block a [signature] occupies in a body: a blank line, the delimiter line, then the signature
 * itself. Empty for a blank signature, so every caller can concatenate unconditionally.
 *
 * [delimiter] is the "Separator line above the signature" setting (#90), on by default. Turned off,
 * the block is the blank line and the signature alone: the signature field then holds EXACTLY what
 * goes into the message, so whoever wants "__", a rule, or nothing at all types it there. The
 * setting governs what is WRITTEN; both shapes are still recognised when reading a body back (see
 * [signatureBlockAt]).
 */
internal fun signatureBlock(signature: String, delimiter: Boolean): String = when {
    signature.isBlank() -> ""
    delimiter -> "\n\n$SIGNATURE_DELIMITER\n${signature.trim()}"
    else -> "\n\n${signature.trim()}"
}

/**
 * The composer's initial body: the [quoted] original (empty for a new message) with the signature
 * block placed above it, or below when [signatureBelowQuote] is set. A reply's quote already starts
 * with its own blank lines, so the caret sits at the top of an empty first line either way.
 */
internal fun bodyWithSignature(
    quoted: String,
    signature: String,
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return quoted
    return if (signatureBelowQuote) quoted + block else block + quoted
}

/**
 * [body] with [signature]'s block added where the prefill would have put it — used when the "From"
 * identity changes and the identity being left had NO signature, so there is nothing to swap and
 * nothing the user can have deleted (D5).
 *
 * [quoted] is the quoted original this compose opened with (empty for a new message or a forward,
 * whose original is carried separately). With the signature above the quote — the default — the
 * block goes immediately before that quote, i.e. under the answer being written, exactly as a reply
 * opens. Below the quote, or with no quote at all, it goes at the very end. A quote the user has
 * edited away is no longer found as the tail, and the block then lands at the end rather than in an
 * arbitrary spot.
 */
internal fun insertSignatureBlock(
    body: String,
    signature: String,
    quoted: String = "",
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return body
    if (signatureBelowQuote || quoted.isEmpty() || !body.endsWith(quoted)) return body + block
    return body.dropLast(quoted.length) + block + quoted
}

/**
 * [body] with the block of [oldSignature] swapped for [newSignature]'s — or null when the block is
 * not there verbatim, which means the user edited (or deleted) it and their text must be left
 * alone. Used when the "From" identity changes mid-composition (D5). A blank [oldSignature] has no
 * block to match: that case is [insertSignatureBlock]'s, not this one's.
 *
 * The old block is looked up in BOTH shapes ([signatureBlockAt]); the new one is written in the
 * shape [delimiter] asks for, so after the swap the body reads exactly as a composer opened today
 * would have written it.
 */
internal fun replaceSignatureBlock(
    body: String,
    oldSignature: String,
    newSignature: String,
    delimiter: Boolean,
): String? {
    val found = signatureBlockAt(body, oldSignature, delimiter) ?: return null
    return body.substring(0, found.start) +
        signatureBlock(newSignature, delimiter) +
        body.substring(found.end)
}

/** Where a signature block was found in a body, and in which shape it was written. */
private data class SignatureBlockMatch(val start: Int, val end: Int, val withDelimiter: Boolean)

/**
 * Where [signature]'s block sits in [body] verbatim, or null when it does not. The LAST occurrence
 * wins, so a reply quoting an older message that ended with the same signature swaps the live block
 * at the bottom, not the quoted copy above it. The block must also END on a line boundary: text
 * appended to its last line ("Acme (mobile)") means the user edited it, and an edited signature is
 * never rewritten nor swapped for its stored HTML.
 *
 * BOTH shapes are recognised — with and without the delimiter line — whatever the setting currently
 * says (#90). A draft written with the delimiter and reopened after the setting was turned off (or
 * the reverse) would otherwise stop being found, and the two features built on this lookup would go
 * quiet without a word: the identity swap would decline every time ("the user edited it"), and the
 * imported HTML signature would stop being substituted into the html alternative. The shape named
 * by [delimiter] is tried FIRST, so an ambiguous body resolves to what the composer writes today.
 */
private fun signatureBlockAt(body: String, signature: String, delimiter: Boolean): SignatureBlockMatch? {
    for (withDelimiter in listOf(delimiter, !delimiter)) {
        val block = signatureBlock(signature, withDelimiter)
        if (block.isEmpty()) return null
        val at = lastBlockIndex(body, block)
        if (at >= 0) return SignatureBlockMatch(at, at + block.length, withDelimiter)
    }
    return null
}

/** The last occurrence of [block] in [body] that ends on a line boundary, or -1. */
private fun lastBlockIndex(body: String, block: String): Int {
    var at = body.lastIndexOf(block)
    while (at >= 0) {
        val end = at + block.length
        if (end == body.length || body[end] == '\n') return at
        if (at == 0) return -1
        at = body.lastIndexOf(block, at - 1)
    }
    return -1
}

/**
 * The outgoing text/html alternative for [body]. When the identity has an imported HTML signature
 * ([signatureHtml]) AND the plain [signature]'s block is still in the body untouched, that block —
 * and only it — is replaced by the HTML version, so the recipient gets the formatted signature.
 * Once the user edits the block, their text wins in both alternatives (WYSIWYG beats fidelity).
 *
 * The delimiter line is emitted only when the block found in the body actually carries one — which,
 * for a body the composer just built, is exactly what [delimiter] says. Mirroring the body rather
 * than the setting is deliberate: the two alternatives of one message must say the same thing, and
 * a body written before the setting was flipped still holds the other shape (#90).
 */
internal fun htmlBodyWithSignature(
    body: String,
    signature: String,
    signatureHtml: String,
    delimiter: Boolean,
): String {
    if (signatureHtml.isBlank()) return htmlEscapeMultiline(body)
    val found = signatureBlockAt(body, signature, delimiter) ?: return htmlEscapeMultiline(body)
    val head = if (found.withDelimiter) "<br><br>$SIGNATURE_DELIMITER<br>" else "<br><br>"
    return htmlEscapeMultiline(body.substring(0, found.start)) +
        head + signatureHtml.trim() +
        htmlEscapeMultiline(body.substring(found.end))
}

// --- Reply / reply-all / forward header derivation (pure, so it works from a cached list row) ---
// These need only the original's headers (sender, recipients, subject) — never its body — so a
// reply/reply-all can be addressed correctly even offline from the cached row of a mail that was
// never opened (the quoted body is added separately, and skipped when the body isn't available).

/**
 * Who an answer to [o] is addressed to, as a LIST: the original's `Reply-To` when it sets one —
 * ALL of its addresses, in order — and only otherwise its sender (the first From address), as it
 * has always been. `Reply-To` (RFC 5322 §3.6.2) is the sender naming another mailbox for answers:
 * a mailing list, a `support@` behind a `no-reply@`, a ticketing alias. Answering the From instead
 * writes to the address the sender deliberately set aside, often one nobody reads.
 *
 * ⛔ Never both: the From is precisely what a `Reply-To` displaces (K-9 and Thunderbird do the
 * same). A `Reply-To` holding nothing but blanks is no `Reply-To`, and falls back to the From
 * rather than leaving a reply with no recipient at all.
 *
 * A list, not a joined string, because `Reply-To` may carry several addresses and [replyAllRecipients]
 * deduplicates entry by entry: a single "a@x, b@x" entry would count as ONE address there and slip
 * past the case-insensitive dedup, putting an address already in To or Cc back in.
 *
 * Blanks dropped, duplicates collapsed case-insensitively, spelling kept as the original has it.
 */
internal fun replyRecipients(o: Email): List<String> {
    val named = if (o.replyTo.any { it.email.isNotBlank() }) {
        o.replyTo.map { it.email }
    } else {
        listOfNotNull(o.from.firstOrNull()?.email)
    }
    return named.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
}

/** The recipient field of a plain reply: [replyRecipients], joined for the To field. */
internal fun replyRecipient(o: Email): String =
    replyRecipients(o).joinToString(", ")

/**
 * The recipients of a reply-all: whoever the reply is addressed to ([replyRecipients] — the
 * `Reply-To` when there is one, the sender otherwise, never both) plus everyone on the original's
 * To and Cc, minus your OWN addresses and blanks/duplicates, joined for the recipient field.
 * [selves] is every address the account can send as (login + all its identities), not just the
 * login: an account with aliases used to reply to its own other alias (B5). Compared
 * case-insensitively, on the bare address.
 */
internal fun replyAllRecipients(o: Email, selves: Collection<String>): String {
    val mine = selves.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val all = (replyRecipients(o) + o.to.map { it.email } + o.cc.map { it.email })
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in mine }
        .distinctBy { it.lowercase() }
    return all.joinToString(", ")
}

/**
 * Which of your own addresses [o] was delivered to: the first address of the original's To, then
 * its Cc, that belongs to you ([mine] = the account's identities, plus its login where the caller
 * knows it). Returned as the original spells it, so it can be shown verbatim; matched
 * case-insensitively on the bare address, like every other self-comparison here.
 *
 * Null when none of your addresses is named — a mailing list, or a Bcc delivery. Callers must treat
 * that as "unknown" and keep whatever default they had; it is never a reason to guess.
 */
internal fun receivingAddress(o: Email, mine: Collection<String>): String? {
    val ours = mine.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (ours.isEmpty()) return null
    return (o.to + o.cc)
        .map { it.email.trim() }
        .firstOrNull { it.isNotEmpty() && it.lowercase() in ours }
}

/**
 * The "From" identity a reply/forward of [o] should open with: the one identity of [accountId] that
 * the original was actually delivered to (#81). Without this, an account with several aliases always
 * replied under its default identity, so mail to an alias silently went back out from another
 * address.
 *
 * Only [accountId]'s own options are considered — replying never hops to another account — and only
 * addresses that really are a sending option, so the pick is always something the picker can show.
 * A delegated sub-account needs no special case: its options already carry the addresses
 * [app.sterna.core.data.account.AccountStore.identities] resolves for it (its own, or its login's).
 *
 * Null means "nothing to preselect" (no self address in To/Cc, or none of them is an identity), and
 * the caller keeps the account's default identity.
 */
internal fun receivingFromOption(
    options: List<FromOption>,
    accountId: String?,
    o: Email,
): FromOption? {
    val mine = options.filter { it.accountId == accountId }
    val received = receivingAddress(o, mine.map { it.identity.email })?.lowercase() ?: return null
    return mine.firstOrNull { it.identity.email.trim().lowercase() == received }
}

/** [subject] with [prefix] ("Re:"/"Fwd:") prepended, unless it already carries it (any case). */
internal fun withPrefix(subject: String?, prefix: String): String {
    val s = subject.orEmpty()
    return if (s.startsWith(prefix, ignoreCase = true)) s else "$prefix $s"
}

/**
 * What a compose `mode` navigation argument opens. Parsed ONCE, here, so the three places in
 * [ComposeViewModel.prepare] that branch on it cannot disagree: a mode known to one and not the
 * others opened, in silence, a quoted reply carrying In-Reply-To.
 */
internal enum class ComposeOpening(
    /** Sets In-Reply-To / References from the original. */
    val threads: Boolean,
    /** "Re:" / "Fwd:" */
    val subjectPrefix: String,
    /** The body starts with the quoted original. */
    val quotes: Boolean,
    /** `buildForwarded`: the original travels inline, under the note, at send time. */
    val carriesOriginal: Boolean,
    /** The original's raw source is staged as a `message/rfc822` part (`.eml`). */
    val attachesSource: Boolean,
) {
    REPLY(threads = true, subjectPrefix = "Re:", quotes = true, carriesOriginal = false, attachesSource = false),
    REPLY_ALL(threads = true, subjectPrefix = "Re:", quotes = true, carriesOriginal = false, attachesSource = false),
    FORWARD(threads = false, subjectPrefix = "Fwd:", quotes = false, carriesOriginal = true, attachesSource = false),
    FORWARD_ATTACHMENT(threads = false, subjectPrefix = "Fwd:", quotes = false, carriesOriginal = false, attachesSource = true),
}

/** The one reader of the route's `mode`. Null and any unknown string open a reply, as the
 *  `else` of the old `when` did. */
internal fun composeOpening(mode: String?): ComposeOpening = when (mode) {
    "forward" -> ComposeOpening.FORWARD
    "forwardAttachment" -> ComposeOpening.FORWARD_ATTACHMENT
    "replyAll" -> ComposeOpening.REPLY_ALL
    else -> ComposeOpening.REPLY
}

/**
 * Which sentence a draft that could not be read shows. [offline] is
 * `isOfflineFailure(t, online = hasUsableNetwork(app))`, the app's one offline verdict, decided by
 * the caller because the caller is the only place that holds both the throwable and the link state.
 *
 * Two sentences rather than one, because they ask for different things: offline, the draft's text
 * is on the other side of a connection and what is needed is to come back with one; over a live
 * link, all we know is that the text did not arrive. ⛔ Neither sentence names a CAUSE: no server
 * was even contacted on the "no credentials" path, and a captive portal, a refused certificate, a
 * 401 and a local exception all land here identically. We do not state a mechanism we have not
 * observed.
 *
 * ⛔ And neither promises a recovery that does not exist: `_draftLoadFailed` is never cleared and
 * nothing re-runs the fetch, so coming back online changes nothing until the screen is left and the
 * draft opened again (KISS: no retry state machine). Both sentences therefore ask for exactly that
 * — open it again — which is the one thing that does work.
 *
 * Neither sentence says a word about saving, and that is deliberate: on this path there is nothing
 * to save. The editor is not drawn at all when the fetch fails and nothing has been shown yet (see
 * [draftReopenView]), so the only gesture left on the screen is the one that leaves it.
 */
internal fun draftLoadNoticeFor(offline: Boolean): Int =
    if (offline) R.string.compose_draft_offline else R.string.compose_draft_load_failed

/**
 * What a composer opened on a saved draft is allowed to put on screen.
 *
 * Three states and not a boolean, because the third one carries something: the sentence to show.
 */
internal sealed interface DraftReopenView {
    /** The draft is in hand, or this composer is not reopening one: the editor, exactly as always. */
    data object Editor : DraftReopenView

    /**
     * The fetch is still out. Nothing is editable and no action is offered, because there is
     * nothing yet to edit or to act on: the fields would be blank and a save from them would add a
     * second draft beside the one still being read.
     */
    data object Waiting : DraftReopenView

    /**
     * The draft could not be read at all. [notice] is the string id of what to say, and it is the
     * only thing this screen has left to offer besides the way out.
     */
    data class DeadEnd(val notice: Int) : DraftReopenView
}

/**
 * Which of the three a reopened draft's composer draws, from what the ViewModel publishes about the
 * fetch — whether it is still out ([loading]) and, if it failed, what to say about it
 * ([failureNotice], null while nothing has failed) — and from the one thing the SCREEN knows:
 * [prefillApplied], whether this screen has already put the draft in front of the reader.
 *
 * ⭐ [prefillApplied] WINS OVER BOTH, and it is not an exception grudgingly bolted on. It is the
 * fact "this screen has already shown the draft". A screen that has shown it cannot lie by showing
 * it again; what it WOULD hide, by drawing a spinner or a dead end over it, is the reader's own
 * typing. That is a real path, not a hypothetical: `body`, `initialBody` and this very flag are
 * `rememberSaveable` and survive process death, while the ViewModel does not — so after a kill the
 * fetch is replayed, and offline it dead-ends over three words that are still in memory, intact and
 * suddenly invisible. `ComposeDirty` would still see them, so the X would offer to save, and the
 * save would put a SECOND draft on the server: the exact symptom this whole screen exists to close.
 *
 * The dead end and the wait are therefore claims about a draft NEVER DISPLAYED, which is precisely
 * what `applied == false` says. It is the same flag that already stops a replayed prefill from
 * overwriting restored text: one notion read in two places, not two rules to keep in agreement.
 *
 * ⭐ Wanted side effect: `carryDraftAttachments` runs AFTER `_prefill` is emitted and BEFORE the
 * `finally` that lowers [loading]. With this argument the editor comes back as soon as the text is
 * there, instead of sitting behind a bare spinner through the download AND the re-upload of an
 * attachment of several MB (in JMAP `stageOutgoing` is a full network round trip). ⛔ What keeps
 * that window safe is NOT that the id is missing from it — it is armed before the coroutine, so it
 * is there from the first frame. It is that the LICENCE is missing: `editingDraftLossy` is armed
 * `true` at the same moment and lowered only by the last statement of the branch, after the carry
 * has answered. An id with a lossy verdict destroys nothing, so nothing destructive is armed during
 * that window either — but the reason has to be read off the verdict now, not off a null id.
 *
 * [loading] wins over [failureNotice]. It is not a tie-break for tidiness: a notice left over from
 * an earlier attempt must never be read as the verdict of the one now in flight, and while the
 * fetch is out there is no verdict to read.
 *
 * ⭐ The answer CARRIES the string id rather than leaving the screen to pick a sentence from a
 * boolean. That is the whole reason [DraftReopenView.DeadEnd] holds a value instead of being a
 * second `data object`: what the reader ends up seeing is then a value this function RETURNED, so a
 * JVM test can execute the decision and pin the very resource that lands on screen. Were the
 * screen to re-derive the sentence, it would hold a second copy of the rule, and nothing outside
 * Compose could see the two drift apart.
 */
internal fun draftReopenView(
    loading: Boolean,
    failureNotice: Int?,
    prefillApplied: Boolean,
): DraftReopenView = when {
    prefillApplied -> DraftReopenView.Editor
    loading -> DraftReopenView.Waiting
    failureNotice != null -> DraftReopenView.DeadEnd(failureNotice)
    else -> DraftReopenView.Editor
}

/**
 * Whether the reply's quoted original (fetched in the background, after the headers) may be dropped
 * into the body. Only once the header prefill has been [applied], and only while the body still
 * equals its [initialBody] baseline — so a late-arriving quote never clobbers text the user has
 * already started typing.
 */
internal fun canApplyReplyQuote(applied: Boolean, bodyText: String, initialBody: String): Boolean =
    applied && bodyText == initialBody

/**
 * The time zone the composer renders an OUTGOING date in — the two dates it writes into a message
 * body itself: a reply's quote attribution line and a forwarded message's Date header (#120). With
 * [quotedDatesUtc] on, UTC; off (the default), [deviceZone]. Nothing else changes zone through
 * here: the reader's header and the list rows keep the device's clock (MailDatesTest pins that),
 * and the SMTP `Date:` header was already written in UTC without any setting.
 */
internal fun outgoingDateZone(
    quotedDatesUtc: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = if (quotedDatesUtc) ZoneOffset.UTC else deviceZone

/**
 * [outgoingDateZone], resolved by SUSPENDING on the stored setting: the flow's first emission is
 * awaited, never substituted with a default. DataStore answers a beat late on a cold start, and
 * "not answered yet" is not "off" — a reply prefilled in that beat would write the local time with
 * the switch on, which is the very leak the switch exists to close.
 */
internal suspend fun resolveOutgoingDateZone(
    quotedDatesUtc: Flow<Boolean>,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = outgoingDateZone(quotedDatesUtc.first(), deviceZone)

/** What a sweep of the composer's attachment list found: [kept] stays on screen, [gone] leaves it. */
internal data class StagedAttachmentSweep(
    val kept: List<EmailBodyPart>,
    val gone: List<EmailBodyPart>,
)

/**
 * Which of the composer's [attachments] still have bytes behind them, and which are now a chip and
 * nothing else.
 *
 * A picked file is copied into `cacheDir/outgoing` before it is anything else
 * (`ComposeViewModel.stageOutgoing`), and `cacheDir` is not ours to keep: it is the directory
 * Settings → Apps → Sterna → Storage → Clear cache empties on request, and the one Android evicts
 * on its own when the phone runs short of room. Neither of those closes the composer. The chip
 * stays drawn over a file that is gone, and the first thing to notice is the save — a red banner
 * with a raw English detail in nine locales, and no draft written. Swept on the way back to the
 * screen, the chip goes instead, and the composer says so.
 *
 * ⛔ ONLY PATHS UNDER [outgoingRoot] ARE ASKED ABOUT, and that guard is the whole difficulty. A
 * `partId` exists only for a part staged on this phone — IMAP, or JMAP once PGP is on. Plain JMAP
 * uploads a blob and comes back with `partId = null` (`MailRepository.uploadAttachment`), and a
 * reopened draft or outbox item rebuilds its parts from the same descriptors
 * (`localDraftUploadAttachments`), where the same field is either a staged path or nothing. Ask the
 * filesystem about the rest and it answers "gone" for all of them, which empties the attachment
 * list of a composer that has lost nothing whatsoever. Anything this function is not sure about is
 * KEPT: a chip that is a little stale is a nuisance, a chip silently deleted is the user's file.
 *
 * ⛔ AN INLINE PART THAT CARRIES A `cid` IS KEPT WHATEVER THE FILESYSTEM SAYS, and not even asked
 * about. On a forward, inline images are staged like any other attachment and enter the list with
 * `disposition = "inline"` and a `cid` (`ComposeViewModel.kt:2063-2075`), while the forwarded HTML
 * that references them through `<img src="cid:…">` is built ONCE, from `carriedCids`, and reused
 * as-is at send. Taking such a part off the list would leave a `cid:` pointing at nothing and the
 * recipient would get a broken image; before this sweep existed the send simply REFUSED
 * (`MailRepository.persistAttachments`, "refuse rather than shorten") and nothing went out. The
 * chip therefore stays, and that refusal is what the user meets — the pre-existing behaviour for
 * this case, unchanged. Rebuilding the forwarded HTML would be a new mechanism for an edge case.
 *
 * ⛔ It does NOT touch the filesystem itself: [exists] is handed in, so the decision can be executed
 * on the JVM rather than described by a test that recomputes it (`StagedAttachmentSweepTest`).
 *
 * This says nothing about the moment of saving. Bytes can still go away between this sweep and the
 * tap, and what covers that is the invariant that has always covered it: refuse rather than write a
 * truncated message (#70).
 */
internal fun sweepStagedAttachments(
    attachments: List<EmailBodyPart>,
    outgoingRoot: File,
    exists: (File) -> Boolean,
): StagedAttachmentSweep {
    // A separator on the end, so a sibling directory whose name merely starts the same
    // ("outgoing-old") is not read as being inside the staging root.
    val root = outgoingRoot.path.trimEnd(File.separatorChar) + File.separatorChar
    val (kept, gone) = attachments.partition { part ->
        // The body points at this one; the same two-legged test the repository writes its own
        // inline parts with (MailRepository.imapDraftAttachments, OutboxEdit.take).
        if (part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()) {
            return@partition true
        }
        val staged = part.partId?.takeIf { it.startsWith(root) } ?: return@partition true
        exists(File(staged))
    }
    return StagedAttachmentSweep(kept = kept, gone = gone)
}
