package app.sterna.core.data.mail

import android.content.Context

/**
 * The ONE version whose arrival needs the cached message bodies gone: the release that taught
 * the reader to look for the unsubscribe headers (`versionCode` 168, i.e. the release after
 * 1.4.7 = 167). A literal on purpose — it is a fact about a past upgrade, not the current build
 * number, and it must not move when the version does.
 *
 * **The day another change needs the cached bodies dropped, add a SECOND constant and a second
 * threshold; do not raise this one and do not go back to "purge on every version bump".** A
 * threshold is a one-off answer to a one-off need. "Every version bump" is a permanent policy
 * that throws away every body on the first start after each update — every message read again
 * becoming a network round trip, and nothing openable offline — in exchange for a need that
 * arose exactly once.
 */
const val BODY_CACHE_PURGE_VERSION = 168

/**
 * The SECOND such version — the second constant the KDoc above prescribes rather than raising the
 * first one, because 168 is a fact about an upgrade that has already happened and must keep
 * answering for it.
 *
 * **Why 170 needs the bodies gone.** The release that publishes the read-receipt work (#148) reads
 * `Disposition-Notification-To` off the message (`Email.dispositionNotificationTo`). A body
 * serialised by 1.4.9 simply has no such field, and `openMessage` serves the cached body before
 * reading anything off the message — so the banner offering to answer a read receipt cannot appear
 * on mail already in the cache, and no amount of reopening brings the field back. Worse than after
 * 168: whoever ran 1.4.9 recorded `purgedForVersion = 169`, so the first threshold can never be
 * crossed again by anyone. Without a second one, that user never purges again for the life of the
 * install, and the prefetch keeps precisely the twenty newest inbox messages — the ones they will
 * try it on — warm and fieldless.
 *
 * **170 must be the `versionCode` of the cut that CARRIES #148**, not merely the next number.
 * `versionCode` is 169 (1.4.9) as this is written, so the next cut is 170 — but a cut that skips
 * #148 breaks this. Late is harmless: with no 170 ever published, 169 → 171 still crosses 170 and
 * purges (`jumping over the second threshold purges on arrival`). **The dangerous case is an
 * INTERLEAVED release**: ship a corrective 1.4.10 as `versionCode` 170 WITHOUT #148, and the 1.4.9
 * user purges their bodies for nothing, records 170, then gets #148 in 171 — no threshold left to
 * cross, no banner, ever, in silence and with the suite green from end to end.
 *
 * So `/release` checks one thing: this value is the `versionCode` of the release that publishes
 * #148. If an unrelated cut takes 170 first, this constant moves up to the one that does carry it.
 */
const val BODY_CACHE_PURGE_VERSION_2 = 170

/**
 * The THIRD such version — a third constant for the same reason the second one exists: 168 and 170
 * are facts about upgrades that have already happened and must keep answering for them.
 *
 * **Why 172 needs the bodies gone.** The release that publishes the origin-sender work (#160) reads
 * an `X-…-Original-From…` field off the message (`Email.originalSender`). A body serialised by
 * 1.5.1 simply has no such field — it deserialises without error, defaulting to null — and
 * `openMessage` serves the cached body before touching the network, so the "Original sender" line
 * cannot appear on mail already in the cache, and no amount of reopening brings it back. The
 * prefetch keeps the twenty newest inbox messages warm, which is exactly where a freshly relayed
 * message sits: the reporter would try the update on the one message that cannot show it.
 *
 * And the two earlier thresholds cannot help: whoever runs 1.5.1 recorded
 * `purgedForVersion = 171`, which is past both. Without a third threshold that install never
 * purges again for the life of it.
 *
 * **172 must be the `versionCode` of the cut that CARRIES #160**, not merely the next number.
 * `versionCode` is 171 (1.5.1) as this is written, so the next cut is 172 — but a cut that skips
 * #160 breaks this. Late is harmless: with no 172 ever published, 171 → 173 still crosses 172 and
 * purges. **The dangerous case is an INTERLEAVED release**: ship a corrective as `versionCode`
 * 172 WITHOUT #160, and the 1.5.1 user purges their bodies for nothing, records 172, then gets
 * #160 in 173 — no threshold left to cross, no line, ever, in silence and with the suite green
 * from end to end.
 *
 * So `/release` checks one thing: this value is the `versionCode` of the release that publishes
 * #160. If an unrelated cut takes 172 first, this constant moves up to the one that does carry it.
 */
