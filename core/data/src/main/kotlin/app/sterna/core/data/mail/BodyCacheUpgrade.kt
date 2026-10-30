package app.sterna.core.data.mail

import android.content.Context

/** The versions whose arrival needs the cached bodies gone: 168 for the unsubscribe headers, 170 and
 *  172 for the cached shape, 177 for a MIME preamble, 186 for `Email.authenticationFailures`. */
const val BODY_CACHE_PURGE_VERSION = 168

const val BODY_CACHE_PURGE_VERSION_2 = 170

const val BODY_CACHE_PURGE_VERSION_3 = 172

const val BODY_CACHE_PURGE_VERSION_4 = 177

/** A body cached without the sender verdict would leave it unknown for good. */
const val BODY_CACHE_PURGE_VERSION_5 = 186

/** Crossing any of them purges once. Nothing here is derived from the current build number. */
private val BODY_CACHE_PURGE_VERSIONS =
    listOf(
        BODY_CACHE_PURGE_VERSION,
        BODY_CACHE_PURGE_VERSION_2,
        BODY_CACHE_PURGE_VERSION_3,
        BODY_CACHE_PURGE_VERSION_4,
        BODY_CACHE_PURGE_VERSION_5,
    )

/**
 * `openMessage` serves a cached body before reading the message, and the cache is a serialised
 * `Email`, so a row written short of a field never gains it. A threshold is crossed, not equalled.
 */
fun bodyCachePurgeVersion(purgedForVersion: Int, currentVersion: Int): Int? =
    currentVersion.takeIf {
        BODY_CACHE_PURGE_VERSIONS.any { threshold ->
            purgedForVersion < threshold && it >= threshold
        }
    }

/** Bodies only. The version is recorded after the purge succeeds, so a failure is retried. */
class BodyCachePurge(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("body_cache_upgrade", Context.MODE_PRIVATE)

    suspend fun onceForVersion(currentVersion: Int, purge: suspend () -> Unit) {
        val toRecord = bodyCachePurgeVersion(prefs.getInt(KEY_PURGED_FOR, 0), currentVersion) ?: return
        purge()
        prefs.edit().putInt(KEY_PURGED_FOR, toRecord).apply()
    }

    private companion object {
        const val KEY_PURGED_FOR = "purgedForVersion"
    }
}
