package app.sterna.core.data.mail

/** A swipe whose server write had not answered yet, as it stands on disk. [mailboxId] is the folder
 *  its row was evicted from, the one whose full re-read settles it. */
data class FlightMarker(val accountId: String, val emailId: String, val mailboxId: String)

/** The durable half of the rows `MailRepository.beginRemoval` holds, implemented by [FlightMarkerPrefs].
 *  A write must be on disk when it returns: the kill a marker exists for is exactly the one that
 *  loses a write still queued in memory. */
interface FlightMarkerStore {
    fun write(marker: FlightMarker)
    fun erase(accountId: String, emailId: String)
    fun all(): List<FlightMarker>
}

/** Length-prefixed rather than separated: an IMAP id carries its folder path, and neither half of
 *  the pair has a character it is guaranteed not to contain. */
fun flightMarkerKey(accountId: String, emailId: String): String = "${accountId.length}:$accountId$emailId"

/** The marker [flightMarkerKey] wrote, or null for a key it cannot have written. */
fun parseFlightMarker(key: String, mailboxId: String): FlightMarker? {
    val colon = key.indexOf(':')
    val length = key.substring(0, colon.coerceAtLeast(0)).toIntOrNull() ?: return null
    val rest = key.substring(colon + 1)
    if (length <= 0 || length > rest.length) return null
    return FlightMarker(rest.substring(0, length), rest.substring(length), mailboxId)
}

/**
 * The swipes a previous process life left in flight. Its row is out of the cache while the server
 * still files the message, and the persisted cursors send the next sync down the delta branch,
 * which only fetches ids the cache already holds or the server reports added: without this, that
 * message never comes back.
 *
 * An orphan is any marker already on disk when this instance first looks, which happens before its
 * own first swipe writes one, so a flight of this process is never taken for an orphan.
 *
 * An orphan is cleared only once its folder has been read whole, never when its account's cursors
 * are dropped: that drop goes through `apply()`, a second kill can lose it, and the marker still on
 * disk is then the only thing that drops the cursors again on the next start.
 */
internal class OrphanedFlights(
    private val store: FlightMarkerStore?,
    // Null when the account list could not be read: an empty list there is not "signed out".
    private val configuredAccountIds: () -> Collection<String>?,
    private val dropCursors: (accountId: String) -> Unit,
) {
    // Guarded by its own monitor after the snapshot; `lazy` serialises the snapshot itself.
    private val orphans: MutableMap<String, FlightMarker> by lazy { adopt() }

    /** Take the snapshot and drop the cursors of every account an orphan names, once per instance. */
    fun settle() {
        orphans
    }

    /** Called before the eviction. A key that was an orphan becomes this flight's: the row is back in
     *  the cache or it could not have been swiped again, and the re-read must not erase this marker. */
    fun begin(marker: FlightMarker) {
        val orphans = orphans
        synchronized(orphans) { orphans.remove(flightMarkerKey(marker.accountId, marker.emailId)) }
        store?.write(marker)
    }

    fun end(accountId: String, emailId: String) {
        store?.erase(accountId, emailId)
    }

    /** [mailboxId] of [accountId] was just read whole: every orphan evicted from it is settled, its
     *  row written back by that read if the server still files the message there. */
    fun reread(accountId: String, mailboxId: String) {
        val disk = store ?: return
        val orphans = orphans
        synchronized(orphans) {
            val settled = orphans.filterValues { it.accountId == accountId && it.mailboxId == mailboxId }
            settled.forEach { (key, marker) ->
                disk.erase(marker.accountId, marker.emailId)
                orphans.remove(key)
            }
        }
    }

    /** Whether an orphan evicted from [mailboxId] of [accountId] still waits for [reread]. */
    fun awaitsReread(accountId: String, mailboxId: String): Boolean {
        val orphans = orphans
        return synchronized(orphans) { orphans.values.any { it.accountId == accountId && it.mailboxId == mailboxId } }
    }

    private fun adopt(): MutableMap<String, FlightMarker> {
        val disk = store ?: return HashMap()
        val found = disk.all()
        if (found.isEmpty()) return HashMap()
        val configured = configuredAccountIds()
        val (kept, signedOut) = found.partition { configured == null || it.accountId in configured }
        signedOut.forEach { disk.erase(it.accountId, it.emailId) }
        kept.map { it.accountId }.distinct().forEach(dropCursors)
        return kept.associateByTo(HashMap()) { flightMarkerKey(it.accountId, it.emailId) }
    }
}
