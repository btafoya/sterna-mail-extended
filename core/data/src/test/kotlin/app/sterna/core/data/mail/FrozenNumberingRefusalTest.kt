package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "FROZEN, AND THERE WAS NOTHING" IS REFUSED — the volet of #189 that a `Long?` could not carry.
 *
 * The defect, in one sentence: `moveToAccount` took a `Long?` in which `null` meant BOTH "no tick
 * froze anything, fall back on the folder's record" (the reader's single move, and the default of
 * every ordinary delete and read) AND "a tick froze the numbering of this row, and the row carried
 * none". The second collapsed onto the first's fallback — `expectedUidValidity ?: recorded` — and
 * the record is realigned by the first iteration of the loop that meets a renumbering. So the
 * record was compared with itself, agreed, and the octets of whatever holds that uid today were
 * appended on account B while the live message of account A went to the bin. No Undo on that path.
 *
 * The first test EXECUTES the decision; the two others say where it is spent. They are separate on
 * purpose: a decision nothing runs is a decision a mutation walks through, and a decision that runs
 * but is wired nowhere refuses nothing.
 */
class FrozenNumberingRefusalTest {

    // ---- the decision, RUN ---------------------------------------------------------------------

    /**
     * Three inputs, three outputs, and the test states them as literals — it does not recompute the
     * rule it is checking. Invert any branch of [numberingToOppose] and exactly one of these reds.
     */
    @Test fun `nothing frozen falls back, a stamp is opposed, a frozen absence refuses`() {
        assertEquals(
            "NothingFrozen must keep main's behaviour: Select(null), i.e. 'fall back on the " +
                "folder's record'. A refusal here fails 100 % of IMAP swipe-deletes, because " +
                "mayDestroyUnderStatedNumbering(null, 42) is false — the most expensive " +
                "regression this volet can cause",
            NumberingToOppose.Select(null),
            numberingToOppose(FrozenNumbering.NothingFrozen),
        )
        assertEquals(
            "a tick that froze a real numbering must put THAT number on the wire, unchanged",
            NumberingToOppose.Select(42L),
            numberingToOppose(FrozenNumbering.Frozen(42L)),
        )
        assertEquals(
            "⛔ THE VOLET: a row that WAS ticked and carries no numbering has nothing to oppose, " +
                "and nothing to oppose is a refusal — never the fallback on the folder's record, " +
                "which a renumbering has already realigned by the time this is asked",
            NumberingToOppose.Refuse,
            numberingToOppose(FrozenNumbering.Frozen(null)),
        )
    }

    /** A REAL stamp is carried, not interpreted: whatever number was frozen is the number opposed. */
    @Test fun `every frozen number is handed on as itself`() {
        listOf(1L, 42L, 77L, Long.MAX_VALUE).forEach { stamp ->
            assertEquals(
                "the envelope must not rewrite the number it carries ($stamp)",
                NumberingToOppose.Select(stamp),
                numberingToOppose(FrozenNumbering.Frozen(stamp)),
            )
        }
    }

