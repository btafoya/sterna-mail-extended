package app.sterna.core.data.mail

import android.content.Context

/** [FlightMarkerStore] over a preferences file of its own. Not `sync_states`: every key there is
 *  read as a cursor by `SyncStateStore.keys()` and wiped by its version `clear()`. Key from
 *  [flightMarkerKey], value the source folder id. */
class FlightMarkerPrefs(context: Context) : FlightMarkerStore {
    private val prefs = context.getSharedPreferences("swipes_in_flight", Context.MODE_PRIVATE)

    // `commit()`, not `apply()`: an `apply()` still queued when the process is killed is lost, and
    // that kill is the one case this file exists for. Callers keep it off the main thread.
    override fun write(marker: FlightMarker) {
        val ok = prefs.edit().putString(flightMarkerKey(marker.accountId, marker.emailId), marker.mailboxId).commit()
        if (!ok) android.util.Log.w("MailSync", "swipe marker not written for ${marker.accountId}/${marker.emailId}")
    }

    override fun erase(accountId: String, emailId: String) {
        val ok = prefs.edit().remove(flightMarkerKey(accountId, emailId)).commit()
        if (!ok) android.util.Log.w("MailSync", "swipe marker not erased for $accountId/$emailId")
    }

    override fun all(): List<FlightMarker> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { parseFlightMarker(key, it) } }
}
