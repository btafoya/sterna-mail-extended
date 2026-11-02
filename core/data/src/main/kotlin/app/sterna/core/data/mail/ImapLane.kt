package app.sterna.core.data.mail

import app.sterna.core.imap.ImapSession

/**
 * Which of an account's pooled connections a call runs on. [FOREGROUND] carries only the reads a
 * message being opened waits on, so the open never queues behind a folder walk on [SHARED] (#157);
 * every write stays on [SHARED].
 */
internal enum class ImapLane { SHARED, FOREGROUND }

internal data class ImapPoolKey(val accountId: String, val lane: ImapLane)

/** [probeMs], or what is left of the caller's budget when that is less. */
internal fun probeBound(probeMs: Int, leftMs: Int): Int =
    if (leftMs == ImapBudget.NO_BUDGET) probeMs else minOf(probeMs, leftMs)

/** A session left idle can have been dropped by the server or a NAT without a word; only a round trip tells. */
internal fun ImapSession.answersWithin(ms: Int): Boolean =
    try {
        withReadTimeout(ms) { noop() }
        true
    } catch (_: Exception) {
        false
    }

/** Off the caller's path: on a peer that has just failed a NOOP, the LOGOUT would wait out its own bound. */
internal fun closeAside(session: ImapSession) {
    Thread({ runCatching { session.close() } }, "imap-close-aside").apply { isDaemon = true }.start()
}
