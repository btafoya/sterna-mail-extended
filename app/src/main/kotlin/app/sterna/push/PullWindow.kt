package app.sterna.push

import app.sterna.core.data.account.SyncWindow

/**
 * The `limit` and `pruneBeforeMillis` a pull-to-refresh hands `MailRepository.refresh`, both off one
 * [SyncWindow]: the repository reads the two together, `limit` being the retention floor of an age
 * window, so they cannot come from two different places.
 */
fun pullWindowArgs(window: SyncWindow, nowMillis: Long): Pair<Int, Long?> =
    window.limit to window.maxAgeDays?.let { nowMillis - it.toLong() * MILLIS_PER_DAY }

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
