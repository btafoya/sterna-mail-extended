package app.sterna.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Span
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A reply whose body quotes decrypted text: what the composer remembers of it, what that does to the
 * lock, the one refusal the send, the draft and the schedule share, and where the body may be parked.
 * The last test is a source lint: `ComposeViewModel` and `ComposeScreen` cannot be built on the JVM,
 * so the calls are compared as whole lines, arguments included. It proves nothing about what runs.
 */
class DecryptedQuoteGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var slot: ComposerResumeSlot

    @Before
    fun setUp() {
        dir = folder.newFolder("files")
        file = composerResumeSlotFile(dir, "one")
        slot = ComposerResumeSlot(file)
    }

    private val refusal = R.string.compose_decrypted_quote_needs_encryption
    private val clearModes = listOf(PgpMode.OFF, PgpMode.SIGN, PgpMode.SMIME_SIGN)
    private val encryptingModes = listOf(PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED, PgpMode.SMIME_ENCRYPT)

    // --- the refusal ---------------------------------------------------------------------------

    @Test fun `the two lists below name every mode`() {
        assertEquals(PgpMode.entries.toSet(), (clearModes + encryptingModes).toSet())
    }

    @Test fun `a body quoting decrypted text is refused in every mode that leaves it readable`() {
        for (mode in clearModes) {
            assertEquals("$mode lets the quoted clear text out", refusal, decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode))
        }
    }

    @Test fun `it leaves in every mode that encrypts`() {
        for (mode in encryptingModes) {
            assertNull("$mode encrypts and is refused", decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode))
        }
    }

    @Test fun `a body that quotes nothing decrypted is never refused`() {
        for (mode in PgpMode.entries) {
            assertNull("$mode refuses an ordinary message", decryptedQuoteRefusal(quotesDecrypted = false, pgpMode = mode))
        }
    }

    @Test fun `a lock reopened by hand is refused`() {
        assertEquals(refusal, decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = nextPgpMode(PgpMode.ENCRYPT)))
    }

    @Test fun `no mode keeps such a body as a draft, the two rules together`() {
        for (mode in PgpMode.entries) {
            val kept = draftSaveAllowed(mode) && decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode) == null
            assertFalse("$mode stores the quoted clear text as a draft", kept)
        }
    }

    @Test fun `no mode schedules such a body, the two rules together`() {
        for (mode in PgpMode.entries) {
            val refused = decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode)
                ?: scheduleRefusal("alice@example.org", "", "", mode)
            assertNotNull("$mode schedules the quoted clear text", refused)
        }
        assertEquals(
            "the open lock is refused for the quote, not for a reason the addresses could lift",
            refusal,
            decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = PgpMode.OFF) ?: scheduleRefusal("", "", "", PgpMode.OFF),
        )
    }

    // --- the lock ------------------------------------------------------------------------------

    private fun settled(
        quotesDecrypted: Boolean,
        accountDefault: Boolean = false,
        current: PgpMode = PgpMode.OFF,
        available: Boolean = true,
        userSet: Boolean = false,
    ) = lockRefresh(
        current = current,
        available = available,
        accountConfigured = true,
        encryptByDefault = encryptByDefaultFor(accountDefault, quotesDecrypted),
        userSet = userSet,
        smimeOffered = listOf(PgpMode.SMIME_SIGN, PgpMode.SMIME_ENCRYPT),
    ).mode

    @Test fun `a decrypted quote closes the lock on an account that does not encrypt by default`() {
        assertEquals(PgpMode.ENCRYPT, settled(quotesDecrypted = true))
        assertEquals(PgpMode.ENCRYPT, settled(quotesDecrypted = true, current = PgpMode.SIGN))
    }

    @Test fun `without one the lock is the account's`() {
        assertEquals(PgpMode.OFF, settled(quotesDecrypted = false))
        assertEquals(PgpMode.ENCRYPT, settled(quotesDecrypted = false, accountDefault = true))
    }

    @Test fun `a lock set by hand is left as set, and it is the refusal that answers`() {
        val mode = settled(quotesDecrypted = true, userSet = true)
        assertEquals(PgpMode.OFF, mode)
        assertEquals(refusal, decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode))
    }

    @Test fun `with no provider the lock stays open, and it is the refusal that answers`() {
        val mode = settled(quotesDecrypted = true, available = false)
        assertEquals(PgpMode.OFF, mode)
        assertEquals(refusal, decryptedQuoteRefusal(quotesDecrypted = true, pgpMode = mode))
    }

    @Test fun `an S-MIME mode is not rewritten`() {
        assertEquals(PgpMode.SMIME_ENCRYPT, settled(quotesDecrypted = true, current = PgpMode.SMIME_ENCRYPT, userSet = true))
        assertEquals(PgpMode.SMIME_SIGN, settled(quotesDecrypted = true, current = PgpMode.SMIME_SIGN, userSet = true))
    }

    @Test fun `the lock is settled the one time the fact is raised, and never over an S-MIME mode`() {
        for (mode in PgpMode.entries) {
            val smime = mode == PgpMode.SMIME_SIGN || mode == PgpMode.SMIME_ENCRYPT
            assertEquals("$mode, as the fact is raised", !smime, settlesLockOnQuote(raised = true, pgpMode = mode))
            assertFalse("$mode, with nothing raised", settlesLockOnQuote(raised = false, pgpMode = mode))
        }
    }

    // --- the fact ------------------------------------------------------------------------------

    private val decrypted = QuoteSource(Email(id = "1"), fromDecrypted = true, nothingToQuote = false)
    private val asFetched = QuoteSource(Email(id = "1"), fromDecrypted = false, nothingToQuote = false)

    @Test fun `a decrypted quote put in the body is remembered`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(decrypted)
        assertFalse("a quote only offered is not in the body yet", guard.held.value)
        assertTrue("the landing that raises the fact does not say so", guard.landed())
        assertTrue("the composer forgot that its quote was decrypted", guard.held.value)
        assertFalse("a second landing raises it again", guard.landed())
    }

    @Test fun `a quote of the server's original is not`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(asFetched)
        assertFalse(guard.landed())
        assertFalse(guard.held.value)
    }

    @Test fun `an encrypted original left unquoted is not`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(quoteSource(ComposeOpening.REPLY, Email(id = "1"), CryptoKind.PGP_ENCRYPTED) { null })
        assertFalse(guard.landed())
        assertFalse("a reply that quotes nothing is held to encryption", guard.held.value)
    }

    @Test fun `a landing with nothing offered is not`() {
        val guard = DecryptedQuoteGuard()
        guard.landed()
        assertFalse(guard.held.value)
    }

    @Test fun `once remembered nothing but lower() lowers it`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(decrypted)
        guard.landed()
        guard.offer(asFetched)
        guard.landed()
        guard.restore(false)
        assertTrue(guard.held.value)
        guard.lower()
        assertFalse("the user's confirmation left the quote guarded", guard.held.value)
    }

    @Test fun `once lowered the same quote landing again does not raise it`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(decrypted)
        guard.landed()
        guard.lower()
        assertFalse("a second landing of the quote raised the fact over the user's answer", guard.landed())
        assertFalse(guard.held.value)
    }

    @Test fun `an Undo of a send that left guarded raises it again after a lowering`() {
        val guard = DecryptedQuoteGuard()
        guard.offer(decrypted)
        guard.landed()
        guard.lower()
        guard.restore(true)
        assertTrue(guard.held.value)
    }

    // --- the question ----------------------------------------------------------------------------

    private fun heldGuard() = DecryptedQuoteGuard().apply { offer(decrypted); landed() }

    @Test fun `confirming lowers the guard and hands back the gesture asked`() {
        QuoteInClearGesture.entries.forEach { gesture ->
            val guard = heldGuard()
            val question = QuoteInClearQuestion(guard)
            question.ask(gesture)
            assertEquals(gesture, question.pending.value)
            assertEquals(gesture, question.confirm(refusal))
            assertFalse("$gesture: the confirmation did not lower the guard", guard.held.value)
            assertNull("$gesture: the question stayed up after its answer", question.pending.value)
        }
    }

    @Test fun `going back leaves the guard held`() {
        val guard = heldGuard()
        val question = QuoteInClearQuestion(guard)
        question.ask(QuoteInClearGesture.SEND)
        question.dismiss()
        assertNull(question.pending.value)
        assertTrue(guard.held.value)
    }

    @Test fun `a confirmation with nothing asked lowers nothing and resumes nothing`() {
        val guard = heldGuard()
        assertNull(QuoteInClearQuestion(guard).confirm(refusal))
        assertTrue(guard.held.value)
    }

    @Test fun `the question withdraws itself once nothing refuses the body, the guard kept`() {
        val guard = heldGuard()
        val question = QuoteInClearQuestion(guard)
        question.ask(QuoteInClearGesture.SCHEDULE)
        question.refusalIs(refusal)
        assertEquals("the question left while the body was still refused", QuoteInClearGesture.SCHEDULE, question.pending.value)
        question.refusalIs(null)
        assertNull("the question stayed up over a lock that turned encrypting", question.pending.value)
        assertTrue("withdrawing the question lowered the guard", guard.held.value)
    }

    @Test fun `a confirmation once nothing refuses the body lowers nothing and resumes nothing`() {
        val guard = heldGuard()
        val question = QuoteInClearQuestion(guard)
        question.ask(QuoteInClearGesture.DRAFT)
        assertNull("a gesture was resumed that no longer needed the answer", question.confirm(null))
        assertNull(question.pending.value)
        assertTrue("an answer to a question no longer due lowered the guard", guard.held.value)
    }

    @Test fun `a refused body asks, and only an accepted one goes on`() {
        QuoteInClearGesture.entries.forEach { gesture ->
            val asked = mutableListOf<QuoteInClearGesture>()
            var proceeded = 0
            askBeforeQuoteInClear(refusal, gesture, { asked += it }, { proceeded++ })
            assertEquals(listOf(gesture), asked)
            assertEquals("$gesture went on without asking", 0, proceeded)
            askBeforeQuoteInClear(null, gesture, { asked += it }, { proceeded++ })
            assertEquals("$gesture asked of a body nothing refuses", listOf(gesture), asked)
            assertEquals(1, proceeded)
        }
    }

    @Test fun `an undone send comes back as it left`() {
        assertTrue(DecryptedQuoteGuard().also { it.restore(true) }.held.value)
        assertFalse(DecryptedQuoteGuard().also { it.restore(false) }.held.value)
    }

    // --- the disk ------------------------------------------------------------------------------

    private val quote = "On Monday Leo wrote:\n> the decrypted secret"
    private val baseline = "> the decrypted secret, as the baseline"

    private fun composer() = ComposerResumeState(
        TextFieldValue(quote, TextRange(3, 7)),
        baseline,
        ranges = mapOf(Inline.BOLD to listOf(Span(0, 2))),
    )

    private fun onDisk(): String = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test fun `the decision`() {
        assertFalse(bodyMayBeParkedOnDisk(quotesDecrypted = true))
        assertTrue(bodyMayBeParkedOnDisk(quotesDecrypted = false))
    }

    @Test fun `a body quoting decrypted text is parked nowhere on disk, nor in the parcel`() {
        val memory = ComposerBodyMemory { true }
        val parcel = saveComposerResume(composer(), slot, memory)

        assertEquals("files were written beside the composer", emptyList<String>(), dir.list().orEmpty().toList())
        assertFalse("the quoted clear text is on disk", "secret" in onDisk())
        assertEquals("the parcel carries more than the mark", "m:", parcel)
    }

    @Test fun `a body over the inline bound is no exception`() {
        val long = ComposerResumeState(TextFieldValue("secret ".repeat(10_000)), "")
        assertEquals("m:", saveComposerResume(long, slot, ComposerBodyMemory { true }))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test fun `a rotation gives it back whole`() {
        val memory = ComposerBodyMemory { true }
        val back = restoreComposerResume(saveComposerResume(composer(), slot, memory)!!, slot, memory)!!

        assertEquals(quote, back.bodyState.value.text)
        assertEquals(TextRange(3, 7), back.bodyState.value.selection)
        assertEquals(baseline, back.baselineState.value)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 2))), back.rangesState.value)
        assertFalse("a body that came back is not a lost one", back.bodyWasLost)
    }

    @Test fun `after a process death the body is gone, said as a loss, and nothing brings the quote back`() {
        val parcel = saveComposerResume(composer(), slot, ComposerBodyMemory { true })!!
        val reborn = ComposerBodyMemory { false }
        val back = restoreComposerResume(parcel, slot, reborn)!!

        assertEquals("", back.bodyState.value.text)
        assertEquals("", back.baselineState.value)
        assertTrue("an emptied composer came back looking clean", back.bodyWasLost)
    }

    @Test fun `a record parked before the quote landed is erased by the next save`() {
        var quotesDecrypted = false
        val memory = ComposerBodyMemory { quotesDecrypted }
        val state = ComposerResumeState(TextFieldValue("-- \nme"), "-- \nme")
        saveComposerResume(state, slot, memory)
        assertTrue("an ordinary reply is parked on disk as before", file.isFile)

        quotesDecrypted = true
        state.bodyState.value = TextFieldValue(quote)
        assertEquals("m:", saveComposerResume(state, slot, memory))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test fun `an ordinary body is still parked on disk and read back from it`() {
        val memory = ComposerBodyMemory { false }
        val state = ComposerResumeState(TextFieldValue("hello", TextRange(5)), "")
        val parcel = saveComposerResume(state, slot, memory)!!

        assertEquals("t:" + state.token, parcel)
        assertTrue(file.isFile)
        assertEquals("hello", restoreComposerResume(parcel, slot, ComposerBodyMemory { false })!!.bodyState.value.text)
    }

    @Test fun `what the memory holds is handed back once`() {
        val memory = ComposerBodyMemory { true }
        val parcel = saveComposerResume(composer(), slot, memory)!!
        restoreComposerResume(parcel, slot, memory)
        assertEquals("", restoreComposerResume(parcel, slot, memory)!!.bodyState.value.text)
    }

    @Test fun `the screen's saver parks such a body in the memory it was given, and reads it back from there`() {
        val memory = ComposerBodyMemory { true }
        val saver = composerResumeSaver(slot, memory)
        val parcel = with(saver) { SaverScope { true }.save(composer()) }

        assertEquals("the saver wrote the body somewhere its memory does not decide", "m:", parcel)
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        assertEquals(quote, saver.restore(parcel!!)!!.bodyState.value.text)
    }

    // --- the wiring, as text -------------------------------------------------------------------

    @Test fun `the composer feeds each decision the fact and the mode in force`() {
        val vm = code(VIEW_MODEL)
        assertEquals(
            "the refusal is asked somewhere else than the question, the send, the draft and the schedule, or of " +
                "something other than the fact and the mode: the send must ask the mode it captured, never a second read",
            listOf(
                QuoteInClearWiringTest.CONFIRM,
                QuoteInClearWiringTest.WITHDRAW,
                "decryptedQuoteRefusal(decryptedQuote.held.value, mode)?.let { error(getApplication<Application>().getString(it)) }",
                "val refusal = decryptedQuoteRefusal(decryptedQuote.held.value, _pgpMode.value) ?: scheduleRefusal(to, cc, bcc, _pgpMode.value) ?: return true",
                "decryptedQuoteRefusal(decryptedQuote.held.value, _pgpMode.value)?.let {",
            ),
            vm.filter { "decryptedQuoteRefusal(" in it },
        )
        assertEquals(
            "the lock no longer reads its default through encryptByDefaultFor at its three sites",
            listOf(
                "private fun encryptsByDefault(account: StoredAccount?): Boolean =",
                "encryptByDefault = encryptsByDefault(account),",
                "(encryptsByDefault(account) || _pgpMode.value.encrypts)",
                "if (pgpModeUserSet || !encryptsByDefault(account)) return",
            ),
            vm.filter { "encryptsByDefault(" in it },
        )
        assertEquals(
            listOf("encryptByDefaultFor(account?.pgpEncryptByDefault == true, decryptedQuote.held.value)"),
            vm.filter { "encryptByDefaultFor(" in it },
        )
        assertEquals(
            "the fact is no longer fed by the quote's source, carried by both Undo records and read back from one",
            listOf(
                "if (!settlesLockOnQuote(decryptedQuote.landed(), _pgpMode.value)) return",
                "decryptedQuote.restore(d.quotesDecrypted)",
                "?.also { decryptedQuote.offer(it) }",
            ),
            vm.filter { "decryptedQuote.offer(" in it || "decryptedQuote.landed(" in it || "decryptedQuote.restore(" in it },
        )
        assertEquals(
            "one of the two Undo records no longer carries the fact with the body",
            List(2) { "quotesDecrypted = decryptedQuote.held.value," },
            vm.filter { it.startsWith("quotesDecrypted = ") },
        )
        val landing = vm.indexOf("fun onQuoteInBody() {")
        assertEquals(
            "a landed quote no longer settles the lock at once, on the availability already known",
            listOf(
                "fun onQuoteInBody() {",
                "if (!settlesLockOnQuote(decryptedQuote.landed(), _pgpMode.value)) return",
                "settleLock(pgpAccount(), _pgpAvailable.value, smimeOffered = emptyList())",
                "}",
            ),
            vm.subList(landing, landing + 4),
        )
        assertEquals(
            "the screen is shown something else than the fact",
            listOf("val quotesDecrypted: StateFlow<Boolean> = decryptedQuote.held"),
            vm.filter { it.startsWith("val quotesDecrypted") },
        )
        val slotCode = code(RESUME_SLOT)
        val saver = slotCode.indexOfFirst { it.startsWith("fun composerResumeSaver(") }
        assertEquals(
            "the saver no longer hands both decisions the memory it was given",
            listOf(
                "fun composerResumeSaver(slot: ComposerResumeSlot, memory: ComposerBodyMemory): Saver<ComposerResumeState, String> = Saver(",
                "save = { saveComposerResume(it, slot, memory) },",
                "restore = { restoreComposerResume(it, slot, memory) },",
                ")",
            ),
            slotCode.subList(saver, saver + 4),
        )
        assertEquals(
            "the body's memory no longer reads the fact",
            listOf("val bodyMemory = ComposerBodyMemory { decryptedQuote.held.value }"),
            vm.filter { "ComposerBodyMemory" in it },
        )

        val screen = code(SCREEN)
        assertEquals(
            listOf("val quoteRefusal = decryptedQuoteRefusal(quotesDecrypted, pgpMode)"),
            screen.filter { "decryptedQuoteRefusal(" in it },
        )
        assertEquals(
            listOf("val quotesDecrypted by viewModel.quotesDecrypted.collectAsStateWithLifecycle()"),
            screen.filter { it.startsWith("val quotesDecrypted") },
        )
        assertEquals(
            "the refusal is no longer the leave dialog's body, the composer's standing line and the three gestures' question",
            listOf(
                "val quoteRefusal = decryptedQuoteRefusal(quotesDecrypted, pgpMode)",
                QuoteInClearWiringTest.SEND_ASKS,
                "val mayKeepDraft = draftSaveAllowed(pgpMode) && quoteRefusal == null",
                "quoteRefusal ?: when (wording) {",
                QuoteInClearWiringTest.DRAFT_TAP,
                QuoteInClearWiringTest.SCHEDULE_TAP,
                "quoteRefusal?.let { refusal ->",
                "stringResource(refusal),",
            ),
            screen.filter { "quoteRefusal" in it || it == "stringResource(refusal)," },
        )
        assertEquals(
            "the leave dialog offers to keep a draft of a body the save would refuse",
            listOf("val mayKeepDraft = draftSaveAllowed(pgpMode) && quoteRefusal == null"),
            screen.filter { it.startsWith("val mayKeepDraft = ") },
        )
        val landings = screen.indices.filter { "viewModel.onQuoteInBody()" in screen[it] }
        assertEquals(
            "the composer is told a quote landed at two places, each just above the write it describes",
            listOf(
                listOf("if (it.quoted) viewModel.onQuoteInBody()", "body = TextFieldValue(it.body, TextRange(caret ?: 0))"),
                listOf("viewModel.onQuoteInBody()", "body = TextFieldValue(quote, TextRange(0))"),
            ),
            landings.map { screen.subList(it, it + 2) },
        )
        assertEquals(
            "the quote is announced outside the branch that drops it in",
            "if (canApplyReplyQuote(applied, body.text, initialBody)) {",
            screen[landings.last() - 1],
        )
    }

    private fun code(path: String): List<String> = File(root, path).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val RESUME_SLOT = "app/src/main/kotlin/app/sterna/ui/compose/ComposerResumeSlot.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error("Cannot find $VIEW_MODEL from ${File("").absolutePath}")
        }
    }
}
