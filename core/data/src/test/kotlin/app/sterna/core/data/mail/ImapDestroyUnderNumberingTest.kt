package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IMAP twin of [DestroyableIdsTest] (Codeberg #99): given the numbering a destroy was
 * confirmed under, what may be destroyed at all — and how the wave is routed.
 *
 * Why it is a decision of its own rather than a comment in `destroyAll`: an IMAP `UID STORE
 * +FLAGS (\Deleted)` + `UID EXPUNGE` names messages by number, and those numbers only mean
 * anything inside one UIDVALIDITY. A held-back destroy that opposes NOTHING re-reads the folder's
 * current numbering at execution, the SELECT matches it against itself, and a folder renumbered
 * during the hold-back is expunged by UIDs that now name OTHER, live messages — in the Trash, or
 * in the Inbox itself on an account with no Trash, where `deleteWouldDestroy` is true and the
 * folder carried is the Inbox.
 *
 * Everything below RUNS. The wiring test at the end is the only source-text part, and it is there
 * because `MailRepository` needs Room and a `Context`; it pins whole lines, never substrings.
 */
class ImapDestroyUnderNumberingTest {

    private fun id(mailbox: String, uid: Long) = ImapMailService.emailId(ACCOUNT, mailbox, uid)

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    // ---- the decision, executed --------------------------------------------------------------

    @Test fun `a destroy with no numbering to oppose destroys nothing`() {
        // The held-back destroy of a selection, enqueued by a version predating the key, or for a
        // folder whose numbering was never observed. Nothing can be shown to still mean anything.
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = null),
        )
    }

    @Test fun `a numbering the server never announced destroys nothing`() {
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 0L),
        )
        assertEquals(
            emptyList<String>(),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = -1L),
        )
    }

    /** The inverse witness, without which "always empty" would satisfy every case above — and
     *  refusing every destroy is a total regression, not a guard. */
    @Test fun `a destroy carrying its numbering keeps its whole list, in its own order`() {
        assertEquals(
            listOf("a", "b"),
            UidValidity.destroyableUnderNumbering(listOf("a", "b"), expected = 42L),
        )
        assertEquals(
            listOf("b", "a"),
            UidValidity.destroyableUnderNumbering(listOf("b", "a"), expected = 42L),
        )
    }

    // ---- the routing, executed ---------------------------------------------------------------

    @Test fun `with nothing to oppose, every id is refused and no folder is touched`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = null)

        // Empty byFolder is what makes it safe: the destroy loop has nothing to iterate, so no
        // UID EXPUNGE is ever issued and nothing can land in `succeeded`.
        assertEquals(emptyMap<String, List<String>>(), plan.byFolder)
        assertEquals(ids, plan.refused)
    }

    @Test fun `an unverifiable numbering refuses the wave just as a missing one does`() {
        val ids = listOf(id("INBOX", 1L))

        assertEquals(emptyMap<String, List<String>>(), UidValidity.imapDestroyPlan(ids, 0L).byFolder)
        assertEquals(ids, UidValidity.imapDestroyPlan(ids, 0L).refused)
    }

    @Test fun `under its own numbering the wave is grouped by the folder its ids name`() {
        val ids = listOf(id("INBOX", 1L), id("Trash", 2L), id("INBOX", 3L))

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(
            mapOf("INBOX" to listOf(ids[0], ids[2]), "Trash" to listOf(ids[1])),
            plan.byFolder,
        )
        assertEquals(emptyList<String>(), plan.refused)
    }

    @Test fun `an id naming no folder is refused, never destroyed`() {
        // A JMAP-shaped id in an IMAP account's wave: unparsable, so it names no UID either.
        val ids = listOf(id("INBOX", 1L), "Mabcdef")

        val plan = UidValidity.imapDestroyPlan(ids, expected = 42L)

        assertEquals(mapOf("INBOX" to listOf(ids[0])), plan.byFolder)
        assertEquals(listOf("Mabcdef"), plan.refused)
    }

    @Test fun `a folder path containing colons survives the grouping`() {
        // IMAP paths can hold ':' — the id parser keeps them, and so must the plan.
        val ids = listOf(id("a:b", 7L))

        assertEquals(mapOf("a:b" to ids), UidValidity.imapDestroyPlan(ids, expected = 42L).byFolder)
    }

    @Test fun `the plan is a partition - nothing destroyed twice, nothing invented, nothing dropped`() {
        val ids = listOf(id("INBOX", 1L), "Mabcdef", id("Trash", 2L))

        listOf(null, 0L, -1L, 1L, 42L, Long.MAX_VALUE).forEach { expected ->
            val plan = UidValidity.imapDestroyPlan(ids, expected)
            val destroyed = plan.byFolder.values.flatten()
            assertEquals("$expected: every id is accounted for, once", ids.sorted(), (destroyed + plan.refused).sorted())
            assertTrue(
                "$expected: an id may not be both destroyed and refused",
                destroyed.none { it in plan.refused },
            )
            assertTrue("$expected: the plan may not invent ids", destroyed.all { it in ids })
        }
    }

    // ---- where the decision is plugged in (source text: MailRepository needs Room) ------------

    @Test fun `the IMAP branch of destroyAll routes through the decision and fails what it refuses`() {
        val body = bodyOf("destroyAll")
        assertEquals(
            "the IMAP destroy must ask the decision what its numbering licenses — a predicate " +
                "written inline is a decision no test can execute",
            listOf("val plan = UidValidity.imapDestroyPlan(emailIds, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyPlan("),
        )
        assertEquals(
            "what the decision refused must land in `failed` — never silently counted a success, " +
                "and `failed` is what makes the worker re-query so the survivors come back",
            listOf("failed += plan.refused"),
            codeLinesNaming(body, "plan.refused"),
        )
        assertEquals(
            "and only what it licensed may reach the expunge",
            listOf("plan.byFolder.forEach { (source, ids) ->"),
            codeLinesNaming(body, "plan.byFolder"),
        )
        assertEquals(
            "the frozen numbering must travel on to the SELECT, which is what actually refuses",
            listOf("imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)"),
            codeLinesNaming(body, "imapDestroyGroup("),
        )
    }

    /**
     * One link further than the test above, and the link that actually destroys: the argument has
     * to reach `deleteBatch`. Dropping it COMPILES — `ImapMailService.deleteBatch` defaults
     * `expectedUidValidity` to null — and `onMailbox` then falls back on `expectedUidValidity ?:
     * recorded`, so the SELECT compares the folder's current number with itself, concludes SAME,
     * and expunges a renumbered folder without one failure. That is the original defect, entire.
     */
    @Test fun `the frozen numbering reaches the wire, not merely the branch that routes`() {
        assertEquals(
            "the expunge must SELECT under the numbering the caller froze — the argument is what " +
                "makes it refusable, and its absence is silent, legal Kotlin",
            listOf("imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)"),
            codeLinesNaming(bodyOf("imapDestroyGroup"), "imap.deleteBatch("),
        )
    }

    /**
     * The OTHER destroy path, and the one `destroyAll` never routed: discarding a draft, or
     * replacing one that was re-saved, expunges a single id straight from the folder it names.
     * It handed `imapDestroyGroup` nothing to oppose, so `onMailbox` fell back on
     * `expectedUidValidity ?: recorded`, compared the folder's current number with itself and
     * expunged — including on a folder whose numbering the app had never recorded at all, i.e.
     * with nothing whatsoever to oppose. Source text for the same reason as the tests above:
     * `MailRepository` needs Room and a `Context`, so the lines are pinned WHOLE, arguments
     * included — an assertion that the call merely exists says nothing about what it is handed.
     *
     * ⭐ Opposing a number READ HERE was only half of it, and the cheap half. Between the moment
     * the composer reads the draft's id off the screen and the moment the discard goes out, a
     * background pass (`loadFolder`, `loadWatchedFolders`) may have seen the folder renumbered and
     * written the NEW number to the store. Reading at execution then opposes that new number to
     * itself — SAME, expunge — on a UID belonging to the OLD numbering, i.e. on whatever message
     * now holds that number in Drafts. So the numbering is frozen by the caller when it reads the
     * id, and arrives here as a parameter; this path reads nothing (see
     * [the destroy path never looks the numbering up for itself]).
     */
    @Test fun `discarding a draft opposes the numbering frozen when its id was read`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the decision the held-back destroy runs must be handed the FROZEN parameter, never a " +
                "value this path went and looked up: nothing to oppose licenses nothing, and the " +
                "draft simply stays",
            listOf("val destroyable = UidValidity.destroyableUnderNumbering(listOf(emailId), frozenUidValidity)"),
            codeLinesNaming(body, "destroyableUnderNumbering("),
        )
        assertEquals(
            "only what the decision licensed may reach the expunge, and the same frozen numbering " +
                "must travel with it: the argument is what `deleteBatch` opposes to the number the " +
                "server states. Left out, the destroy arrives carrying nothing and the guard below " +
                "has only the fallback to compare with itself — which is the defect entire",
            listOf(
                "imapDestroyGroup(credentials, folder, destroyable, mutableSetOf(), mutableSetOf(), frozenUidValidity)",
            ),
            codeLinesNaming(body, "imapDestroyGroup("),
        )
    }

    /**
     * The freeze must name the folder the destroy will SELECT. The accessor the composer calls
     * resolves it from the id exactly as [destroyDraft] does — the draft's own cached mailbox,
     * falling back on the account's Drafts role. Resolve it differently on either side and the
     * number frozen belongs to another folder: opposed to Drafts it reads CHANGED and every
     * discard is refused for ever, or worse it happens to match and licenses the expunge the
     * guard exists to stop.
     *
     * ⚠ The needle stays the bare `mailboxOf(`, and it is deliberately WIDER than the two lines it
     * pins: any other way of resolving a folder in these bodies — `mailboxDao.mailboxOf(`, a bare
     * `mailboxOf(`, a helper named for one — lands in the list and fails the comparison, which is
     * the whole point of comparing whole lists rather than searching inside them. It was briefly
     * narrowed to `emailDao.mailboxOf(` on the argument that the JMAP branch's `draftMailboxOf(`
     * matched inside it; that argument is false — [codeLinesNaming] is case-sensitive and
     * `draftMailboxOf(` carries a capital M — so the narrowing bought nothing and cost the rule its
     * reach. ⛔ Do not narrow it again without running the needle over the body first.
     */
    @Test fun `the numbering is frozen for the very folder the destroy will select`() {
        val resolution = listOf(
            "val folder = emailDao.mailboxOf(credentials.id, emailId)",
            "?: mailboxDao.idForRole(credentials.id, \"drafts\")",
        )
        listOf("destroyDraft", "recordedUidValidityForDraft").forEach { function ->
            val body = bodyOf(function)
            assertEquals(
                "$function must resolve the draft's folder account-scoped (#31) and identically on " +
                    "both sides of the freeze",
                resolution,
                codeLinesNaming(body, "mailboxOf(") + codeLinesNaming(body, "idForRole("),
            )
        }
        assertEquals(
            "and the accessor must answer with the numbering recorded for THAT folder — the " +
                "repository's own protocol-guarded read, which is null on JMAP",
            listOf("return recordedUidValidity(credentials, folder)"),
            codeLinesNaming(bodyOf("recordedUidValidityForDraft"), "recordedUidValidity("),
        )
    }

    /**
     * And it must refuse WITHOUT A WORD ON SCREEN.
     *
     * The refusal arrives here as an exception. `ComposeViewModel.submit` catches every `Throwable`
     * and puts `t.message` straight into the "could not save" banner (`ComposeScreen`), so a refusal
     * left to propagate shows the user "The server stated no UIDVALIDITY for Drafts; a destroy
     * frozen under 42 was refused" — English protocol jargon, in all nine locales, precisely when
     * the guard is doing its job. Its twin refusal, the one where the decision licenses an empty
     * list, is already silent: `imapDestroyGroup` returns on `if (uidToId.isEmpty()) return` and
     * nobody raises anything. Both refusals say nothing, or neither does — and a new string on
     * screen costs nine translations for a case in which nothing was lost.
     */
    @Test fun `a draft the server will not confirm the numbering of is refused in silence`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the draft path must swallow the two numbering refusals AND NOTHING ELSE, each caught " +
                "BY TYPE. `ImapNumberingUnconfirmed` is the server stating no UIDVALIDITY at all; " +
                "`ImapUidValidityChanged` is the one the frozen number now makes reachable — " +
                "`onMailbox` hands `expectedUidValidity ?: recorded` to the SELECT, and a folder " +
                "renumbered since the composer read the id refuses THERE. Uncaught, it travels the " +
                "#69 path up to `ComposeViewModel.submit`, which puts `t.message` verbatim in the " +
                "\"could not save\" banner: English protocol jargon in all nine locales, at the " +
                "exact moment the guard saved the user's mail. A `runCatching` or a " +
                "`catch (t: Throwable)` here would instead hide a transport failure and close the " +
                "composer over a draft that comes back on the next sync. Body was:\n$body",
            listOf(
                "} catch (unconfirmed: ImapNumberingUnconfirmed) {",
                "} catch (renumbered: ImapUidValidityChanged) {",
            ),
            codeLinesNaming(body, "catch"),
        )
        assertEquals(
            "and the ONLY runCatching allowed on this path is the best-effort list refresh of the " +
                "renumbering catch. Wrapping the destroy itself in one — the shape that reads as " +
                "harmless — hides a transport failure just as `catch (t: Throwable)` would, and " +
                "closes the composer over a draft that comes back on the next sync. Body was:\n$body",
            listOf("runCatching { refresh(credentials, folder) }"),
            codeLinesNaming(body, "runCatching"),
        )
        assertEquals(
            "and nowhere else in the repository: on the trash purge and on a selection destroy the " +
                "same refusal must keep travelling up to `MessageDestroyWorker`, which bounds its " +
                "own retries. Swallowed there, a purge the user confirmed would report success " +
                "having destroyed nothing",
            listOf("} catch (unconfirmed: ImapNumberingUnconfirmed) {"),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "ImapNumberingUnconfirmed"),
        )
    }

    /**
     * The numbering ARRIVES on this path; it is never read on it. A local read — including one
     * that merely SHADOWS the parameter, which Kotlin accepts with a warning — hands the guard
     * the number a refresh recorded AFTER the renumbering, which is precisely the value that
     * makes it pass on the mail it exists to save. Same shape as the "no swallower" rule of
     * [DestroyChecksTheFolderWiringTest].
     *
     * `destroyDraft` joined this list: it used to read at execution, and that read WAS the window
     * between the composer displaying a draft's id and the expunge going out. The accessor the
     * composer freezes through, `recordedUidValidityForDraft`, is a different function on purpose —
     * called at the read, never here. `discardDraft` and `finishDraftSave` are on it too: they are
     * the two functions that HAND the parameter over, and a `?: recordedUidValidityForDraft(…)`
     * written on either of them restores the whole defect while every other rule stays green.
     * The needle is the shorter name, so it catches the new accessor as well.
     */
    @Test fun `the destroy path never looks the numbering up for itself`() {
        listOf("destroyAll", "imapDestroyGroup", "destroyDraft", "discardDraft", "finishDraftSave")
            .forEach { function ->
                assertEquals(
                    "$function must take the frozen numbering as a parameter and read nothing: what " +
                        "it could read is the numbering of the renumbering it must refuse (#99)",
                    emptyList<String>(),
                    codeLinesNaming(bodyOf(function), "recordedUidValidity"),
                )
            }
    }

    /**
     * ⭐ And every one of them must be written as a BLOCK, because a single-expression body is
     * INVISIBLE to the rule above: [DaoQuerySource.mailFunctionBody] delimits a body by matching
     * braces, an expression body has none, and the lookup then fails instead of reading. A
     * `discardDraft` written as
     *
     *     suspend fun discardDraft(…) = destroyDraft(c, id, frozen ?: recordedUidValidityForDraft(c, id))
     *
     * re-opens the defect ENTIRE on the "empty a draft" gesture — the fallback is taken exactly
     * when the composer froze nothing, and it answers the number recorded AFTER the renumbering —
     * and it cannot appear in the list above at all. Same reason `ImapMailService.ccAndBccOf` is
     * kept a block, and its KDoc says so.
     */
    @Test fun `the draft destroy path is written in blocks, or no rule can see inside it`() {
        listOf("destroyDraft", "discardDraft", "finishDraftSave").forEach { function ->
            assertTrue(
                "$function must have a BLOCK body: an expression body silently drops it out of " +
                    "every source rule in this file, starting with the one forbidding it to read a " +
                    "numbering for itself",
                runCatching { bodyOf(function) }.isSuccess,
            )
        }
    }

    /**
     * The freezing accessor, everywhere it is CALLED in the repository — pinned as a list rather
     * than as an absence, so the DECLARATION has to be named and a rule that has stopped finding
     * anything at all cannot pass by accident.
     *
     * ⭐ There is no execution-time call left. The send used to be the one exception: `performSend`
     * read the numbering at delivery, which is a headless worker firing when the network comes back
     * or at a scheduled send's hour — hours or days after the composer read that draft. The outbox
     * row now CARRIES the number the composer froze (`OutboxEntity.draftUidValidity`), so this
     * accessor is called nowhere in the repository: only the composer calls it, once, at the read.
     *
     * One line more here is the defect back, and each shape of it reads as harmless: a `?:` fallback
     * in `performSend` "for rows queued before the column existed", a re-read in `discardDraft` or
     * `finishDraftSave` "to be safe". All three answer the numbering recorded AFTER a renumbering,
     * which is exactly the value that licenses the expunge on whatever now holds those UIDs.
     *
     * The file-wide rule below this one only pins `imap.recordedUidValidity(`; this accessor carries
     * no `imap.` prefix and slips under it, so without this rule every draft path in the class could
     * go back to reading at execution unseen.
     */
    @Test fun `the freezing accessor is declared for the composer and called at no execution point`() {
        assertEquals(
            "`recordedUidValidityForDraft` is the READ-TIME accessor and the repository must not " +
                "call it AT ALL: every path that destroys a draft is handed the frozen number as a " +
                "parameter — the composer's, or the outbox row's. A call restored here is a number " +
                "read at execution, i.e. the defect, whichever function it is written in",
            listOf(
                // The declaration itself, pinned whole: the signature is part of the contract.
                "suspend fun recordedUidValidityForDraft(credentials: AccountCredentials, emailId: String): Long? {",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "recordedUidValidityForDraft("),
        )
    }

    /**
     * The two hand-overs of `uploadDraft` (`saveDraft`'s upload half since #95) — the IMAP branch
     * and the JMAP one. Nothing named
     * `finishDraftSave` in any test before this: replacing either argument with
     * `replacesEmailId?.let { recordedUidValidityForDraft(credentials, it) }` restores the defect
     * on the RE-SAVE gesture (the one that destroys the server copy it replaces) and leaves the
     * whole suite green. Whole lines, arguments included.
     */
    @Test fun `re-saving hands on the numbering the composer froze, on both protocols`() {
        val body = bodyOf("uploadDraft")
        assertEquals(
            "both branches of uploadDraft must hand `finishDraftSave` the parameter the composer " +
                "froze — twice, identically: the IMAP branch destroys the replaced original by " +
                "UID EXPUNGE, and the JMAP one carries null all the same so neither branch can " +
                "start reading a numbering for itself. Body was:\n$body",
            listOf("frozenUidValidity = replacesUidValidity,", "frozenUidValidity = replacesUidValidity,"),
            codeLinesNaming(body, "frozenUidValidity"),
        )
    }

    /**
     * The inverse witness for the new accessor, twin of "the accessor really asks IMAP" below.
     * Every executable rule here proves "handed nothing, destroy nothing"; nothing proves a healthy
     * IMAP account is handed a REAL number. Flip `!=` to `==` on the protocol guard, or answer
     * `null` outright, and every IMAP draft destroy becomes a silent, permanent refusal: a
     * duplicate left in Drafts on every re-save, and an emptied draft that comes back on the next
     * sync — with the whole suite green.
     */
    @Test fun `the freezing accessor answers a real number for an IMAP account`() {
        val body = bodyOf("recordedUidValidityForDraft")
        assertEquals(
            "the accessor must refuse JMAP (`!=`) and answer the folder's recorded numbering for " +
                "everything else. `==` there reads as a typo and turns the guard into a blanket " +
                "refusal of every IMAP draft destroy, silently. Body was:\n$body",
            listOf(
                "if (credentials.protocol != MailProtocol.IMAP) return null",
                "?: return null",
                "return recordedUidValidity(credentials, folder)",
            ),
            codeLinesNaming(body, "return"),
        )
    }

    /**
     * ⛔ WYSIWYG on the refusal. `onMailbox` invalidates the folder before raising
     * `ImapUidValidityChanged`, which drops the cached BODIES of the whole Drafts folder and
     * records the new numbering — but the draft's LIST ROW survives, carrying an id from a
     * numbering that no longer exists. The "save" path repairs that by accident (`saveDraft` ends
     * on `runCatching { refresh(credentials, drafts) }`); the "empty a draft" path repaired
     * nothing, so the user was left looking at a row that means nothing, with no word on screen.
     */
    @Test fun `a refused discard leaves the drafts list telling the truth`() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "the renumbering catch must re-read the folder, best-effort: the refusal is silent, so " +
                "the list is the only thing that can tell the user anything, and it must not keep " +
                "showing a row whose id belongs to a numbering the server has thrown away. " +
                "`runCatching` because a refusal that then fails to refresh is still a refusal — " +
                "letting the reload throw would turn a silent, harmless refusal into the " +
                "\"could not save\" banner it exists to avoid. Body was:\n$body",
            listOf("runCatching { refresh(credentials, folder) }"),
            codeLinesNaming(body, "refresh("),
        )
    }

    /**
     * The inverse witness at system level, and the one the executable tests cannot give: they all
     * prove "handed nothing, destroy nothing". Nothing proves that a healthy account hands over a
     * REAL number — replace this accessor's body with `null` and every IMAP permanent delete
     * becomes a silent, permanent refusal, with the whole suite green.
     *
     * Read file-wide rather than per function: it is a single-expression body, which the brace
     * matching of [DaoQuerySource.mailFunctionBody] cannot delimit. The second line is the purge's
     * own read of the same value (#99), pinned here for the same reason.
     */
    @Test fun `the accessor really asks IMAP for the folder's numbering`() {
        assertEquals(
            "the numbering must come from the IMAP layer's record for THAT account and THAT folder — " +
                "a body that answers null refuses every IMAP destroy for ever. The two folder reads " +
                "carry a protocol guard; the notification one deliberately does not (it is reached " +
                "only with IMAP preview sources in hand, and JMAP conveys none). ⛔ The list is CLOSED " +
                "and it no longer holds a per-selection destroy: since #99's row-stamping branch, a " +
                "held-back destroy freezes what the ROWS were read under " +
                "(`numberingRowsWereReadUnder`, off `emails.uidValidity`) and never the folder's " +
                "current record, which a background pass realigns the moment it meets a renumbering. " +
                "A new `imap.recordedUidValidity(` on that path is the defect coming back",
            listOf(
                // The notification pre-pass's own read of the same value: it is how a preview pass
                // notices a folder renumbered under it, since `fetchPreview` swallows the refusal
                // (see `MailRepository.recordedNumbering`, and `PreviewConveyanceTest`).
                "imap.recordedUidValidity(accountId, mailboxId)",
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, mailboxId) else null",
                "if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "imap.recordedUidValidity("),
        )
    }

    private companion object {
        const val ACCOUNT = "acc1"
    }
}