const val BODY_CACHE_PURGE_VERSION_3 = 172

/**
 * The thresholds, in the order they were needed. Crossing ANY of them purges once.
 *
 * A list of one-off answers, never a policy: nothing here is derived from the current build
 * number, so no entry ever appears on its own when the version moves.
 */
private val BODY_CACHE_PURGE_VERSIONS =
    listOf(BODY_CACHE_PURGE_VERSION, BODY_CACHE_PURGE_VERSION_2, BODY_CACHE_PURGE_VERSION_3)

/**
 * Which version to record after dropping the cached message bodies at startup, or null when
 * there is nothing to do — the whole decision behind [BodyCachePurge], as one executed function.
 *
 * **Why a cache written by an older build has to go.** `openMessage` serves a cached body BEFORE
 * reading anything off the message, and the cache is a serialised `Email`: a row written by a
 * build that did not know a field simply does not have it, and no amount of reopening the message
 * brings it back. The bodies most likely to be in there are the twenty most recent of the inbox,
 * kept warm by the prefetch — which, for most people, is largely newsletters. So the one feature
 * that reads a new header would have been invisible on exactly the mail it was written for, and
 * would only have appeared on what arrived afterwards. Not a bug anyone could report: just a
 * button that is not there.
 *
 * A cache is the one store where this is free: it refills itself from the server on the next open.
 * Hence no migration, no column, no schema change — a DELETE and a number in a preference file.
 *
 * **A threshold CROSSED, not a version bump.** Purge if and only if AT LEAST ONE threshold in
 * [BODY_CACHE_PURGE_VERSIONS] has not yet been purged for and the build now running is that
 * version or later. Crossed, not equalled: someone who skips from 1.4.6 straight to 1.4.9 must
 * purge once on arrival, and someone who jumps 169 → 171 must purge once for 170 they never ran.
 *
 * **Once per threshold, and nothing in between.** What gets recorded is `currentVersion`, not the
 * threshold: a launch at 171 that crossed 170 records 171, so every threshold at or below it is
 * settled for good and no later launch can purge for it twice. A version bump that crosses no
 * threshold — 168 to 169, 170 to 999 — does nothing at all, which is the whole difference between
 * this and "purge on every version bump".
 */
fun bodyCachePurgeVersion(purgedForVersion: Int, currentVersion: Int): Int? =
    currentVersion.takeIf {
        BODY_CACHE_PURGE_VERSIONS.any { threshold ->
            purgedForVersion < threshold && it >= threshold
        }
    }

/**
 * The once-per-upgrade purge of the cached message bodies, remembered in its own small preference
 * file (the decision itself is [bodyCachePurgeVersion]).
 *
 * Bodies ONLY. Not the message list, not the attachments, not the search index: those are not
 * caches in the same sense — losing them costs a full resync, and none of them holds a serialised
 * `Email` that an older build could have written short of a field.
 *
 * The version is recorded only AFTER the purge succeeds, so a failure (a locked database at
 * startup, say) is simply retried at the next launch instead of being silently skipped forever.
 */
class BodyCachePurge(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("body_cache_upgrade", Context.MODE_PRIVATE)

    suspend fun onceForVersion(currentVersion: Int, purge: suspend () -> Unit) {
        val toRecord = bodyCachePurgeVersion(prefs.getInt(KEY_PURGED_FOR, 0), currentVersion) ?: return
        purge()
        prefs.edit().putInt(KEY_PURGED_FOR, toRecord).apply()
    }

    private companion object {
        const val KEY_PURGED_FOR = "purgedForVersion"
    }
}
