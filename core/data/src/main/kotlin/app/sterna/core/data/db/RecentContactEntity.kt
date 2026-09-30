package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A person we've emailed (a send recipient). Received senders come from the cached
 */
@Entity(tableName = "recent_contacts")
data class RecentContactEntity(
    @PrimaryKey val email: String,
    val name: String?,
    val lastSeen: Long,
    /** Rows older than [MIGRATION_31_32] start at 1: each one is an address written to at least once. */
    val sendCount: Int = 1,
)
