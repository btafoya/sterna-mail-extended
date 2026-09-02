package app.sterna.ui.compose

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The composer's body, parked on disk instead of in the activity's saved-state parcel.
 *
 * WHY IT EXISTS. Replying to a ~324 kB message, letting the quote land, then pressing HOME killed
 * the activity with `TransactionTooLargeException` (a 1 340 240 byte parcel under the key
 * `androidx.lifecycle.BundlableSavedStateRegistry.key`) and the draft was gone without a word. Two
 * `rememberSaveable`s each carried the WHOLE body into that parcel — the text being edited and the
 * baseline it is compared against — and `Parcel.writeString` writes UTF-16, so every character of
 * the body cost 4 bytes of parcel. Everything else on that screen weighs ~12 kB.
 *
 * WHAT CHANGES. The parcel now carries a short TOKEN and nothing else; the body, its caret and the
 * baseline go into an app-private file under `filesDir`. The token proves the parcel and the file
 * are talking about the same record — it has no security role whatsoever.
 *
 * ⛔ ONE FILE PER COMPOSER, not one per app. Two composers ARE reachable: `MainActivity` is
 * singleTask, `onNewIntent` feeds `pendingMailto`, and the host navigates to `compose` unguarded,
 * so a mailto: link or a share arriving with the composer open stacks a SECOND composer on the
 * first. On a single shared file, closing the one on top erased the record of the one underneath
 * and the long reply came back blank — a loss this whole change would have CREATED.
 *
 * ⛔ THE FILE IS NOT AN AUTOSAVE. It is the same lifetime the parcel had: written when the screen
 * is saved, consumed by the restore that follows, erased when that composer closes for good.
 * A composer typing an ENCRYPTED message keeps its cleartext there like any other, so it must
 * never be allowed to outlive its screen.
 */
class ComposerResumeSlot(private val file: File) {

    /** What one saved composer holds. Text is handed back exactly as it went in, never hashed. */
    data class Resume(
        val body: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val baseline: String,
        /** The body's inline styling (#131), per family; empty for a plain body or a `/1` file. */
        val ranges: Map<Inline, List<Span>>,
        /** The baseline's styling, compared by the leave guard exactly like its text. */
        val baselineRanges: Map<Inline, List<Span>>,
    )

