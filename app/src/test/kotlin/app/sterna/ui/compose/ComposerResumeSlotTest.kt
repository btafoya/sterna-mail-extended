package app.sterna.ui.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A BEHAVIOUR test, not a lint: it builds a real [ComposerResumeSlot] on a real file and EXECUTES
 * the two decisions the composer now depends on, [saveComposerResume] and [restoreComposerResume].
 *
 * What it stands for. Replying to a ~324 kB message and pressing HOME killed the activity on a
 * `TransactionTooLargeException` (1 340 240 byte parcel) and the draft vanished in silence, because
 * the body travelled in the saved-state parcel twice over. It now travels through this slot, and
 * this file is what says the text, its caret and its baseline come back BYTE FOR BYTE — a resume
 * that quietly truncates or re-orders is the same lost draft with extra steps.
 */
class ComposerResumeSlotTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var slot: ComposerResumeSlot

    @Before
    fun setUp() {
        file = File(folder.newFolder("files"), "composer-resume")
        slot = ComposerResumeSlot(file)
    }

    // -- the round trip that closes the defect ---------------------------------------------------

    /** 400 000 characters, the size that could not fit in the parcel, out and back unchanged. */
    @Test
    fun `a very long body comes back whole, with its caret`() {
        val body = longBody(400_000)
        val baseline = "> quoted original, the baseline the leave guard compares against"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(123_456, 123_456)), baseline)

        val token = saveComposerResume(state, slot)
        assertTrue("a 400 000 character body must be parked, not refused", token != null)

        val back = restoreComposerResume(token!!, slot)
        assertTrue("the parked composer must come back", back != null)
        val restored = back!!.bodyState.value
        assertEquals("the body lost or gained characters", body.length, restored.text.length)
        assertEquals("the body's opening was rewritten", body.take(200), restored.text.take(200))
        assertEquals("the body's TAIL was cut — the parcel defect, moved to disk",
            body.takeLast(200), restored.text.takeLast(200))
        assertEquals("the whole body must be identical", body, restored.text)
        assertEquals("the caret came back somewhere else", TextRange(123_456, 123_456), restored.selection)
        assertEquals("the baseline is handed back as TEXT, never a digest — `insertSignatureBlock` " +
            "and `rewrite` are given it verbatim", baseline, back.baselineState.value)
    }

    /**
     * The four arguments [saveComposerResume] hands to [ComposerResumeSlot.write], pinned by making
     * every one of them different: a body and a baseline that cannot be confused, and a selection
     * whose start and end differ so a swapped pair cannot pass.
     */
    @Test
    fun `body, baseline and both ends of the selection each go to their own place`() {
        val body = "BODY-".repeat(2_000)
        val baseline = "BASELINE-".repeat(3)
        val state = ComposerResumeState(TextFieldValue(body, TextRange(17, 4_242)), baseline)

        val saved = saveComposerResume(state, slot)!!
        assertTrue(
            "a composer whose file WAS written must be parcelled as a token, not inline: `$saved`",
            saved.startsWith("t:"),
        )
        val resume = slot.read(saved.removePrefix("t:"))!!

        assertEquals("the body went to the baseline's place", body, resume.body)
        assertEquals("the baseline went to the body's place", baseline, resume.baseline)
        assertEquals("the selection START was taken from somewhere else", 17, resume.selectionStart)
        assertEquals("the selection END was taken from somewhere else", 4_242, resume.selectionEnd)
    }

    /** A body carrying every kind of character a mail actually contains, counting included. */
    @Test
    fun `newlines, CRLF, accents, cyrillic and an emoji all survive the count`() {
        val body = buildString {
            append("Bonjour Amélie,\n\nvoici la pièce jointe.\r\n")
            append("Приветствую вас, коллеги.\r\n")
            append("Ça va ? — oui. 😀🎉\n")
            append("x".repeat(50_000))
            append("\n😀")
        }
        val token = park(slot, body, 3, 9, "→ ligne de base 😀")
        val resume = slot.read(token)!!

        assertEquals("a surrogate pair was miscounted and the split moved", body, resume.body)
        assertEquals(body.length, resume.body.length)
        assertEquals("→ ligne de base 😀", resume.baseline)
        assertEquals(3, resume.selectionStart)
        assertEquals(9, resume.selectionEnd)
    }

    /** An untouched composer: empty is a value, not "nothing to save". */
    @Test
    fun `an empty body and an empty baseline round trip`() {
        val token = park(slot, "", 0, 0, "")
        val resume = slot.read(token)!!

        assertEquals("", resume.body)
        assertEquals("", resume.baseline)
        assertEquals(0, resume.selectionStart)
        assertEquals(0, resume.selectionEnd)
    }

    // -- refusing what is not ours ---------------------------------------------------------------

    /** The token is the proof that parcel and file describe the SAME saved composer. */
    @Test
    fun `a wrong token reads nothing and does not consume the file`() {
        val token = park(slot, "the real draft", 1, 1, "base")

        assertNull("a file written under another token must not be handed over", slot.read(token + "x"))
        assertTrue("a refused read must LEAVE the file: the token that does match may still ask",
            file.isFile)
        assertEquals("the rightful owner must still get its draft back", "the real draft",
            slot.read(token)!!.body)
    }

    /** A resume is consumed once — that is what "no file left after a resume" is measured on. */
    @Test
    fun `a successful read erases the file, so the second read finds nothing`() {
        val token = park(slot, "once and once only", 2, 2, "base")

        assertEquals("once and once only", slot.read(token)!!.body)
        assertFalse("a consumed resume must leave no cleartext behind on disk", file.isFile)
        assertNull("a consumed resume must not be replayed", slot.read(token))
    }

    /** A file cut short by a crash or a full disk is refused WHOLE, never handed back amputated. */
    @Test
    fun `a truncated file reads null instead of an amputated draft`() {
        val token = park(slot, "A".repeat(20_000), 5, 5, "base")
        val whole = file.readText(Charsets.UTF_8)
        file.writeText(whole.substring(0, whole.length / 2), Charsets.UTF_8)

        assertNull("half a file must be refused, not returned as a shorter draft", slot.read(token))
    }

    /** Not even a header: nothing to parse, and still no exception out of a restore. */
    @Test
    fun `a file with no header line reads null`() {
        file.parentFile!!.mkdirs()
        file.writeText("this is not a resume", Charsets.UTF_8)

        assertNull(slot.read("any-token"))
        assertNull(restoreComposerResume("any-token", slot))
    }

    /** Missing slot: a fresh composer, not a crash. */
    @Test
    fun `restoring with no file at all yields no state`() {
        assertNull(restoreComposerResume("any-token", slot))
    }

    // -- the closed flag, and what it protects ---------------------------------------------------

    /**
     * ⛔ The rule that keeps a sent — possibly ENCRYPTED — message's cleartext off the disk. The
     * navigation entry is saved as it leaves the composition, i.e. AFTER the composer closed, so
     * without this the very last thing the screen does is write the body back out.
     */
    @Test
    fun `a closed composer parks nothing and leaves no file`() {
        val state = ComposerResumeState(TextFieldValue("secret text", TextRange(0)), "base")
        state.closed = true

        assertNull("a closed composer must not be parked", saveComposerResume(state, slot))
        assertFalse("a closed composer must leave NO file on disk", file.isFile)
    }

    /** Closing also takes away what an earlier save had already parked. */
    @Test
    fun `close erases a slot that was already written`() {
        val state = ComposerResumeState(TextFieldValue("secret text", TextRange(0)), "base")
        assertTrue(saveComposerResume(state, slot) != null)
        assertTrue(file.isFile)

        state.close(slot)

        assertTrue("close must raise the flag that stops the teardown save", state.closed)
        assertFalse("close must take the parked cleartext away", file.isFile)
        assertNull("and nothing may be parked afterwards", saveComposerResume(state, slot))
    }

    // -- one record at a time --------------------------------------------------------------------

    /**
     * ⛔ THE ROTATION DEFECT, and the reason this file exists a second time. Read in androidx's
     * bytecode, not guessed: `SaveableStateHolderImpl.saveAll()` parcels a COPY of the saved
     * states, and `SaveableStateProvider`'s `onDispose` then calls `saveTo` a SECOND time on the
     * live map when the composition is torn down. So the saver runs TWICE per activity recreation
     * — once for the parcel, once at teardown — and the teardown run used to mint a brand-new
     * token, leaving the file under a name no parcel could ever ask for again.
     *
     * On the bench that was: rotate the screen, the composer comes back with its subject and an
     * EMPTY body, at any size, without an exception, a toast or a word. HOME never showed it,
     * because HOME does not tear the composition down.
     */
    @Test
    fun `the teardown save that follows the parcel cannot orphan what the parcel already names`() {
        val body = "the reply she is halfway through writing"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(4, 9)), "> the quoted original")

        val parcelled = saveComposerResume(state, slot)!!   // saveAll(): this is what the Bundle carries
        saveComposerResume(state, slot)                     // onDispose: saveTo() runs the saver again

        val back = restoreComposerResume(parcelled, slot)
        assertTrue(
            "the value the parcel carries must still open the file the teardown rewrote — otherwise " +
                "every rotation loses the body in silence",
            back != null,
        )
        assertEquals("the body must come back whole", body, back!!.bodyState.value.text)
        assertEquals("the baseline must come back whole", "> the quoted original", back.baselineState.value)
        assertEquals("the caret must come back where it was", TextRange(4, 9), back.bodyState.value.selection)
    }

    /** One composer is ONE record, however many times the saver is asked to park it. */
    @Test
    fun `every save of the same composer names the same record`() {
        val state = ComposerResumeState(TextFieldValue("a draft in flight", TextRange(2)), "base")

        val first = saveComposerResume(state, slot)
        val second = saveComposerResume(state, slot)

        assertEquals(
            "the parcel and the file may not diverge: a second save of the SAME composer must name " +
                "the record the first one named, or the first value — the one in the parcel — is " +
                "orphaned on disk",
            first, second,
        )
    }

    /**
     * ⛔ REPLACES "two writes give two tokens and the older one no longer reads", which pinned the
     * DEFECT: it read the saver's two runs per recreation as two records, and that is exactly what
     * cost the body on every rotation. Two COMPOSERS are two records; two saves of one composer are
     * one. A record another composer wrote is still refused, and still left where it was — that is
     * what [`a wrong token reads nothing and does not consume the file`] holds.
     */
    @Test
    fun `two composers are two records`() {
        val mine = ComposerResumeState(TextFieldValue("the long reply", TextRange(0)), "")
        val theirs = ComposerResumeState(TextFieldValue("the mailto: composer", TextRange(0)), "")

        assertNotEquals(
            "each composer must be identifiable on its own, or the one on top reads — and consumes " +
                "— the record of the one underneath",
            mine.token, theirs.token,
        )
    }

    /**
     * A slot holds ONE record: a newer one takes the place of the older, and the older name stops
     * opening it. ⭐ This is what the replaced test held that was true — the false half was reading
     * the saver's two runs per recreation as two records, which is what cost the body on rotation.
     */
    @Test
    fun `a newer record takes the older one's place, and the older name stops opening the slot`() {
        val older = park(slot, "first draft", 1, 1, "base")
        val newer = park(slot, "second draft", 2, 2, "base")

        assertNotEquals("two records written apart must be tellable apart", older, newer)
        assertNull("a stale name must not be served the newer record", slot.read(older))
        assertTrue("the refused stale read must not have consumed the file", file.isFile)
        assertEquals("second draft", slot.read(newer)!!.body)
    }

    /**
     * ⛔ A RENAME THAT IS REFUSED IS A FAILED SAVE. The write succeeds into the temporary and only
     * the rename is turned down (here the target is a directory; in the field: SELinux, a lock, a
     * quota reached in between). Reported as success, it hands the parcel a token for a file that
     * does not exist, the inline fallback is skipped, and the composer comes back EMPTY — at 126
     * characters as at 324 kB, with no exception and no word. The other failure path, the
     * exception, is what [`a body that could not be written falls back into the parcel, whole`]
     * holds; nothing held this one.
     */
    @Test
    fun `a rename the system refuses is a failed save, and falls back into the parcel`() {
        val dir = folder.newFolder("rename")
        val target = File(dir, "composer-resume-blocked")
        assertTrue("the target must be a directory for the rename to be refused", target.mkdirs())
        val blocked = ComposerResumeSlot(target)

        assertFalse(
            "a rename the system refused must be reported as a failure, never as a token pointing " +
                "at a file that is not there",
            blocked.write(newComposerToken(), "the draft she is writing", 3, 3, "base"),
        )
        assertFalse("no temporary may be left beside a refused rename",
            File(dir, "composer-resume-blocked.tmp").isFile)

        val state = ComposerResumeState(TextFieldValue("the draft she is writing", TextRange(3)), "base")
        val saved = saveComposerResume(state, blocked)
        assertTrue("a short body must reach the parcel inline when the file could not be written: `$saved`",
            saved != null && saved.startsWith("v:"))
        assertEquals("the draft she is writing", restoreComposerResume(saved!!, blocked)!!.bodyState.value.text)
    }

    /** No temporary is left lying around beside the slot. */
    @Test
    fun `clear removes the slot and its temporary`() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        park(slot, "draft", 0, 0, "base")
        tmp.writeText("leftover", Charsets.UTF_8)

        slot.clear()

        assertFalse(file.isFile)
        assertFalse("a leftover temporary is cleartext too", tmp.isFile)
    }

    // -- the parcel fallback, for the day the file cannot be written -----------------------------

    /**
     * ⛔ A `write` that fails must NOT come back as "nothing was saved". It used to: the saver then
     * parcelled `null`, the composer was rebuilt EMPTY over an `applied` that survived in the
     * parcel, `draftReopenView` drew that blank editor as if it were the draft, and `isDirty` being
     * false the back gesture left without a word. A short body costs a few kB of parcel — which is
     * what it cost yesterday, before any of this — so under a conservative bound it travels inline.
     */
    @Test
    fun `a body that could not be written falls back into the parcel, whole`() {
        val body = "short body, but every bit of it matters"
        val baseline = "> a different baseline entirely"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(7, 21)), baseline)

        val saved = saveComposerResume(state, unwritableSlot())!!
        assertTrue("the fallback must be the inline form, not a token: `$saved`", saved.startsWith("v:"))

        val back = restoreComposerResume(saved, unwritableSlot())!!
        assertEquals("the body came back changed", body, back.bodyState.value.text)
        assertEquals("the baseline came back changed", baseline, back.baselineState.value)
        assertEquals("the selection START was lost", 7, back.bodyState.value.selection.start)
        assertEquals("the selection END was lost", 21, back.bodyState.value.selection.end)
    }

    /** A body with the separator in it: the inline form is length-described, never split on a token. */
    @Test
    fun `the inline fallback survives colons, newlines and an emoji in the body`() {
        val body = "v:1:2:3: not a header\nnor is this: 😀\r\nfin"
        val baseline = "base: with a colon too 😀"
        val state = ComposerResumeState(TextFieldValue(body, TextRange(3, 9)), baseline)

        val back = restoreComposerResume(saveComposerResume(state, unwritableSlot())!!, unwritableSlot())!!

        assertEquals(body, back.bodyState.value.text)
        assertEquals(baseline, back.baselineState.value)
        assertEquals(TextRange(3, 9), back.bodyState.value.selection)
    }

    /**
     * Above the bound there is nothing safe left to do: the parcel is what killed the activity in
     * the first place. This is the ONE case that still loses text, and it is exactly what a crash
     * lost yesterday — the fallback may never make anything worse, which is its whole point.
     */
    @Test
    fun `a body over the inline bound is not forced into the parcel`() {
        val state = ComposerResumeState(TextFieldValue("x".repeat(16_385), TextRange(0)), "")

        assertNull(
            "16 384 characters is ~64 kB of UTF-16 parcel, far under the 1 MB buffer; past that " +
                "the fallback must refuse rather than kill the activity it exists to save",
            saveComposerResume(state, unwritableSlot()),
        )
    }

    /** Exactly at the bound, body plus baseline: accepted, so the bound is a bound and not a guess. */
    @Test
    fun `a body exactly at the inline bound still travels`() {
        val body = "y".repeat(16_000)
        val baseline = "z".repeat(384)
        val state = ComposerResumeState(TextFieldValue(body, TextRange(5)), baseline)

        val saved = saveComposerResume(state, unwritableSlot())!!
        val back = restoreComposerResume(saved, unwritableSlot())!!

        assertEquals(body, back.bodyState.value.text)
        assertEquals(baseline, back.baselineState.value)
    }

    /** Anything the saver does not recognise is nothing, and never an exception out of a restore. */
    @Test
    fun `an unrecognised parcel value restores nothing`() {
        for (junk in listOf("", "x:whatever", "v:", "v:12", "v:1:2", "v:1:2:3", "t:", "v:a:b:c:body")) {
            assertNull("`$junk` must restore to nothing, not to a mangled draft", restoreComposerResume(junk, slot))
        }
    }

    // -- one slot per navigation entry ------------------------------------------------------------

    /**
     * ⛔ TWO composers ARE reachable: `MainActivity` is singleTask, `onNewIntent` feeds
     * `pendingMailto`, and the host navigates to `compose` unguarded — a mailto: link or a share
     * received with the composer open stacks a SECOND one on the first. On one shared file, closing
     * the second erased the first's text, and the long reply underneath came back blank. Each entry
     * gets its own file, and closing one may not touch the other's.
     */
    @Test
    fun `two slots do not erase each other`() {
        val dir = folder.newFolder("two")
        val mine = ComposerResumeSlot(composerResumeSlotFile(dir, newComposerSlotId()))
        val theirs = ComposerResumeSlot(composerResumeSlotFile(dir, newComposerSlotId()))

        val token = park(mine, "the long reply underneath", 4, 4, "base")
        park(theirs, "the mailto: composer on top", 0, 0, "")
        theirs.clear()

        assertEquals("closing the composer on top must not empty the one underneath",
            "the long reply underneath", mine.read(token)!!.body)
    }

    @Test
    fun `slot ids are distinct`() {
        assertNotEquals(newComposerSlotId(), newComposerSlotId())
    }

    // -- the launch sweep -------------------------------------------------------------------------

    /** With no saved state nothing can consume ANY slot, so all of them are a crash's orphans. */
    @Test
    fun `the sweep erases every slot and nothing else`() {
        val dir = folder.newFolder("sweep")
        val first = composerResumeSlotFile(dir, newComposerSlotId())
        val second = composerResumeSlotFile(dir, newComposerSlotId())
        park(ComposerResumeSlot(first), "one", 0, 0, "")
        park(ComposerResumeSlot(second), "two", 0, 0, "")
        val neighbour = File(dir, "attachments.db")
        neighbour.writeText("not ours", Charsets.UTF_8)

        ComposerResumeSlot.sweep(dir)

        assertFalse("a leftover composer body is cleartext under filesDir", first.isFile)
        assertFalse("sweeping only one file is why this had to become a sweep", second.isFile)
        assertTrue("the sweep must not go near anything that is not a slot", neighbour.isFile)
    }

    /**
     * Parks one record and hands back the token it was parked UNDER — the argument, not something
     * the slot chose. A `write` that minted its own would hand this token to a file that does not
     * carry it, and every read below would come back empty.
     */
    private fun park(
        slot: ComposerResumeSlot,
        body: String,
        selectionStart: Int,
        selectionEnd: Int,
        baseline: String,
        token: String = newComposerToken(),
    ): String {
        assertTrue("the record had to be written for this test to mean anything",
            slot.write(token, body, selectionStart, selectionEnd, baseline))
        return token
    }

    /** A slot whose file cannot be written, ever: its parent is a regular file, not a directory. */
    private fun unwritableSlot(): ComposerResumeSlot {
        val blocker = File(folder.root, "blocker")
        if (!blocker.isFile) blocker.writeText("I am a file, not a directory", Charsets.UTF_8)
        return ComposerResumeSlot(File(blocker, "composer-resume-nope"))
    }

    private fun longBody(chars: Int): String {
        val line = "Le corps de ce message est long, et c'est tout le problème. "
        return buildString(chars + line.length) {
            var i = 0
            while (length < chars) {
                append(i).append(' ').append(line).append('\n')
                i++
            }
        }.substring(0, chars)
    }
}
