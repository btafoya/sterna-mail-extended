package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a gesture waits for the stamps of its own tick before going ahead without them.
 *
 * The read behind those stamps is DATABASE ONLY — `MailRepository.numberingRowsWereReadUnder`, one
 * query per account, chunked at 200 ids — so this is not a network budget. It is sized for the one
 * case that opened the window: "Select all" over a folder of thousands, which writes the whole
 * selection in ONE emission and reads it back in a dozen sequential queries. A phone that cannot
 * finish those in this long is a phone where the gesture has already stopped feeling instant, and
 * waiting longer with the selection bar up would read as a freeze.
 *
 * THE NUMBER IS THE GUARD, so it is EXECUTED, not merely declared: [TickedNumberingBarrierTest]
 * spends the default on virtual time and asserts the delay is this one. Raised to a minute or ten,
 * every rule about "bounded" stays green while the gesture holds the screen with the selection bar
 * up for that long — a literal undoing the whole point of the bound, in one token.
 */
internal const val TICKED_NUMBERING_BUDGET_MS = 1_500L

/**
 * Whether [published] already answers for every key of [awaited] — the whole decision of the
 * barrier, pulled out so a JVM test can RUN it (`InboxViewModel` needs an `Application` and Room,
 * so a rule left inside it is a rule a mutation walks through).
 *
 * A PUBLISHED `null` COUNTS AS AN ANSWER, and that is the subtlety the whole thing turns on.
 * `Map<EmailKey, Long?>` has two shapes of "no number": the key is absent (nobody has read that row
 * yet) and the key is present with a null value (it was read, and the row carries no numbering — a
 * JMAP row, a row cached before the column existed, a row evicted since). Only the FIRST is worth
 * waiting for. Testing values instead of keys would wait for a number that is never coming, spend
 * the whole budget, and hand back the same map anyway.
 */
internal fun numberingStampsCover(awaited: Set<EmailKey>, published: Map<EmailKey, Long?>): Boolean =
    published.keys.containsAll(awaited)

/**
 * The stamps of the tick for [awaited], waiting — bounded — for the read STARTED AT THE TICK to be
 * published (Codeberg #99, #189).
 *
 * A BARRIER, NEVER A RE-READ, and the distinction is the entire point. `InboxViewModel` reads the
 * numbering of a row in a coroutine of its own, off the collector on `_selectedKeys`; the gesture
 * runs in another. A "Select all" writes the keys in one emission and the read comes back a dozen
 * queries later, so a tap on Move or Delete in between used to take a map that was empty or half
 * full. Re-reading the rows here instead would make the guard decorative: the re-read IS the
 * realigned number — a folder renumbered since the tick would be compared with itself, agree, and
 * every line would travel. So this waits for the value the tick's own read publishes, and computes
 * nothing.
 *
 * AND IT IS BOUNDED, because coverage can become UNREACHABLE — and the reason is the selection
 * moving, not a key nobody can resolve. `rememberTickedNumbering` ends on
 * `filterKeys { it in still }`: it publishes only what is STILL ticked. So any change of selection
 * during the wait publishes a map that no longer contains [awaited], and no later publication ever
 * will. The reachable case is the system Back gesture: the selection bar is still up while this
 * waits, `InboxScreen` binds Back to `clearSelection()`, and the collector then publishes an empty
 * map. Unbounded, the gesture would hang there for good.
 *
 * AND THE FALLBACK IS THE MAP AS IT WAS WHEN THE GESTURE ASKED — frozen on entry, never
 * `published.value` read again at the timeout. That distinction is a regression this file already
 * had: the capture used to sit outside the `launch`, so the gesture held a snapshot taken at the
 * tap; moving it in here and re-reading at the end handed back whatever the collector had emptied
 * it to meanwhile. On a "Select all" + Move + Back that is an EMPTY map, every row becomes
 * `FrozenNumbering.Frozen(null)`, and the repository refuses ALL of them — where before the branch
 * the gesture simply went ahead with the map it had sampled at the tap.
 *
 * So on timeout this hands back exactly what the gesture would have sampled at the tap. Not a
 * shrug, and safe in the right direction: a key with no entry becomes `Frozen(null)` at the call
 * site, which the repository refuses on the move between accounts. The gesture fails; it does not
 * destroy.
 *
 * WHAT THIS IS NOT. The race is not closed, it is BOUNDED: past the budget — a very large folder,
 * a slow phone, a multi-account selection that chains two reads — the old symptom comes back
 * unchanged, now preceded by a second and a half of a screen that does not answer. And the gesture
 * can still wait; it can no longer wait INDEFINITELY.
 *
 * [published] is a `StateFlow`, so an already-covered map returns without suspending at all: the
 * ordinary case (a handful of rows ticked one by one, read long before the tap) costs nothing.
 */
internal suspend fun tickedNumberingWhenCovered(
    awaited: Set<EmailKey>,
    published: StateFlow<Map<EmailKey, Long?>>,
    budgetMs: Long = TICKED_NUMBERING_BUDGET_MS,
): Map<EmailKey, Long?> {
    // FROZEN ON ENTRY, and this line is the whole of point one. The fallback below must be what
    // the gesture would have sampled at the tap; `published.value` read after the timeout is what
    // the collector has emptied the field to since — an empty map on any change of selection.
    val atTheGesture = published.value
    return withTimeoutOrNull(budgetMs) { published.first { numberingStampsCover(awaited, it) } } ?: atTheGesture
}
