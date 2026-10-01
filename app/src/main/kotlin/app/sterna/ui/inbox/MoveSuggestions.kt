package app.sterna.ui.inbox

import app.sterna.core.data.account.MoveEvent
import app.sterna.core.data.account.MoveHistory
import app.sterna.core.data.account.moveEventOf
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox

/** How many rows the Recent section of a move-to-folder picker holds at most. */
internal const val MOVE_SUGGESTIONS_MAX = 3

/** How many remembered moves of one sender into one folder make it that sender's folder. */
internal const val SENDER_HABIT_MIN_EVENTS = 2

private const val DAY_MS = 86_400_000.0

/**
 * What a picker suggests, best first, at most [MOVE_SUGGESTIONS_MAX] folder ids, every one of them
 * among [targets], so never the current folder, a hidden one, one gone from the server, or one the
 * subscription setting leaves out.
 *
 * First the [sender]'s folder: the one that holds at least [SENDER_HABIT_MIN_EVENTS] events naming
 * that address, the most frequent, then the most recent. Then the others by score, the sum over a
 * folder's events of `1 / (1 + age in days / 7)`: a move of today weighs 1, of last week 1/2, of a
 * month ago about 1/5, so a folder used often long ago and a folder used once this morning can both
 * be offered. A plain count would pin last year's habits; the last folder alone would forget them.
 * Ties go to the order of [targets], the order the list below shows.
 */
internal fun moveSuggestions(
    events: List<MoveEvent>,
    targets: List<Mailbox>,
    sender: String?,
    now: Long,
): MoveSuggestions {
    val rank = targets.withIndex().associate { (index, folder) -> folder.id to index }
    val offered = events.filter { it.mailboxId in rank }
    val habit = sender?.lowercase()?.let { address ->
        offered.filter { address in it.senders }
            .groupBy { it.mailboxId }
            .filterValues { it.size >= SENDER_HABIT_MIN_EVENTS }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<MoveEvent>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.maxOf { it.at } }
                    .thenBy { rank.getValue(it.key) },
            )
            .firstOrNull()?.key
    }
    val byScore = offered.groupBy { it.mailboxId }
        .mapValues { (_, folderEvents) -> folderEvents.sumOf { recency(it.at, now) } }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { rank.getValue(it.key) })
        .map { it.key }
    val ids = (listOfNotNull(habit) + byScore).distinct().take(MOVE_SUGGESTIONS_MAX)
    return MoveSuggestions(habit, if (habit == null) ids else ids.drop(1))
}

/** What a picker suggests: [senderHabit], the sender's own folder when there is one, then [others],
 *  none of them repeating it. [folderIds] is the order the section shows. */
internal data class MoveSuggestions(val senderHabit: String?, val others: List<String>) {
    val folderIds: List<String> get() = listOfNotNull(senderHabit) + others
}

private fun recency(at: Long, now: Long): Double = 1.0 / (1.0 + (now - at).coerceAtLeast(0L) / DAY_MS / 7.0)

/** What a picker suggests for [accountId], computed once and held while it stays open. */
internal data class FrozenSuggestions(val accountId: String, val suggestions: MoveSuggestions)

/**
 * The suggestions to hold: [frozen] while it was computed for the [listed] account, so a row never
 * slides under the finger when a move lands during the pick; otherwise computed afresh, once
 * [history] was read for that very account and its [targets] are in. A history read for another
 * account is refused whole, the same pairing as `FolderOrdering.forList`: ids repeat between accounts.
 */
internal fun freezeSuggestions(
    frozen: FrozenSuggestions?,
    listed: String?,
    history: MoveHistory,
    targets: List<Mailbox>,
    sender: String?,
    now: Long,
): FrozenSuggestions? {
    if (frozen != null && frozen.accountId == listed) return frozen
    if (listed == null || history.accountId != listed || targets.isEmpty()) return null
    return FrozenSuggestions(listed, moveSuggestions(history.events, targets, sender, now))
}

/** A row of the Recent section, and whether it is the sender's own folder. */
internal data class SuggestedRow(val row: FolderPickerRow, val usualForSender: Boolean)

/** The painted rows of the Recent section: none as soon as something is typed in the filter,
 *  which then filters the whole list alone, and none held for another account than [listed]. */
internal fun suggestedRows(
    frozen: FrozenSuggestions?,
    listed: String?,
    rows: List<FolderPickerRow>,
    query: String,
): List<SuggestedRow> {
    if (query.isNotEmpty()) return emptyList()
    if (frozen == null || frozen.accountId != listed) return emptyList()
    val byId = rows.associateBy { it.folder.id }
    val habit = frozen.suggestions.senderHabit
    return frozen.suggestions.folderIds.mapNotNull { id -> byId[id]?.let { SuggestedRow(it, usualForSender = id == habit) } }
}

/** The one sender of a selection, lower-cased, or null when its messages have several, or when one
 *  of the [expected] messages was not read: an unknown message may have another sender. */
internal fun singleSender(emails: List<Email>, expected: Int): String? {
    if (emails.isEmpty() || emails.size != expected) return null
    val senders = emails.map { it.from.firstOrNull()?.email?.trim()?.lowercase() }
    val first = senders.first()?.takeIf { it.isNotEmpty() } ?: return null
    return first.takeIf { senders.all { it == first } }
}

/** A move chosen in a picker: the account and the folder it goes into. */
internal data class PickedMove(val accountId: String, val mailboxId: String)

/** What a picker's move recorded, so that its Undo can take it back. */
internal data class RememberedMove(val accountId: String, val event: MoveEvent)

/** The picked move a selection's move remembers: none from the selection bar's Inbox button, which
 *  moves without the user having chosen a folder, and none without an account to keep it under. */
internal fun pickedInto(fromPicker: Boolean, accountId: String?, mailboxId: String): PickedMove? {
    if (!fromPicker) return null
    return accountId?.let { PickedMove(it, mailboxId) }
}

/** The messages a single-message move really moved: the one message once its write landed, none
 *  for a refusal or a write that moved nothing. */
internal fun landedByTheSwipe(written: Result<String?>, email: Email): List<Email> =
    if (swipeWrite(written) == SwipeWrite.LANDED) listOf(email) else emptyList()

/** The messages a move between accounts really moved: only [CrossAccountMove.Moved]. A
 *  [CrossAccountMove.CopiedNotRemoved] left the original where it was, and did not file it. */
internal fun landedAcrossAccounts(
    outcomes: Map<EmailKey, CrossAccountMove>,
    emails: Map<EmailKey, Email>,
): List<Email> = outcomes.filterValues { it is CrossAccountMove.Moved }.keys.mapNotNull { emails[it] }

/** The event a picked move leaves once [landed] is known, null when nothing landed. */
internal fun pickedMoveEvent(landed: List<Email>, mailboxId: String, at: Long): MoveEvent? {
    if (landed.isEmpty()) return null
    return moveEventOf(mailboxId, at, landed.flatMap { email -> email.from.map { it.email } })
}
