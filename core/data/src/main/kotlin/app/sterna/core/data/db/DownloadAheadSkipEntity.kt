package app.sterna.core.data.db

import androidx.room.Entity

/**
 * A message the download ahead read and will not read again: its text is too large for the body
 * cache, or it is signed or encrypted, whose body is never cached. Keyed like `emails`, and dropped
 * once that row is gone ([EmailBodyDao.pruneDownloadAheadSkips]).
 */
@Entity(
    tableName = "download_ahead_skips",
    primaryKeys = ["accountId", "id"],
)
data class DownloadAheadSkipEntity(
    val accountId: String,
    val id: String,
)
