package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` as text and proves nothing
 * about what runs. Same instrument and same disclaimer as [ComposeViewModelWiringTest]: there is
 * no Robolectric here and `ComposeViewModel` is an `AndroidViewModel`, so nothing in this module
 * can instantiate it. The decision it guards is executed elsewhere —
 * `UidValidity.destroyableUnderNumbering` runs in `ImapDestroyUnderNumberingTest`; what no
 * executable test can see is WHICH number this class hands it, and WHEN it read it.
 *
 * The defect, in one sentence: an IMAP `UID EXPUNGE` only means anything inside one UIDVALIDITY,
 * and `MailRepository.destroyDraft` used to look that number up at execution. A background pass
 * that noticed the Drafts folder renumbered has already written the NEW number to the store by
 * then, so the guard compared it with itself, said SAME, and expunged a UID from the OLD numbering
 * — destroying whatever message now carries that number in Drafts, silently. The number therefore
 * has to be FROZEN at the moment this composer reads the draft's id, carried in memory beside
 * `editingDraftId`, and handed to the repository. Re-reading it at save time is the defect, not a
 * refinement of it: the whole value of freezing is that it happened BEFORE the renumbering.
 *
 * Whole lines are compared, never `contains`: a substring check is blind to any mutation that
 * LENGTHENS the line, and `?: repo.recordedUidValidityForDraft(credentials, draftId)` appended to
 * a re-read would sail straight through one.
 *
 * WHAT IT DOES NOT COVER: that any of this is reached at runtime, that the repository does
 * anything with the value (pinned in `core/data`), and the outbox reopen path, which deliberately
 * still freezes null — V2 carries the number with the outbox row.
 */
class DraftNumberingFreezeWiringTest {

    @Test fun `the numbering is frozen where the draft's id is read, and nowhere else`() {
        val prepare = codeLines(bodyOf("prepare"))
        val id = prepare.indexOf(REMEMBER_ID)
        val freeze = prepare.indexOf(FREEZE)
        assertTrue(
            "prepare() must still remember the reopened draft's id as '$REMEMBER_ID' — this rule " +
                "pins the freeze NEXT TO it and must fail loudly rather than pin it beside " +
                "something it never located. Body was:\n${prepare.joinToString("\n")}",
            id >= 0,
        )
        assertTrue(
            "prepare() must still freeze the numbering as '$FREEZE'. Body was:\n" +
                prepare.joinToString("\n"),
            freeze >= 0,
        )
        assertEquals(
            "every line of prepare() that arms the destroy — the id — or freezes the numbering it " +
                "belongs to, whole and IN ORDER: the restore branch reads both back from the " +
                "undone send; the reopen branch freezes the numbering FIRST and arms the id LAST " +
                "(#63: the id is set only after the attachment carry has answered, so a send " +
                "tapped during that download destroys nothing). The numbering must be read at the " +
                "earliest possible moment and never again: anywhere later is a different number — " +
                "the one a refresh recorded after the renumbering, i.e. exactly the value that " +
                "lets the expunge through on another message. An extra line here (an alias, a " +
                "'re-read to be safe') is the defect restored. Body was:\n" +
                prepare.joinToString("\n"),
            listOf(
                "editingDraftId = d.draftEmailId",
                "editingDraftUidValidity = d.draftUidValidity",
                "editingDraftAccountId = d.fromAccountId",
                FREEZE,
                FREEZE_ACCOUNT,
                REMEMBER_ID,
            ),
            prepare.filter {
                it.startsWith("editingDraftId =") ||
                    it.startsWith("editingDraftUidValidity =") ||
                    it.startsWith("editingDraftAccountId =")
            },
        )
        assertTrue(
            "…and in the reopen branch the freeze must come BEFORE the id is armed: the id read " +
                "and the number must belong to the same numbering, and the earliest read is the " +
                "safest one. Body was:\n" + prepare.joinToString("\n"),
            freeze < id,
        )
        // ⛔ And the ACCOUNT those two belong to is frozen in the same breath, for the same reason.
        // The "From" picker below covers EVERY account and resets nothing: read at save time
        // instead, the account is whatever identity is on show, and the expunge of the id and the
        // numbering frozen here leaves for a server that never issued them — JMAP spares the id
        // (the emptied draft comes back on the next sync), IMAP compares two NUMBERS and an equal
        // UIDVALIDITY expunges in the OTHER account's Drafts. Placed after the attachment carry it
        // is no longer "the same breath": the freeze pair would straddle a download of unbounded
        // length, on the one path where the account can still change under it.
        val carry = prepare.indexOfFirst { "carryDraftAttachments(" in it }
        val account = prepare.indexOf(FREEZE_ACCOUNT)
        assertTrue(
            "prepare() must still carry the reopened draft's attachments, and must still freeze " +
                "the account as '$FREEZE_ACCOUNT' — read off the credentials that have just read " +
                "the draft, so the two can never disagree. Body was:\n" + prepare.joinToString("\n"),
            carry >= 0 && account >= 0,
        )
        assertTrue(
            "…and the account freeze belongs WITH the numbering — after it, and before both the " +
                "attachment carry and the id. Body was:\n" + prepare.joinToString("\n"),
            freeze < account && account < carry && carry < id,
        )
    }

    /**
     * The account is frozen exactly twice — the restore, and the reopen — and never looked up
     * again on the way out.
     *
     * ⛔ The defect it closes: the empty-save expunged under `credentials()`, i.e. the account of
     * the identity the "From" picker was showing, over an id and a numbering frozen at the open.
     * Any THIRD write here is that re-read wearing the frozen field's name — `editingDraftAccountId
     * = _selectedFrom.value?.accountId` slipped in beside the picker, and every rule in this suite
     * stays green while the destroy goes back to the wrong server.
     */
    @Test fun `the account is frozen where the numbering is, and written nowhere else`() {
        val lines = codeLines(source())
        assertEquals(
            "exactly two writes: the restore of a message coming back from the queue, and the " +
                "freeze taken when a saved draft is opened. \u26d4 A qualified receiver counts as " +
                "a write, which is why this looks for the assignment and not for the start of the " +
                "line: `this.editingDraftAccountId = option.accountId` written into selectFrom " +
                "sails past a startsWith, and it is the reported defect itself — the picker " +
                "re-freezing the account it was never allowed to touch. Lines were:\n" +
                lines.filter { "editingDraftAccountId" in it }.joinToString("\n"),
            listOf("editingDraftAccountId = d.fromAccountId", FREEZE_ACCOUNT),
            lines.filter { ASSIGNS_ACCOUNT.containsMatchIn(it) },
        )
        assertEquals(
            "…and it is declared once, as a plain field of this class. Lines were:",
            listOf("private var editingDraftAccountId: String? = null"),
            lines.filter { it.startsWith("private var editingDraftAccountId") },
        )
        val at = lines.indexOf("private var editingDraftAccountId: String? = null")
        assertTrue(
            "⛔ …and it is a FIELD, not a computed one: `get() = _selectedFrom.value?.accountId ?: " +
                "field` compiles, leaves every other rule in this file green, and restores the " +
                "whole defect one layer beneath them — the expunge would again be addressed to " +
                "whatever identity the \"From\" picker happens to be showing, while the two rules " +
                "above go on pinning a freeze that no longer decides anything. The line after the " +
                "declaration was: " + lines.getOrNull(at + 1),
            lines.getOrNull(at + 1)?.let { !it.startsWith("get()") && !it.startsWith("set(") } == true,
        )
    }

    /**
     * An assignment to the frozen account, however it is written — bare, or through a qualified
     * `this.`, which a `startsWith` never sees.
     */
    private val ASSIGNS_ACCOUNT = Regex("""^(this\.)?editingDraftAccountId\s*=[^=]""")

    @Test fun `it is never read again at save time`() {
        val everywhere = codeLines(source()).filter { "recordedUidValidityForDraft" in it }
        assertEquals(
            "the accessor may be called EXACTLY ONCE in this class, at the read. A second call — " +
                "in saveDraft, in submit, in a helper 'to be safe' — re-opens the whole defect: it " +
                "answers the numbering of the renumbering the guard exists to refuse, and every " +
                "test on the repository side stays green. It is the same reason " +
                "InboxViewModel.flushPendingDestroy is deliberately not `suspend`. Lines were:\n" +
                everywhere.joinToString("\n"),
            listOf(FREEZE),
            everywhere,
        )
    }

    @Test fun `both destroying saves are handed the frozen numbering, not a fresh one`() {
        val save = codeLines(bodyOf("saveDraft"))
        assertEquals(
            "an emptied draft is DISCARDED — a permanent expunge (#69) — and must carry the number " +
                "frozen when the composer opened it, captured beside the id before abandon() runs. " +
                "Body was:\n${save.joinToString("\n")}",
            listOf(
                "val original = editingDraftId",
                "val originalUidValidity = editingDraftUidValidity",
                "val originalAccountId = editingDraftAccountId",
            ),
            save.filter { it.startsWith("val original") },
        )
        assertEquals(
            "and the discard call must be handed that captured value — nothing else, and no `null` " +
                "placeholder: `null` destroys nothing, so an emptied draft would come back on the " +
                "next sync. Body was:\n${save.joinToString("\n")}",
            listOf("repo.discardDraft(credentials, original, originalUidValidity)"),
            save.filter { "repo.discardDraft(" in it },
        )
        assertEquals(
            "re-saving an opened draft destroys the server copy it replaces, so repo.saveDraft must " +
                "carry the frozen numbering for it too, under its own named argument. Passing " +
                "editingDraftId's numbering read afresh here is the defect this fix closes. It now " +
                "travels inside the pair the account guard is asked on (⛔ pinned right below, or " +
                "this rule could be satisfied by a pair built from a fresh read). " +
                "Body was:\n${save.joinToString("\n")}",
            listOf(
                "replacesEmailId = replaces.emailId,",
                "replacesUidValidity = replaces.uidValidity,",
            ),
            save.filter { it.startsWith("replaces") },
        )
        assertEquals(
            "…and that pair is built from the two FROZEN values, in one statement: the id read at " +
                "the open and the numbering read beside it (#99). Rebuilt from " +
                "`recordedUidValidityForDraft` here — or from any other read — this is the whole " +
                "defect back, one layer beneath a rule that goes on pinning the argument names. " +
                "Body was:\n${save.joinToString("\n")}",
            listOf("val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)"),
            save.filter { it.startsWith("val replaces") },
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced. */
    private fun bodyOf(name: String): String {
        val code = codeLines(source()).joinToString("\n")
        val at = code.indexOf("fun $name(")
        check(at >= 0) { "ComposeViewModel.kt declares no '$name(' — did it get renamed?" }
        val start = code.indexOf('{', code.indexOf(')', at)) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    /** The lines of [text] that are code, trimmed, with comments taken off — the comments here
     *  name the very calls and the very ordering the rules above pin. */
    private fun codeLines(text: String): List<String> = text.lines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun source(): String = COMPOSE_VIEW_MODEL.readText()

    companion object {
        private const val REMEMBER_ID = "editingDraftId = draftId"
        private const val FREEZE =
            "editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)"
        private const val FREEZE_ACCOUNT = "editingDraftAccountId = credentials.id"

        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
