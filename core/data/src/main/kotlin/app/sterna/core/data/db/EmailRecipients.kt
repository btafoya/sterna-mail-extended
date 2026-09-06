package app.sterna.core.data.db

import app.sterna.core.jmap.model.EmailAddress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * (De)serialises the `To:` recipients stored in [EmailEntity.recipientsJson], via kotlinx JSON
 * of [EmailAddress] directly.
 *
 * Total both ways: empty list encodes to null; null, blank or unparseable decodes to empty list
 * — a pre-v17 row simply has no recipients and falls back to showing its sender.
 */
object EmailRecipients {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EmailAddress.serializer())

    fun encode(addresses: List<EmailAddress>): String? =
        if (addresses.isEmpty()) null else json.encodeToString(serializer, addresses)

    fun decode(raw: String?): List<EmailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
