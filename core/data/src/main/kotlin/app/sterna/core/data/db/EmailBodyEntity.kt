package app.sterna.core.data.db

import androidx.room.Entity

/**
 * Cached full body of an opened/prefetched message; separate from [EmailEntity] since bodies
 * are large and evicted on their own LRU schedule. [bodyJson] is a serialized
 * [app.sterna.core.jmap.model.Email]; [inlineImagesJson] is the cid→data: URI map, empty until
 * images are downloaded on first open.
 *
 * Composite `(accountId, id)` key: two accounts of one login (#31) can cache bodies under the
 * same JMAP email id, and a single-column key would serve one account the other's body.
 */
@Entity(
    tableName = "email_bodies",
    primaryKeys = ["accountId", "id"],
)
data class EmailBodyEntity(
    val id: String,
    val accountId: String,
    val bodyJson: String,
    val inlineImagesJson: String,
    /** Epoch millis when fetched, for LRU eviction. */
    val fetchedAt: Long,
)
