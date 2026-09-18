package app.sterna.core.data.mail

import android.content.Context

/** [FlightMarkerStore] over a preferences file of its own. Not `sync_states`: every key there is
 *  read as a cursor by `SyncStateStore.keys()` and wiped by its version `clear()`. Key from
 *  [flightMarkerKey], value the source folder id. */
class FlightMarkerPrefs(context: Context) : FlightMarkerStore {
    private val prefs = context.getSharedPreferences("swipes_in_flight", Context.MODE_PRIVATE)

    // `commit()`, not `apply()`: an `apply()` still queued when the process is killed is lost, and
    // that kill is the one case this file exists for. Callers keep it off the main thread.
    // One edit for the whole batch, so a selection costs one `commit()` and not one per message.
    override fun write(markers: List<FlightMarker>) {
        if (markers.isEmpty()) return
        val edit = prefs.edit()
        markers.forEach { edit.putString(flightMarkerKey(it.accountId, it.emailId), it.mailboxId) }
        flightMarkersCommitted(edit.commit()) { "not written: ${markers.joinToString { "${it.accountId}/${it.emailId}" }}" }
    }

    override fun erase(accountId: String, emailIds: List<String>) {
        if (emailIds.isEmpty()) return
        val edit = prefs.edit()
        emailIds.forEach { edit.remove(flightMarkerKey(accountId, it)) }
        // Logged, not refused, unlike the write ([flightMarkersCommitted]): a marker still on disk
        // is an orphan the next start settles, and no caller acts on this having landed.
        if (!edit.commit()) android.util.Log.w("MailSync", "swipe markers not erased for $accountId: ${emailIds.joinToString()}")
    }

    override fun all(): List<FlightMarker> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { parseFlightMarker(key, it) } }
}
