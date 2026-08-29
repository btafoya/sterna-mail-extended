package app.sterna.ui.inbox

/**
 * "Has this very view just been reconciled?" — as pure functions, so the one rule that decides
 * whether a refresh is worth firing is executable by a plain JVM test.
 *
 * Codeberg #178: the reporter opens and closes the app all day long, and every launch, every
 * account switch and every folder tap fired a full reconcile she never asked for. A view that
 * came back a moment ago is not going to have changed, so it is not fetched again.
 *
 * ⭐ The register is PERSISTED (`AccountStore.refreshFreshness`), on purpose: her case IS the cold
 * start — the phone kills the app, she reopens it seconds later — and an in-memory register dies
 * with the process, i.e. covers exactly none of it. The public promise on that thread says so in
 * so many words: "including when the phone killed the app and you reopen it right after".
 *
 * Two shapes are borrowed from [SelectionMemory], for the reasons written there:
 *  - ⛔ the key carries the ACCOUNT ID next to the folder id, never a bare folder id. Two accounts
 *    on the same server number their mailboxes independently, and a bare id would let one
 *    account's freshly-read folder pass for its sibling's (#92 / #121);
 *  - ⛔ a folder id can hold ANY character — an IMAP path decoded from modified UTF-7 carries
 *    whatever the name held, line feeds and tabs included — so an entry that cannot be written
 *    without ambiguity is REFUSED rather than mis-split. A refused entry is simply not remembered,
 *    which costs one extra refresh; the error is taken on the side that fetches too much, never on
 *    the side that freezes a stale list.
 */

/**
 * How long a successful reconcile answers for its view.
 *
 * 30 s is the length of the gesture being defended: leave the app, come back. It is deliberately
 * far shorter than any interval at which mail actually matters, and it is bypassed entirely by the
 * pull gesture and the two Retry buttons — the reader can always demand a fetch and get one.
 */
internal const val REFRESH_FRESHNESS_WINDOW_MS = 30_000L

/** One (account, folder) pair reconciled successfully, and when. */
internal data class FreshScope(val accountId: String, val mailboxId: String, val at: Long)

/** Between the three fields of an entry. */
private const val FIELD_SEPARATOR = "\t"

/** Between entries. */
private const val ENTRY_SEPARATOR = "\n"

/**
 * Whether this entry can be written down and read back as itself. Both separators are refused in
 * both ids, and so is an empty id: an empty field is indistinguishable from a truncated line at
 * decode time, and `decode(encode(x)) == x` has to hold for everything [encodeFreshness] keeps.
 *
 * (An account id cannot break this — `AccountStore` mints them with `UUID.randomUUID()` — a folder
 * id very much can.)
 */
private fun FreshScope.isStorable(): Boolean =
    accountId.isNotEmpty() && mailboxId.isNotEmpty() &&
        !accountId.contains(FIELD_SEPARATOR) && !accountId.contains(ENTRY_SEPARATOR) &&
        !mailboxId.contains(FIELD_SEPARATOR) && !mailboxId.contains(ENTRY_SEPARATOR)

/**
 * Whether [this] entry still answers for its view at [now].
 *
 * ⛔ The LOWER bound matters as much as the upper one. The clock at the call site is
 * `SystemClock.elapsedRealtime` (see the clock in `InboxViewModel`), which RESTARTS at a reboot: a
 * stamp written before one then lies in the future, and a stamp in the future must read as STALE
 * or the list would never reconcile again — for the whole uptime, or for ever. Reading it as stale
 * is exactly right here: a reboot is far longer than the window, so nothing survives it as fresh.
 * The same guard covers a wall clock moved backwards, should the call site ever pick one.
 *
 * Written as two comparisons rather than `now - at in 0 until WINDOW` so a nonsense value read
 * back out of corrupted preferences (`Long.MIN_VALUE`) cannot overflow the subtraction into a
 * small positive number, i.e. into "fresh".
 */
private fun FreshScope.isFreshAt(now: Long): Boolean =
    at <= now && at > now - REFRESH_FRESHNESS_WINDOW_MS