    /**
     * AND `0` IS NOT A NUMBERING — nor is anything below it. It is the same rule as
     * `UidValidity.stated`, which this decision READS rather than restates: `UidValidityStore
     * .record` refuses to write a value at or below 0, so no folder in this app is ever recorded
     * under one.
     *
     * Why it must refuse rather than travel: `Select(0L)` would be the WEAKEST of the three states.
     * `onMailbox` has no fallback for a stated 0 (`0 ?: recorded` is 0), and `ImapSession.select`
     * refuses only when two POSITIVE numbers disagree — so the SELECT would confirm nothing at all,
     * on the one path that destroys without an Undo. Not reachable today (`ImapMessage.toEntity`
     * writes null or a positive), and refused anyway: "irreversible first" outranks "reachable
     * today", and the check costs one comparison.
     */
    @Test fun `a frozen zero, and anything below it, refuses like an absence`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { notANumbering ->
            assertEquals(
                "$notANumbering is not a numbering (UidValidity.stated normalises it to null), so " +
                    "there is nothing to oppose and nothing to oppose is a refusal — never a " +
                    "SELECT that confirms nothing",
                NumberingToOppose.Refuse,
                numberingToOppose(FrozenNumbering.Frozen(notANumbering)),
            )
        }
    }

    // ---- where it is spent ----------------------------------------------------------------------

    /**
     * SOURCE LINT, the last resort and only beside the executed test above: `MailRepository`
     * needs Room, a `Context` and a socket, so no JVM test instantiates it. What only the text can
     * say is that the decision is consumed AT the two `imap.*` calls and that the refusal comes
     * FIRST. Whole consecutive lines, never a `contains`: a substring rule is blind to a mutation
     * that lengthens what it matched, and "one more argument" is the exact shape of the mutation
     * that shipped once already on this path.
     */
    private fun codeLines(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /** The [count] lines of [function]'s body ending on the line that is exactly [last]. */
    private fun endingOn(function: String, last: String, count: Int): List<String> {
        val lines = codeLines(DaoQuerySource.mailFunctionBody("MailRepository", function))
        val end = lines.indexOf(last)
        check(end >= 0) { "MailRepository.$function no longer has the line <$last>. Body was:\n" + lines.joinToString("\n") }
        return lines.subList(end - count + 1, end + 1)
    }

    /**
     * THE READ IS WHERE IT HAS TO LAND. `crossAccountMove` runs readSource → writeTarget →
     * trashSource, so a refusal at the READ is a `Failed` with nothing written on the target
     * account, while a refusal at the bin alone leaves the copy on B under an APPEND that is never
     * replayed: `CopiedNotRemoved`, a permanent duplicate, no Undo. Fixing only the bin would move
     * the harm, not remove it.
     */
    @Test fun `the read refuses before it fetches, and hands the wire what the decision chose`() {
        assertEquals(
            "fetchRawSource must consume numberingToOppose IMMEDIATELY before imap.fetchSource, " +
                "and put nothing else on the wire. Dropping the refusal still compiles and still " +
                "reads — under `null ?: recorded`, the folder's own realigned record.",
            listOf(
                "val numbering = when (val verdict = numberingToOppose(frozen)) {",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)",
                "is NumberingToOppose.Select -> verdict.stamp",
                "}",
                "imap.fetchSource(credentials, mailboxId, uid, numbering)",
            ),
            endingOn("fetchRawSource", "imap.fetchSource(credentials, mailboxId, uid, numbering)", 5),
        )
    }

    /**
     * AND THE BIN REFUSES TOO, because the read alone leaves the case where the octets were
     * already in hand. The `?: recordedUidValidity(...)` on the last line STAYS: it is the fallback
     * that makes every ordinary swipe-delete work, and `Select(null)` is what reaches it.
     */
    @Test fun `the bin refuses before it moves, and keeps the fallback the swipe depends on`() {
        assertEquals(
            "delete must consume numberingToOppose IMMEDIATELY before imap.move, and the Select " +
                "branch must still fall back on the folder's record — without that `?:`, " +
                "mayDestroyUnderStatedNumbering(null, x) is false and 100 % of IMAP deletes fail.",
            listOf(
                "val numbering = when (val verdict = numberingToOppose(frozen)) {",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mb, null)",
                "is NumberingToOppose.Select -> verdict.stamp",
                "}",
                "val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))",
            ),
            endingOn("delete", "val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))", 5),
        )
    }

    /**
     * AND JMAP NEVER REACHES EITHER. Both refusals sit inside a protocol gate, and that is the
     * whole reason refusing an absent stamp is affordable HERE and unaffordable in
     * `UidValidity.destroyableUnderTheNumberingItWasTickedUnder` / `numberingStampsOfRows`, which
     * are protocol-agnostic: every JMAP row has a null numbering by construction.
     */
    @Test fun `both refusals sit behind a protocol gate`() {
        mapOf(
            "fetchRawSource" to "val raw = if (credentials.protocol == MailProtocol.IMAP) {",
            "delete" to "if (credentials.protocol == MailProtocol.IMAP) {",
        ).forEach { (function, gate) ->
            val lines = codeLines(DaoQuerySource.mailFunctionBody("MailRepository", function))
            val gateAt = lines.indexOf(gate)
            val refusalAt = lines.indexOfFirst { it.startsWith("NumberingToOppose.Refuse ->") }
            assertTrue("$function must still open its IMAP branch with <$gate>", gateAt >= 0)
            assertTrue(
                "$function must raise its refusal INSIDE the IMAP branch (gate at $gateAt, " +
                    "refusal at $refusalAt): a JMAP move between accounts freezes a null numbering " +
                    "on every row (EmailEntity.uidValidity is null there by construction) and " +
                    "refusing it outside the gate would break that protocol whole.",
                refusalAt > gateAt,
            )
        }
    }
}
