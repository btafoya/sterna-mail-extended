package app.sterna.pgp

/**
 * Whether the key ids the provider answered for [addressCount] recipient addresses can be encrypted
 * to. [picked] is true when the answer comes from the provider's key selector: it returns a set with
 * no address-to-key mapping, and one key standing for two addresses is legitimate, so a shorter list
 * passes then and only then. An empty list never passes: the sender's own key is added downstream, and
 * the message would be encrypted to it alone.
 */
internal fun recipientKeyIdsAccepted(ids: LongArray?, addressCount: Int, picked: Boolean): Boolean = when {
    ids == null || ids.isEmpty() -> false
    picked -> true
    else -> ids.size >= addressCount
}
