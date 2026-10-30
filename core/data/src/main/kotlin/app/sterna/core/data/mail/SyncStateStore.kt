package app.sterna.core.data.mail

import android.content.Context

/** Persists the per-(account, mailbox) JMAP sync cursors, write-through under [SyncCursors]' map:
 *  without it every process death forces a full re-query per folder (#17). Value format
 *  "queryState\nemailState". */
class SyncStateStore(context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences("sync_states", Context.MODE_PRIVATE)

    init {
        // Each version drops every cursor once. v2: collapsed-query queryStates, which can omit thread
        // members. v3, v4: rows cached before `answered`/`forwarded`, then `labelsJson`, read unset.
        if (cursorsOutdated(prefs.getInt(VERSION_KEY, 1))) clear()
    }

    override fun save(key: String, queryState: String, emailState: String) {
        prefs.edit().putString(key, "$queryState\n$emailState").apply()
    }

    override fun load(key: String): Pair<String, String>? {
        val raw = prefs.getString(key, null) ?: return null
        val split = raw.split('\n', limit = 2)
        return if (split.size == 2) split[0] to split[1] else null
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().putInt(VERSION_KEY, CURSOR_VERSION).apply()
    }

    /** The cursor keys held, without the schema marker, which must never reach a per-account drop. */
    override fun keys(): Set<String> = prefs.all.keys - VERSION_KEY
}

/** Store schema marker; sync keys are "<localAccountId><mailboxId>", which can't collide. */
private const val VERSION_KEY = "cursor_version"
private const val CURSOR_VERSION = 4

internal fun cursorsOutdated(stored: Int): Boolean = stored < CURSOR_VERSION
