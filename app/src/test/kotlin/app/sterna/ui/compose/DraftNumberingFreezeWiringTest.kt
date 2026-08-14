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
        assertEquals(
            "the numbering must be frozen on the line right after '$REMEMBER_ID', in the same " +
                "block of the reopen branch: it is the one moment at which the id on screen and " +
                "the folder's numbering are known to belong to each other. Anywhere later is a " +
                "different number — the one a refresh recorded after the renumbering, i.e. exactly " +
                "the value that lets the expunge through on another message. Body was:\n" +
                prepare.joinToString("\n"),
            id + 1,
            freeze,
        )
    }

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
            ),
            save.filter { it.startsWith("val original") },
        )
        assertEquals(
            "and the discard call must be handed that captured value — nothing else, and no `null` " +
                "placeholder: `null` destroys nothing, so an emptied draft would come back on the " +
                "next sync. Body was:\n${save.joinToString("\n")}",
            listOf("submit(to) { credentials, _ -> repo.discardDraft(credentials, original, originalUidValidity) }"),
            save.filter { "repo.discardDraft(" in it },
        )
        assertEquals(
            "re-saving an opened draft destroys the server copy it replaces, so repo.saveDraft must " +
                "carry the frozen numbering for it too, under its own named argument. Passing " +
                "editingDraftId's numbering read afresh here is the defect this fix closes. " +
                "Body was:\n${save.joinToString("\n")}",
            listOf(
                "replacesEmailId = editingDraftId,",
                "replacesUidValidity = editingDraftUidValidity,",
            ),
            save.filter { it.startsWith("replaces") },
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
