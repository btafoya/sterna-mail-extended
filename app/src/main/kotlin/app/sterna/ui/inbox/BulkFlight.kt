package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.MailRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One row a bulk action is about to write: [key] is what the list draws it under, [emailId] what
 *  the server knows it by. Not interchangeable: a row the list cannot name an account for is
 *  routed to the current account, and its ticket is taken under that account's id, not the key's. */
internal data class BulkRow(val key: EmailKey, val emailId: String)

/** One account's share of a bulk action. Never two accounts in one group: two accounts of one
 *  server share message ids (#31), and a ticket is closed by account and id. */
internal data class BulkGroup<C>(val credentials: C, val accountId: String, val rows: List<BulkRow>)

/** What one account's credentials may fly, and the keys they may not touch at all. */
internal data class BulkGrouped<C>(val group: BulkGroup<C>?, val refused: List<EmailKey>)

/**
 * The rows [credentials] may take a ticket for, and the keys they may not. A key naming another
 * account reaches these credentials because its own account no longer resolves (signed out, with
 * its rows still drawn in the unified view), and the ticket taken here would evict the row of the
 * SAME id in [accountId] and decount that folder, two accounts of one server sharing message ids
 * (#31). A key naming no account is this account's by construction, the list drew it from here.
 */
internal fun <C> bulkGroupUnder(credentials: C, accountId: String, rows: List<BulkRow>): BulkGrouped<C> {
    val (mine, foreign) = rows.partition { it.key.accountId == null || it.key.accountId == accountId }
    return BulkGrouped(
        group = if (mine.isEmpty()) null else BulkGroup(credentials, accountId, mine),
        refused = foreign.map { it.key },
    )
}

/** The keys the server refused, matched inside their own group and never across it: two accounts
 *  of one server share message ids (#31). These rows never left, so they go back into an open
 *  search and into the failure count. */
internal fun <C> refusedKeys(flown: List<Pair<BulkGroup<C>, MailRepository.BulkResult>>): List<EmailKey> =
    flown.flatMap { (group, result) -> group.rows.filter { it.emailId in result.failed }.map { it.key } }

/** The keys the server did move, each with the folder its own group landed in: an Undo reverses a
 *  move by the destination of the batch that made it, and two groups can land in two folders. */
internal fun <C> movedKeys(flown: List<Pair<BulkGroup<C>, MailRepository.BulkResult>>): List<Pair<EmailKey, String?>> =
    flown.flatMap { (group, result) ->
        group.rows.filter { it.emailId in result.succeeded }.map { it.key to result.dest }
    }

/**
 * One group's share written a message at a time, answered as one verdict: the movers a swipe hands
 * over take a single id, and the flight reads a [MailRepository.BulkResult].
 *
 * An id whose write moved nothing is `failed`, which is the only answer that puts its row back: the
 * message is still where the reader saw it. [refusal] is called for a throw and for nothing else,
 * the sentence a gesture shows being owed to a refusal alone. One destination for the group, the
 * first a write named: these movers resolve their target folder per account, not per message.
 */
internal suspend fun writeOneByOne(
    ids: List<String>,
    refusal: (Throwable) -> Unit,
    write: suspend (String) -> String?,
): MailRepository.BulkResult {
    val succeeded = mutableSetOf<String>()
    val failed = mutableSetOf<String>()
    var dest: String? = null
    ids.forEach { id ->
        runCatching { write(id) }
            .onSuccess { landed ->
                if (landed == null) failed += id else succeeded += id
                dest = dest ?: landed
            }
            .onFailure {
                failed += id
                refusal(it)
            }
    }
    return MailRepository.BulkResult(succeeded, failed, dest)
}

/**
 * Take a whole selection off the list at the tap, and put back only what the server refused.
 *
 * Every row is masked and every ticket taken before the first write leaves: one account's round
 * trip is seconds long on a phone, and a ticket taken inside the write loop would leave the next
 * account's rows drawn for the whole of it.
 *
 * The close is unconditional and uncancellable, as a swipe's is: the scope dies with the activity,
 * and a ticket nobody closes leaves its keys standing, which hides those messages from every page
 * read from the server for the life of the process. Taking the tickets is inside the close's reach
 * too: `beginRemovalAll` throws on a cancelled scope and on a Room failure, and a group it threw
 * on leaves the earlier accounts holding open tickets and the later ones masked with nothing
 * coming that could ever unmask them. A group the flight never wrote has no verdict, so the whole
 * of it comes back: a write cut off mid-flight moved nothing.
 *
 * Answers with the groups that were written and what each answered; a cancelled flight answers
 * nothing, it throws out of the close.
 */
internal suspend fun <C> flyBulkGroups(
    groups: List<BulkGroup<C>>,
    pose: (List<EmailKey>) -> Unit,
    begun: (EmailKey, Boolean) -> Unit,
    lift: (List<EmailKey>) -> Unit,
    begin: suspend (String, List<String>) -> MailRepository.InFlightBatch?,
    write: suspend (C, List<String>) -> MailRepository.BulkResult,
    end: suspend (MailRepository.InFlightBatch, Collection<String>) -> Unit,
): List<Pair<BulkGroup<C>, MailRepository.BulkResult>> {
    if (groups.isEmpty()) return emptyList()
    val tickets = arrayOfNulls<MailRepository.InFlightBatch>(groups.size)
    val verdicts = arrayOfNulls<MailRepository.BulkResult>(groups.size)
    // How many groups were told their eviction's verdict. Past it, `begun` never ran, so nothing
    // but the close below can ever lift what `pose` hid.
    var begunThrough = 0
    try {
        pose(groups.flatMap { group -> group.rows.map { it.key } })
        groups.forEachIndexed { index, group ->
            tickets[index] = begin(group.accountId, group.rows.map { it.emailId })
            val held = tickets[index]?.emailIds.orEmpty().toSet()
            // A row nothing evicted has no generation coming that could ever lift its key: it goes now.
            group.rows.forEach { begun(it.key, it.emailId in held) }
            begunThrough = index + 1
        }
        groups.forEachIndexed { index, group ->
            verdicts[index] = write(group.credentials, group.rows.map { it.emailId })
        }
    } finally {
        withContext(NonCancellable) {
            groups.forEachIndexed { index, group ->
                val ticket = tickets[index]
                val back: Set<String> = when (ticket) {
                    null -> emptySet()
                    else -> verdicts[index]?.failed ?: group.rows.mapTo(mutableSetOf()) { it.emailId }
                }
                if (ticket != null) end(ticket, back)
                // After the put-back, never before: unmasked first, the row shows from the
                // generation still on screen, leaves with the one that evicted it, and comes back.
                // A group that never reached `begun` is unmasked whole, ticket or no ticket: it is
                // posed, nothing evicted it, and no generation carries a lift for it.
                val home = if (index >= begunThrough) group.rows else group.rows.filter { it.emailId in back }
                lift(home.map { it.key })
            }
        }
    }
    return groups.mapIndexedNotNull { index, group -> verdicts[index]?.let { group to it } }
}
