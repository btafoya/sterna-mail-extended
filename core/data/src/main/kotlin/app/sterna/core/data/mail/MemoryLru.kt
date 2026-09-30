package app.sterna.core.data.mail

/**
 * A bounded map that forgets its least recently used entry, for the in-memory crypto caches. Not
 * `android.util.LruCache`, whose every method throws on the JVM, where the crypto read path is tested.
 */
internal class MemoryLru<V : Any>(private val maxEntries: Int) {
    private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: String): V? = entries[key]

    @Synchronized fun put(key: String, value: V) {
        entries[key] = value
    }

    @Synchronized fun remove(key: String) {
        entries.remove(key)
    }
}