/**
 * The register as stored, or an empty list when there is none. Every malformed line — wrong field
 * count, unreadable stamp, empty id, blank line — is dropped SILENTLY: these are preferences, one
 * day somebody edits the file, and the cost of a dropped line is one refresh, while the cost of a
 * throw is a crash in a ViewModel constructor.
 */
internal fun decodeFreshness(stored: String?): List<FreshScope> {
    if (stored.isNullOrEmpty()) return emptyList()
    return stored.split(ENTRY_SEPARATOR).mapNotNull { line ->
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 3) return@mapNotNull null
        val at = parts[2].toLongOrNull() ?: return@mapNotNull null
        FreshScope(parts[0], parts[1], at).takeIf { it.isStorable() }
    }
}

/**
 * [entries] as the opaque string the store holds, or `null` when there is nothing worth storing —
 * which the caller writes as `null`, i.e. "nothing is fresh, reconcile whatever is asked".
 *
 * Entries that cannot be written unambiguously are dropped here rather than escaped: see
 * [isStorable].
 */
internal fun encodeFreshness(entries: List<FreshScope>): String? {
    val lines = entries.filter { it.isStorable() }
        .map { "${it.accountId}$FIELD_SEPARATOR${it.mailboxId}$FIELD_SEPARATOR${it.at}" }
    return if (lines.isEmpty()) null else lines.joinToString(ENTRY_SEPARATOR)
}

/**
 * Whether a refresh over [scopes] can be skipped at [now]: EVERY scope the view spans must have
 * been reconciled inside the window. One stale account in the unified inbox is a stale unified
 * inbox.
 *
 * ⛔ An EMPTY [scopes] is never fresh. That is the state of an account that has never synced (no
 * folder id yet), and calling it fresh would skip the very first fetch and freeze an empty list on
 * screen — with nothing but a pull gesture to get out of it.
 */
internal fun isFresh(entries: List<FreshScope>, scopes: List<Pair<String, String>>, now: Long): Boolean {
    if (scopes.isEmpty()) return false
    return scopes.all { (accountId, mailboxId) ->
        entries.any { it.accountId == accountId && it.mailboxId == mailboxId && it.isFreshAt(now) }
    }
}

/**
 * The register after [scopes] were reconciled successfully at [now].
 *
 * Everything outside the window is pruned on the way through — stamps in the past AND in the
 * future — which is what bounds the size of the thing: it can never hold more than the folders one
 * reader visited in the last 30 s. An existing entry for a recorded scope is REPLACED, not
 * doubled.
 */
internal fun recordFresh(
    entries: List<FreshScope>,
    scopes: List<Pair<String, String>>,
    now: Long,
): List<FreshScope> {
    val recorded = scopes.distinct()
    val keys = recorded.toSet()
    val kept = entries.filter { it.isFreshAt(now) && (it.accountId to it.mailboxId) !in keys }
    return kept + recorded.map { (accountId, mailboxId) -> FreshScope(accountId, mailboxId, now) }
}

/**
 * The register once [removedIds] have been signed out: their entries FILTERED OUT and the rest
 * re-encoded, or `null` when nothing is left — [encodeFreshness] already answers `null` for an
 * empty register, which the caller writes as `null`, i.e. the key leaves the preferences.
 *
 * Same promise as [prunedView]'s, on the other account-scoped preference: what an account left
 * behind goes when the account goes (`PRIVACY.md`, "removed when you remove the account"). Here
 * every entry is keyed on (account, folder), so a removed account's folder ids — IMAP paths the
 * reader named herself — stayed on the disk with nobody left to read them.
 *
 * ⛔ A FILTER, never a wipe. [recordFresh] prunes by TIME only and there is no other pruning in
 * this file; clearing the whole register would cost every SURVIVING account one full reconcile it
 * did not need, which is the exact refresh #178 exists to avoid.
 *
 * ⛔ The `accountId` decides, never the `mailboxId`: two accounts on the same server number their
 * mailboxes independently (#92 / #121), so a survivor's entry may carry the same folder id as a
 * removed account's and must stay.
 */
internal fun prunedFreshness(stored: String?, removedIds: Set<String>): String? =
    encodeFreshness(decodeFreshness(stored).filterNot { it.accountId in removedIds })
