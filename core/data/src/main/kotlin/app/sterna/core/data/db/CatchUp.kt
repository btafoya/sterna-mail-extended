package app.sterna.core.data.db

/**
 * Bit of [EmailEntity.catchUp] (v34): older mail of the folder may be missing between this row and
 * the rows its list walked. Set by the unread catch-up, on the rows it inserts and on those it files
 * back, and by the JMAP retention prune through [EmailDao.markOffWalk], on an unread row it keeps
 * while the read mail around it is evicted. Cleared by [EmailDao.markWalked], on the rows a page of
 * the folder's list writes and those an IMAP read keeps in its window; never by a JMAP delta.
 */
const val CATCH_UP_OFF_WALK: Int = 1

/**
 * Bit of [EmailEntity.catchUp], independent of [CATCH_UP_OFF_WALK]: the row is never announced as
 * new mail. Set by the catch-up alone; it lasts as long as the row, a list page does not clear it.
 */
const val CATCH_UP_SILENT: Int = 2

/** Most ids one `IN (...)` list of [upsertKeepingCatchUp] carries, under SQLite's 999 bound variables. */
private const val CATCH_UP_READ_CHUNK = 500

/** Projection for [EmailDao.catchUpOf]. */
data class CatchUpMark(
    val id: String,
    val catchUp: Int,
)

/**
 * [fresh] as the unread catch-up inserts it: every row [CATCH_UP_OFF_WALK] whatever its age, and
 * [CATCH_UP_SILENT] as well when it is undated or was received before [staleBefore].
 */
internal fun caughtUp(fresh: List<EmailEntity>, staleBefore: Long): List<EmailEntity> = fresh.map { row ->
    val tooOldToBeNew = row.sortKey == 0L || row.sortKey < staleBefore
    row.copy(catchUp = if (tooOldToBeNew) CATCH_UP_OFF_WALK or CATCH_UP_SILENT else CATCH_UP_OFF_WALK)
}

/**
 * [emails] with the mark of the cached row each one is about to overwrite, [cached] being keyed by
 * `(accountId, id)`. A row the cache does not hold keeps the mark it comes with: nothing for a row
 * mapped from the server, and what an Undo read before the row was evicted.
 */
internal fun keepingCatchUp(emails: List<EmailEntity>, cached: Map<Pair<String, String>, Int>): List<EmailEntity> =
    emails.map { row -> cached[row.accountId to row.id]?.let { row.copy(catchUp = it) } ?: row }

/**
 * The body of [EmailDao.upsertAll]: writes [emails] whole through [write] without touching the mark
 * of a row already cached, read through [marksOf] per account. [write] is [EmailDao.upsertRows], an
 * `@Upsert` that writes every column of the entity, and an entity mapped from the server carries no
 * mark: called bare, a delta re-reading a row would erase both bits, and a list page would erase
 * [CATCH_UP_SILENT] with the bit it is there to clear. Nothing else may call it.
 */
internal suspend fun upsertKeepingCatchUp(
    emails: List<EmailEntity>,
    marksOf: suspend (accountId: String, ids: List<String>) -> List<CatchUpMark>,
    write: suspend (List<EmailEntity>) -> Unit,
) {
    if (emails.isEmpty()) return
    val cached = HashMap<Pair<String, String>, Int>()
    emails.groupBy({ it.accountId }, { it.id }).forEach { (accountId, ids) ->
        ids.chunked(CATCH_UP_READ_CHUNK).forEach { chunk ->
            marksOf(accountId, chunk).forEach { cached[accountId to it.id] = it.catchUp }
        }
    }
    write(keepingCatchUp(emails, cached))
}
