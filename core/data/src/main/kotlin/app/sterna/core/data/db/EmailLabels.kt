package app.sterna.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The `$label:<id>` keywords of a JMAP email, stored in [EmailEntity.labelsJson] as the list of
 *  ids, in the server's order, exactly as the server wrote them (case and spaces included). Only
 *  JMAP fills the column; an IMAP row and a local draft keep `[]`. Rows cached before v38 read `[]`
 *  until `CURSOR_VERSION` 4 sends their folder into one full read. In `reconcileCachedFlags` the
 *  row is the source: once its labels or flags differ from a cached body's, every `$label:` key of
 *  the body, a `false` one included, gives way; while they agree, the body is handed back as is. */
object EmailLabels {
    const val PREFIX = "\$label:"

    private val serializer = ListSerializer(String.serializer())

    fun encode(ids: List<String>): String = Json.encodeToString(serializer, ids)

    /** A blob that cannot be read is no labels: a label is shown, never acted upon. */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /** The ids of the `$label:` keywords [keywords] holds `true`, in map order. */
    fun of(keywords: Map<String, Boolean>): List<String> =
        keywords.mapNotNull { (key, held) -> if (held && key.startsWith(PREFIX)) key.removePrefix(PREFIX) else null }

    /** [ids] with [id] held when [set], dropped otherwise; the others keep their order. */
    fun with(ids: List<String>, id: String, set: Boolean): List<String> =
        if (set) (if (id in ids) ids else ids + id) else ids.filter { it != id }

    /**
     * Every label of an account, from the [EmailEntity.labelsJson] blobs of its rows. Two ids that
     * differ only by case are two keywords on the server and stay two entries; the case is ignored
     * by the sort alone. A blank id cannot be shown, so it is not offered either.
     */
    fun union(blobs: List<String>): List<String> = merge(blobs.flatMap { decode(it) })

    /** [ids] in the order and with the deduplication of [union]. */
    fun merge(ids: List<String>): List<String> =
        ids.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
}
