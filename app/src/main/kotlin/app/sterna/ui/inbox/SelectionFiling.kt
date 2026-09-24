package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email

/** The resolved rows of a selection, split by a filing gesture: those it takes away, and those it
 *  leaves where they are. A spared row is not written, not masked, and not counted: it was never
 *  attempted, so it is neither a success nor a failure. */
internal data class SelectionFiling<T>(val leaving: List<T>, val spared: List<T>)

/** Which of [rows] a filing gesture takes away. [moves] is the rule frozen at the tap; null takes
 *  every row, which is what a delete asks for. The rule is asked only of the rows whose key is in
 *  [carried], what a folded row brought in with its conversation: a row the hand ticked leaves. [emailOf]
 *  reads the message a row stands for, so the batched path can hand its resolved targets and the
 *  cross-account path its cached messages. */
internal fun <T> fileSelection(
    rows: List<T>,
    moves: ((Email) -> Boolean)?,
    carried: Set<EmailKey>,
    emailOf: (T) -> Email,
): SelectionFiling<T> {
    if (moves == null) return SelectionFiling(rows, emptyList())
    val (leaving, spared) = rows.partition { row ->
        val email = emailOf(row)
        email.emailKey() !in carried || moves(email)
    }
    return SelectionFiling(leaving, spared)
}

/** The selection and where each key came from. [carried] are the keys a folded row brought in with
 *  its conversation, its representative included, since the hand touched a conversation and not a
 *  message; every other key of [keys] was ticked by hand. Invariant: [carried] is within [keys]. */
internal data class SelectionTicks(val keys: Set<EmailKey>, val carried: Set<EmailKey> = emptySet())

/** A tap on one row: a key already ticked leaves both halves, whatever brought it in; a new one is the hand's. */
internal fun SelectionTicks.toggledByHand(key: EmailKey): SelectionTicks =
    if (key in keys) SelectionTicks(keys - key, carried - key) else SelectionTicks(keys + key, carried)

/** [added] brought in by a conversation. A key the hand already ticked stays the hand's. */
internal fun SelectionTicks.carrying(added: Set<EmailKey>): SelectionTicks =
    SelectionTicks(keys + added, carried + (added - keys))

/** A tap on a folded row: its whole conversation leaves together, or comes in carried. */
internal fun SelectionTicks.toggledConversation(rep: EmailKey, members: Set<EmailKey>): SelectionTicks {
    val all = members + rep
    return if (rep in keys) SelectionTicks(keys - all, carried - all) else carrying(all)
}

/** [keys] a refused gesture ticks again, each with the origin [carried] held at that gesture's tap. */
internal fun handedBack(keys: Set<EmailKey>, carried: Set<EmailKey>): SelectionTicks =
    SelectionTicks(keys, keys intersect carried)
