package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.RowNumbering

/**
 * The IMAP numbering each of [emailIds] was READ under, off the cached rows [read] hands back —
 * the stamp both #99 guards oppose (the held-back DESTROY, and the move between accounts).
 *
 * Extracted from [MailRepository.numberingRowsWereReadUnder] for exactly one reason, the same one
 * that put [byIdsChunked] in its own file: `MailRepository` needs Room and a `Context` (and builds
 * an `android.util.LruCache` in a property initialiser), so nothing in a JVM test can call it, and
 * the three decisions below were shipped WITHOUT ANY TEST EXECUTING THEM. Every stamp THE TWO #99
 * GUARDS OPPOSE comes out of here; inverting the protocol test, or dropping the value, made both
 * of them decorative and no test in the repository noticed. [NumberingStampsTest] runs it.
 *
 * Not every stamp in the app: [MailRepository.recordedUidValidity] makes a SECOND one, at
 * FOLDER level and opposed just as late (`MailBySenderViewModel.askDelete` →
 * `PendingDelete.numbering` → `deleteAll(credentials, ids, pending.numbering)`). It is a different
 * value with a different lifetime, and nothing here says anything about it.
 *
 * The three decisions, and what each is worth:
 *
 * - **JMAP answers an EMPTY map, and [read] is never called.** `emails.uidValidity` is null for
 *   every JMAP row by construction ([app.sterna.core.data.db.EmailEntity]), and an absent stamp
 *   PASSES by construction on the other side (`UidValidity.destroyableUnderTheNumberingItWasTicked
 *   Under` refuses only when two real numbers disagree). Empty is the right answer, not an error
 *   case: refusing on "no number" would break 100% of JMAP.
 * - **An empty [emailIds] answers empty**, rather than issuing an `IN ()`.
 * - **The read is CHUNKED** ([byIdsChunked]): [emailIds] is a whole selection — an "empty this
 *   folder" holds thousands — and SQLite refuses past 999 bound variables below Android 12. This
 *   read sits BEFORE the eviction and the enqueue and OUTSIDE any `runCatching`, so an unbounded
 *   `IN (...)` would not fail the delete, it would crash it.
 *
 * An id with no cached row is ABSENT from the answer; a row whose `uidValidity` is null is
 * PRESENT with a null value. Both read as "nothing to oppose" at the guard, and the difference
 * matters to nobody but a mutation that turns the second into the first.
 *
 * The ACCOUNT SCOPE lives in the [read] the caller supplies (`emailDao.numberingOfRows(
 * credentials.id, chunk)`, Codeberg #31: two accounts on one server carry identical ids). Nothing
 * here can check that; `NumberingStampsSourceTest` pins that call line whole.
 */
internal suspend fun numberingStampsOfRows(
    credentials: AccountCredentials,
    emailIds: List<String>,
    read: suspend (List<String>) -> List<RowNumbering>,
): Map<String, Long?> {
    if (credentials.protocol != MailProtocol.IMAP || emailIds.isEmpty()) return emptyMap()
    val rows = byIdsChunked(emailIds) { chunk -> read(chunk) }
    return rows.associate { it.id to it.uidValidity }
}