    /**
     * Parks [body] (with its caret at [selectionStart]..[selectionEnd]) and [baseline] under
     * [token], and says whether it got there; `false` means nothing could be written (a full disk,
     * an `IOException`) and no half-written file was left behind.
     *
     * ⛔ THE TOKEN IS AN ARGUMENT, never minted here. The saver runs TWICE per activity
     * recreation — `SaveableStateHolderImpl.saveAll()` parcels a COPY of the saved states, then
     * `SaveableStateProvider`'s `onDispose` calls `saveTo` again on the live map as the
     * composition is torn down — so a token minted per write put the file under a name the parcel,
     * already built, could no longer ask for. On the bench that was a rotation coming back with an
     * EMPTY body, at any size, with no exception and no word. See [ComposerResumeState.token].
     *
     * ⛔ Written to a NEIGHBOURING temporary and then renamed over the real file, never in place: an
     * in-place write that dies halfway leaves a file whose header still promises the old lengths,
     * and the next restore would either refuse it or, worse, hand back an amputated draft.
     */
    fun write(
        token: String,
        body: String,
        selectionStart: Int,
        selectionEnd: Int,
        baseline: String,
        ranges: Map<Inline, List<Span>> = emptyMap(),
        baselineRanges: Map<Inline, List<Span>> = emptyMap(),
    ): Boolean {
        val tmp = tempFile()
        return try {
            file.parentFile?.mkdirs()
            tmp.outputStream().bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(header(token, selectionStart, selectionEnd, body.length, baseline.length))
                // Normalised through RichBody, so what is written is always within the text.
                out.write(encodeRanges(RichBody(body, ranges), RichBody(baseline, baselineRanges)))
                out.write("\n")
                out.write(body)
                out.write(baseline)
            }
            // ⛔ `false`, and it matters as much as the `catch` below: a refused rename reported as
            // success hands back a token for a file that is not there, the inline fallback is
            // never reached, and the composer comes back EMPTY at any size — the very defect this
            // whole slot exists to remove.
            if (tmp.renameTo(file)) true else { tmp.delete(); false }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Reads back what [write] parked under [token], or `null` when the file is missing, unreadable,
     * truncated, or was written under a DIFFERENT token.
     *
     * ⭐ A successful read ERASES the file: a resume is consumed once. A refused read leaves it
     * alone — the token that does match may still be about to ask for it.
     */
    fun read(token: String): Resume? = try {
        if (!file.isFile) null else parse(file.readText(Charsets.UTF_8), token)?.also { file.delete() }
    } catch (e: Exception) {
        null
    }

    /** Erases THIS slot and any temporary beside it. Never throws: callers are leaving the screen. */
    fun clear() {
        try {
            file.delete()
            tempFile().delete()
        } catch (e: Exception) {
            // Nothing to do and nothing to say: the caller is on its way out of the composer.
        }
    }

    // -- the format on disk -------------------------------------------------------------------
    //
    // One self-describing header line, ONE styling line (#131), then the two texts back to back:
    //
    //     sterna-composer-resume/2 <token> <selStart> <selEnd> <bodyChars> <baselineChars>\n
    //     <body styling>|<baseline styling>\n
    //     <body><baseline>
    //
    // The styling line ([encodeRanges]) holds neither `:` nor `\n`, so the second newline is its
    // end whatever the body contains, and it is parsed STRICTLY: one unreadable character refuses
    // the record whole, like a bad header. A `/1` file (the build before #131) has no styling
    // line and still reads, with no styling.
    //
    // The lengths are in CHARACTERS (UTF-16 code units, i.e. Kotlin's String.length) on both
    // sides, so an emoji's surrogate pair counts as the two it is and the split lands where it
    // was made. The two texts are separated by nothing at all — a delimiter would have to be
    // escaped out of a body that can contain anything, including newlines and \r\n.
    //
    // ⛔ NOT DataOutputStream.writeUTF: it caps a string at 64 kB and the body that caused this
    // whole change is 324 kB. Encoding is UTF-8, named explicitly at both ends.

    private fun header(token: String, start: Int, end: Int, bodyLen: Int, baseLen: Int): String =
        "$MAGIC $token $start $end $bodyLen $baseLen\n"

    private fun parse(text: String, token: String): Resume? {
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val head = text.substring(0, newline).split(' ')
        if (head.size != 6 || (head[0] != MAGIC && head[0] != MAGIC_V1)) return null
        // The token, and the whole point of it: this file belongs to some OTHER saved composer.
        if (head[1] != token) return null
        val start = head[2].toIntOrNull() ?: return null
        val end = head[3].toIntOrNull() ?: return null
        val bodyLen = head[4].toIntOrNull() ?: return null
        val baseLen = head[5].toIntOrNull() ?: return null
        if (bodyLen < 0 || baseLen < 0) return null
        val styling: Pair<Map<Inline, List<Span>>, Map<Inline, List<Span>>>
        val rest: String
        if (head[0] == MAGIC) {
            val second = text.indexOf('\n', newline + 1)
            if (second < 0) return null
            styling = decodeRanges(text.substring(newline + 1, second), bodyLen, baseLen) ?: return null
            rest = text.substring(second + 1)
        } else {
            styling = emptyMap<Inline, List<Span>>() to emptyMap<Inline, List<Span>>()
            rest = text.substring(newline + 1)
        }
        // The verification the header exists for: a file cut short by a crash or a full disk has
        // fewer characters than it promised, and is refused whole rather than handed back short.
        if (rest.length != bodyLen + baseLen) return null
        return Resume(rest.substring(0, bodyLen), start, end, rest.substring(bodyLen), styling.first, styling.second)
    }

    private fun tempFile(): File = File(file.parentFile, file.name + ".tmp")

    companion object {
        /**
         * Erases EVERY composer slot in [dir], and only those (the prefix is the whole test).
         *
         * ⭐ Sweeping them all is SAFER than sweeping one name, not less so. It is called from a
         * launch with no saved state, where no `rememberSaveable` exists anywhere that could still
         * name a slot, so every file present is the orphan of a crash. With one file per composer
         * entry, a sweep that knew only a single name would leave the others' cleartext under
         * `filesDir` for good.
         */
        fun sweep(dir: File) {
            try {
                dir.listFiles()?.forEach { if (it.name.startsWith(COMPOSER_SLOT_PREFIX)) it.delete() }
            } catch (e: Exception) {
                // A launch is not the place to fail over housekeeping.
            }
        }

        private const val MAGIC = "sterna-composer-resume/2"

        /** The format before #131: no styling line. Still read, never written. */
        private const val MAGIC_V1 = "sterna-composer-resume/1"
    }
}

/**
 * The composer's body and baseline, held as ordinary Compose state so the ~71 sites that read and
 * write them do not change at all, plus the one flag that decides whether they get parked.
 */
class ComposerResumeState(
    body: TextFieldValue,
    baseline: String,
    /** The body's inline styling (#131). Held beside the text, not inside the `TextFieldValue`. */
    ranges: Map<Inline, List<Span>> = emptyMap(),
    /** The baseline's styling: the leave guard compares text AND styling against it. */
    baselineRanges: Map<Inline, List<Span>> = emptyMap(),
    /**
     * Names the record this composer parks, and it is minted ONCE — here — never per write.
     *
     * ⛔ THE ROTATION DEFECT LIVES HERE. The saver runs twice for every activity recreation: once
     * when the parcel is built (`SaveableStateHolderImpl.saveAll()`, which parcels a COPY) and
     * once at teardown (`SaveableStateProvider`'s `onDispose`, which calls `saveTo` again on the
     * live map). With a token minted inside [ComposerResumeSlot.write], the second run renamed the
     * file to something the parcel — already sealed — could never ask for, and the composer came
     * back with an empty body on EVERY rotation, at any size, in complete silence. Pressing HOME
     * never showed it: HOME does not tear the composition down.
     *
     * ⚠ Held for the life of ONE state object, and no longer: a restore builds a new composer with
     * a name of its own. Carrying the old name across the restore was tried and taken back out —
     * nothing could be made to depend on it, and a line that cannot be falsified is a comment
     * pretending to be code.
     */
    val token: String = newComposerToken(),
    /**
     * Whether THIS composer has already lost a body on its way into a parcel — a disk write that
     * failed on a text too big for the inline fallback (see [saveComposerResume]).
     *
     * ⛔ A `val`, and that is the whole of its design: it is raised at construction, by the restore
     * that reads the mark out of the parcel, and there is nowhere it can be lowered. What comes
     * back after a loss is NOT what was lost, so a later save that parks perfectly must keep
     * saying so — a flag that could fall would be clean again one rotation later, and the save
     * after that would destroy the server copy of a draft whose body is already gone.
     */
    val bodyWasLost: Boolean = false,
) {
    val bodyState: MutableState<TextFieldValue> = mutableStateOf(body)
    val baselineState: MutableState<String> = mutableStateOf(baseline)
    val rangesState: MutableState<Map<Inline, List<Span>>> = mutableStateOf(ranges)
    val baselineRangesState: MutableState<Map<Inline, List<Span>>> = mutableStateOf(baselineRanges)

    /**
     * Raised when the composer closes FOR GOOD (sent, saved, scheduled, deleted, abandoned).
     *
     * ⛔ Do not "simplify" this into a bare `clear()` at the exit. `SaveableStateProvider` saves a
     * navigation entry as it LEAVES the composition, so a `clear()` in the exit callback is
     * followed by a re-WRITE of the file when the screen tears down, and the user's cleartext —
     * including a composer that was encrypting — stays on disk. The flag makes the saver return
     * `null` and write nothing at all.
     */
    var closed: Boolean = false

    /** The composer is done: nothing more gets parked, and what was parked goes away now. */
    fun close(slot: ComposerResumeSlot) {
        closed = true
        slot.clear()
    }
}

/**
 * THE DECISION, on the way out. Two forms carry the text, and a third mark carries a LOSS:
 *
 *  - `t:<token>` — the ordinary one. The text is in the file; the parcel carries a dozen bytes.
 *  - `v:<start>:<end>:<bodyChars>:<styling>:<body><baseline>` — the FALLBACK, only when the file could not
 *    be written at all and the text is small enough to be carried the way it was carried before
 *    any of this existed.
 *  - `l:` — THE LOSS. On its own when neither of the two above could carry anything, and as a
 *    PREFIX on either of them (`l:t:…`, `l:v:…`) once this composer has lost a body.
 *
 * ⛔ Why the fallback is not optional. A failed write used to return `null`, so the saver parcelled
 * nothing: the composer came back EMPTY over an `applied` that had survived in the parcel,
 * `draftReopenView` drew that blank editor as if it were the draft, and `ComposeDirty` seeing no
 * change let the back gesture leave without a word. A short body never depended on a disk before,
 * and it must not start now.
 *
 * ⛔ Why it is bounded. [INLINE_LIMIT] characters is ~64 kB of UTF-16 parcel — far under the 1 MB
 * transaction buffer, and less than this screen was already sending yesterday. Above it there is
 * nothing safe left to CARRY: forcing the text into the parcel is the very kill this whole change
 * exists to prevent, so the text is dropped.
 *
 * ⛔ AND THE LOSS IS MARKED, BECAUSE THE FALLBACK IS NOT HARMLESS. The sentence that used to close
 * this comment — "the fallback can never make anything worse" — was false, and it cost a draft.
 * It compared this refusal with the crash it replaced, but `TransactionTooLargeException` took the
 * WHOLE parcel with it, `applied` included: the composer came back unprimed, the prefill replayed
 * the draft from the server, and nothing durable was lost. Returning `null` loses only the body and
 * leaves `applied` standing — the editor is redrawn empty over a draft nobody re-reads, `initialBody`
 * comes back empty too so `ComposeDirty` answers "unchanged", and the Save icon is still lit because
 * a recipient survived. That save goes out with `replacesEmailId` armed and a CLEAN verdict, and
 * `draftReplacementIsFaithful` never compares bodies: the server copy is destroyed and a bodiless one
 * takes its place. Irreversible, in silence, on a plain screen rotation.
 *
 * ⭐ So `l:` travels instead of the text, and it is STICKY: every later save of this composer keeps
 * it, even the ones that park perfectly. What came back after the loss is not what was lost.
 *
 * ⛔ `l:`, not `x:` — `x:whatever` is pinned in the list of values that must restore to nothing.
 */
internal fun saveComposerResume(state: ComposerResumeState, slot: ComposerResumeSlot): String? {
    if (state.closed) return null
    val body = state.bodyState.value
    val baseline = state.baselineState.value
    val parked = slot.write(
        token = state.token,
        body = body.text,
        selectionStart = body.selection.start,
        selectionEnd = body.selection.end,
        baseline = baseline,
        ranges = state.rangesState.value,
        baselineRanges = state.baselineRangesState.value,
    )
    // The styling line travels in the parcel with the text, so it counts against the bound.
    val encoded = encodeRanges(RichBody(body.text, state.rangesState.value), RichBody(baseline, state.baselineRangesState.value))
    val carried = when {
        parked -> TOKEN_FORM + state.token
        body.text.length + baseline.length + encoded.length <= INLINE_LIMIT ->
            INLINE_FORM + "${body.selection.start}:${body.selection.end}:${body.text.length}:" +
                encoded + ":" + body.text + baseline
        // Nothing can be carried: the disk refused and the text is over the bound. This is the
        // loss itself, and the ONLY thing left to do about it is to say so.
        else -> null
    }
    // ⛔ Never `null` from here on — the one `null` this function still returns is `state.closed`
    // above. A parcel with no value at all restores no state, and a restore that yields no state
    // takes the flag down with it.
    if (state.bodyWasLost || carried == null) return LOST_FORM + carried.orEmpty()
    return carried
}

/**
 * THE DECISION, on the way back: rebuild the composer's text, caret and baseline — or, when the
 * body was lost, rebuild a composer that KNOWS it, which is not the same thing as nothing.
 */
internal fun restoreComposerResume(saved: String, slot: ComposerResumeSlot): ComposerResumeState? {
    val lost = saved.startsWith(LOST_FORM)
    val form = if (lost) saved.substring(LOST_FORM.length) else saved
    val resume = when {
        form.startsWith(TOKEN_FORM) -> slot.read(form.substring(TOKEN_FORM.length))
        form.startsWith(INLINE_FORM) -> parseInlineResume(form)
        // Anything else is not ours: a value from an older build, a truncated one, or nothing at
        // all. A fresh composer is the only honest answer, and it is never an exception.
        else -> null
    }
    // ⛔ A RECORD THE PARCEL NAMES AND THAT CAME BACK `null` IS A LOSS, NOT AN ABSENCE. `t:<token>`
    // is a promise that a body WAS parked; [ComposerResumeSlot.read] collapses five different
    // causes into one `null` — the file no longer there, a header truncated by a crash or a full
    // disk, a foreign token (one slot per navigation entry, so a token that does not match means a
    // NEWER record took the place of the one the parcel names), an `IOException`, an unparsable
    // head — and not one of them means "there was nothing to restore". ⚠ WHAT takes the file away
    // in ordinary use is not established: on the bench the deletion was provoked. The five causes
    // are read here, the mechanism that fires them is not, and neither is claimed. On the bench a
    // 20 112 character server draft came back with an empty body, `To` and `Subject` intact, and
    // one tap on the still-lit Save icon left a 317 byte bodiless row where 21 260 bytes had been.
    // A site that cannot prove fidelity says `true`.
    //
    // ⛔ The bound is the EMPTY token, and only that: `t:` on its own names nothing, so it must
    // still restore to nothing, or a parcel that never promised a body arms the loss on a composer
    // that never parked one. ⚠ A `t:` carrying any token at all IS treated as a promise, including
    // one minted by an older build — the wrong side to err on is the other one, where the server
    // copy is destroyed.
    //
    // ⛔ And it is `resume == null` that says "lost", never an empty body that came back. An empty
    // body is a VALUE — the composer whose recipient is typed before its first word — and reading
    // the emptiness instead would arm the destroy licence on the most ordinary screen in the app
    // (pinned by `an empty body that was parked and read back whole has lost nothing`).
    val unreadable = resume == null && form.startsWith(TOKEN_FORM) && form.length > TOKEN_FORM.length
    // ⛔ `null` ONLY when there was no mark either. With the mark there, returning `null` would
    // drop the loss along with the text: `rememberSaveable` would fall back to its factory, the
    // composer would come back empty AND clean, and the next save would destroy the server copy of
    // a draft whose body is gone. An empty state carrying the flag is the whole fix.
    if (resume == null && !lost && !unreadable) return null
    // ⛔ A name of its own, not the one just read: `read` has CONSUMED the file, so the composer
    // rebuilt here parks a new record from scratch. What matters is that every save of THAT
    // composer names the same one — see [ComposerResumeState.token].
    return ComposerResumeState(
        TextFieldValue(
            resume?.body.orEmpty(),
            TextRange(resume?.selectionStart ?: 0, resume?.selectionEnd ?: 0),
        ),
        resume?.baseline.orEmpty(),
        ranges = resume?.ranges.orEmpty(),
        baselineRanges = resume?.baselineRanges.orEmpty(),
        bodyWasLost = lost || unreadable,
    )
}

/** Reads `v:<start>:<end>:<bodyChars>:<styling>:<body><baseline>`; only the first four `:` are
 *  separators (the styling line has none), so a body full of colons, newlines or surrogate pairs
 *  cannot be split in the wrong place. */
private fun parseInlineResume(saved: String): ComposerResumeSlot.Resume? {
    val at = INLINE_FORM.length
    val a = saved.indexOf(':', at)
    if (a < 0) return null
    val b = saved.indexOf(':', a + 1)
    if (b < 0) return null
    val c = saved.indexOf(':', b + 1)
    if (c < 0) return null
    val d = saved.indexOf(':', c + 1)
    if (d < 0) return null
    val start = saved.substring(at, a).toIntOrNull() ?: return null
    val end = saved.substring(a + 1, b).toIntOrNull() ?: return null
    val bodyLen = saved.substring(b + 1, c).toIntOrNull() ?: return null
    val payload = saved.substring(d + 1)
    if (bodyLen < 0 || bodyLen > payload.length) return null
    val styling = decodeRanges(saved.substring(c + 1, d), bodyLen, payload.length - bodyLen) ?: return null
    return ComposerResumeSlot.Resume(
        payload.substring(0, bodyLen), start, end, payload.substring(bodyLen), styling.first, styling.second,
    )
}

// -- the styling line (#131) ------------------------------------------------------------------

private val LETTER_OF = mapOf(Inline.BOLD to 'b', Inline.ITALIC to 'i', Inline.UNDERLINE to 'u', Inline.STRIKE to 's')

/**
 * `b0-4,9-12;i;u;s|b;i;u;s`: the body's four families in declaration order, `|`, the baseline's
 * four. Every span is `start-end` (half-open, characters). Neither `:` nor `\n` ever appears in
 * it, which is what lets the file and the parcel find its end without escaping anything.
 */
internal fun encodeRanges(body: RichBody, baseline: RichBody): String =
    encodeFamilies(body.ranges) + "|" + encodeFamilies(baseline.ranges)

private fun encodeFamilies(ranges: Map<Inline, List<Span>>): String =
    Inline.entries.joinToString(";") { kind ->
        LETTER_OF.getValue(kind) + ranges[kind].orEmpty().joinToString(",") { "${it.start}-${it.end}" }
    }

/**
 * STRICT inverse of [encodeRanges]: exactly two parts, exactly four families each, each opened by
 * its letter, every span `start-end` with `0 <= start <= end <= length` of the text it styles.
 * Anything else is `null`, and the caller refuses the whole record: a guessed styling is a
 * draft that is not what the user wrote.
 */
internal fun decodeRanges(
    line: String,
    bodyLength: Int,
    baselineLength: Int,
): Pair<Map<Inline, List<Span>>, Map<Inline, List<Span>>>? {
    val parts = line.split('|')
    if (parts.size != 2) return null
    val body = decodeFamilies(parts[0], bodyLength) ?: return null
    val baseline = decodeFamilies(parts[1], baselineLength) ?: return null
    return body to baseline
}

private fun decodeFamilies(text: String, length: Int): Map<Inline, List<Span>>? {
    val families = text.split(';')
    if (families.size != Inline.entries.size) return null
    val out = LinkedHashMap<Inline, List<Span>>()
    for ((kind, family) in Inline.entries.zip(families)) {
        if (family.isEmpty() || family[0] != LETTER_OF.getValue(kind)) return null
        val spans = family.substring(1)
        if (spans.isEmpty()) continue
        out[kind] = spans.split(',').map { pair ->
            val dash = pair.indexOf('-')
            if (dash < 0) return null
            val start = pair.substring(0, dash).toIntOrNull() ?: return null
            val end = pair.substring(dash + 1).toIntOrNull() ?: return null
            if (start < 0 || start > end || end > length) return null
            Span(start, end)
        }
    }
    return out
}

/** What goes into the activity's parcel for the composer's body: a short token, and that is all. */
fun composerResumeSaver(slot: ComposerResumeSlot): Saver<ComposerResumeState, String> = Saver(
    save = { saveComposerResume(it, slot) },
    restore = { restoreComposerResume(it, slot) },
)

/** Names one composer's slot for as long as that navigation entry lives. Parcelled (a dozen
 *  bytes), so the composer rebuilt after a process death still knows which file is its own. */
fun newComposerSlotId(): String = shortId(SLOT_IDS)

/** Names one composer's parked RECORD. ⛔ Distinct per composer, and the same for every save of
 *  the same one — see [ComposerResumeState.token] for the rotation that taught us the difference. */
fun newComposerToken(): String = shortId(TOKENS)

/**
 * The file one composer uses. ⛔ Through `applicationContext`, never the activity's own `Context`:
 * this object is built inside a composition that is torn down and rebuilt, and holding the activity
 * from a `remember` is how an activity leak starts.
 */
fun composerResumeFile(context: Context, slotId: String): File =
    composerResumeSlotFile(context.applicationContext.filesDir, slotId)

/** The same naming, on a plain directory, so the slot can be exercised without a `Context`. */
fun composerResumeSlotFile(dir: File, slotId: String): File = File(dir, COMPOSER_SLOT_PREFIX + slotId)

/** What [ComposerResumeSlot.sweep] recognises as ours, and nothing else in `filesDir` starts with it. */
const val COMPOSER_SLOT_PREFIX = "composer-resume-"

private const val TOKEN_FORM = "t:"
private const val INLINE_FORM = "v:"

/** The mark that says this composer lost a body. Composes with the two forms above, or stands
 *  alone when neither of them could carry anything — see [saveComposerResume]. */
private const val LOST_FORM = "l:"

/** ~64 kB of UTF-16 parcel: far under the 1 MB transaction buffer, and less than this screen was
 *  already sending before any of this existed. See [saveComposerResume] for why it is bounded. */
private const val INLINE_LIMIT = 16_384

private val SLOT_IDS = AtomicLong(0)

/** ⛔ Not `Math.random()`: two composers opened in the same millisecond must not be able to collide,
 *  and a counter is the only thing that says so with certainty. */
private val TOKENS = AtomicLong(0)

/** Short, and distinct on each call within a process — which is all either of its users needs. */
private fun shortId(sequence: AtomicLong): String =
    java.lang.Long.toUnsignedString(System.nanoTime(), 36) + "-" +
        java.lang.Long.toUnsignedString(sequence.incrementAndGet(), 36)
