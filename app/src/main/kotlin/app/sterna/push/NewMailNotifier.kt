package app.sterna.push

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FolderRefresh
import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.mail.announceableAt
import app.sterna.core.data.mail.notifyFloor
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Notifies for newly arrived mail against a PERSISTED per-folder baseline (the ids
 * already seen/announced, keyed "accountId:mailboxId"). Shared by [PushService] (live
 * push) and [MailFetchWorker] (the periodic fallback when push is dead — Codeberg #11),
 * so the two never double-notify and a service death never loses the "what have I
 * already announced" state. Multi-folder watch is Codeberg #16.
 */
object NewMailNotifier {
    private const val PREFS = "push_baselines"
    private const val VERSION_KEY = "baseline_version"
    // v2: folder syncs went uncollapsed, so the first full re-query after the update
    // caches every thread member — mail that sat on the server all along but was never
    // in these baselines (they only ever saw representatives).
    private const val BASELINE_VERSION = 2

    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // On the version bump, wipe the stale baselines once so each folder's next pass
        // reseeds silently from the then-current cache ([FetchAndNotify] seeds whenever a
        // baseline is missing) instead of announcing days-old members as new mail. One
        // real arrival in the wipe-to-reseed window going unannounced is the cost.
        if (prefs.getInt(VERSION_KEY, 1) < BASELINE_VERSION) {
            prefs.edit().clear().putInt(VERSION_KEY, BASELINE_VERSION).apply()
        }
        return prefs
    }

    /** Baseline key for one account folder. Account ids are UUIDs, so ':' is unambiguous. */
    private fun key(accountId: String, mailboxId: String) = "$accountId:$mailboxId"

    /** Key of a baseline's last-pass timestamp. Disjoint from [key] ("ts" is no UUID). */
    private fun tsKey(accountId: String, mailboxId: String) = "ts:" + key(accountId, mailboxId)

    /**
     * Move a pre-multi-folder baseline (bare accountId key, inbox-only) onto its
     * per-folder key, preserving notification continuity across the app update
     * (no duplicate and no missed inbox notifications). Call before any diff.
     */
    fun migrateLegacyBaseline(context: Context, accountId: String, inboxId: String) {
        val prefs = prefs(context)
        if (!prefs.contains(accountId) || prefs.contains(key(accountId, inboxId))) return
        prefs.edit()
            .putStringSet(key(accountId, inboxId), prefs.getStringSet(accountId, null).orEmpty())
            .remove(accountId)
            .apply()
    }

    /**
     * Reset a folder's baseline to [baselineIds] without notifying (first sight of a folder, or
     * when the user is opening the app anyway — the list itself shows what arrived).
     *
     * ⚠ [baselineIds] is deliberately NOT "the ids of the messages that were diffed". It REPLACES
     * the set, so it has to cover everything the caller read, including what the caller declined
     * to hydrate: an id that leaves this set is forgotten, and a forgotten id that comes back into
     * view announces as new mail — and, through `unarchiveThreadsOnReply`, moves mail out of the
     * Archive server-side. See `MailRepository.notifyRead`.
     */
    fun seed(context: Context, accountId: String, mailboxId: String, baselineIds: Collection<String>) {
        prefs(context).edit()
            .putStringSet(key(accountId, mailboxId), baselineIds.toSet())
            .putLong(tsKey(accountId, mailboxId), System.currentTimeMillis())
            .apply()
    }

    /** True once [seed] or [notifyDiff] has recorded a baseline for this folder. */
    fun hasBaseline(context: Context, accountId: String, mailboxId: String): Boolean =
        prefs(context).contains(key(accountId, mailboxId))

    /** Drop all of an account's baselines (sign-out), including the legacy bare key. */
    fun clear(context: Context, accountId: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().remove(accountId)
        prefs.all.keys
            .filter { it.startsWith("$accountId:") || it.startsWith("ts:$accountId:") }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    /** Drop one folder's baseline (folder deleted or no longer watched). */
    fun clear(context: Context, accountId: String, mailboxId: String) {
        prefs(context).edit()
            .remove(key(accountId, mailboxId))
            .remove(tsKey(accountId, mailboxId))
            .apply()
    }

    /**
     * Re-key baselines after an IMAP rename (ids are folder paths there) — the folder
     * itself AND its subfolders, whose paths changed too (both common delimiters are
     * matched; a false positive merely re-keys a baseline to an unused key, which the
     * next pass reseeds silently).
     */
    fun rename(context: Context, accountId: String, oldMailboxId: String, newMailboxId: String) {
        val prefs = prefs(context)
        val oldKey = key(accountId, oldMailboxId)
        val newKey = key(accountId, newMailboxId)
        val edit = prefs.edit()
        prefs.all.keys
            .filter {
                val bare = it.removePrefix("ts:")
                bare == oldKey || bare.startsWith("$oldKey/") || bare.startsWith("$oldKey.")
            }
            .forEach { k ->
                if (k.startsWith("ts:")) {
                    edit.putLong("ts:" + newKey + k.removePrefix("ts:").removePrefix(oldKey), prefs.getLong(k, 0L))
                } else {
                    prefs.getStringSet(k, null)?.let { edit.putStringSet(newKey + k.removePrefix(oldKey), it) }
                }
                edit.remove(k)
            }
        edit.apply()
    }

    /**
     * The subset of [emails] genuinely new to this folder: not in its baseline, and past
     * the age floor — never announce mail received more than
     * [app.sterna.core.data.mail.NOTIFY_HORIZON_MS] before the
     * folder's previous pass. The cache gains OLD rows without them ever having been new
     * mail — scrolling back pages weeks-old unread into the cache, expanding a thread
     * caches old members — and none of those are in the baseline, so each would otherwise
     * count as days-late "new" mail. Genuinely new mail carrying an old receivedAt (a
     * delayed relay) is skipped too — accepted trade-off. A missing timestamp or an
     * unparseable date disables the floor for that mail (never suppresses a real arrival).
     * Self-moves are dropped too (Codeberg #50 follow-up): a folder only counts as receiving
     * new mail when it arrives there by itself, server-side — a message the user moved into
     * a watched folder from THIS app (archiving an unread message into a watched Archive,
     * moving to a folder, an Undo's move-back) is no arrival, yet it enters the folder's
     * cache outside the baseline and would otherwise announce on the next pass. The
     * repository records each server-acked move; the filter here only shapes this diff —
     * the ids still enter the baseline via [notifyDiff]/[seed], so they never announce
     * later either.
     * Read-only: the baseline only advances in [notifyDiff]/[seed]. Read state is NOT
     * consulted (a reply already read on another device is still new) — [notifyDiff]
     * additionally drops seen mail, a notification-only concern.
     */
    fun newSince(context: Context, accountId: String, mailboxId: String, emails: List<Email>): List<Email> {
        val known = prefs(context).getStringSet(key(accountId, mailboxId), null).orEmpty()
        val lastPass = prefs(context).getLong(tsKey(accountId, mailboxId), 0L)
        // The floor is a pure function shared with the read that produces [emails]
        // ([app.sterna.core.data.mail.notifyFloor]): the row set handed here has to contain
        // everything this predicate can still let through, or old cached mail turns into
        // "new" mail — see `NotifyCandidates.kt`.
        val floor = notifyFloor(lastPass)
        val selfMoved = (context.applicationContext as Application).container.mailRepository.recentLocalMoves
        return emails.filter {
            it.id !in known && receivedAfter(it, floor) && EmailKey(accountId, it.id) !in selfMoved
        }
    }

    /**
     * Post notifications for a folder's messages not in its baseline, then advance it.
     * [folderName] is shown as notification sub-text; pass null for the inbox.
     *
     * [emails] is what may be announced; [baselineIds] is what the folder will remember. They are
     * not the same set and the second must cover the first — see [seed].
     *
     * [departedIds] are the ids the SERVER reported as having LEFT this folder during the refresh
     * that produced [emails] (Codeberg #134): the banners of the ones the server CONFIRMS are gone
     * are taken down, exactly as a message read on another device has its banner taken down. Only a
     * NAMED id is ever cancelled, so a pass that says nothing — IMAP, an offline pass, a failed one
     * — cancels nothing; the default is what a caller with no such signal passes. See
     * [Notifications.departuresToCancel] for what may and may not be fed here, and
     * `MailRepository.confirmDepartures` for why a delta's word alone is not enough.
     *
     * [previewSources] says where the opening line of each of [emails] could be READ from, for the
     * protocol that has to read it (IMAP; a JMAP row already carries its own — see
     * [FolderRefresh.previewSources]). Empty is a complete answer: the notifications are then the
     * sender and the subject, which is what every position but one shows anyway.
     *
     * ⭐ [previewBudget] is the six reads and four seconds of the WHOLE ACCOUNT PASS, and a
     * multi-folder caller MUST pass the same one to every folder it diffs ([FetchAndNotify.run]):
     * this function is called once per folder, so a budget of its own here would be four budgets
     * for an account watching three extra folders — 24 reads and up to 16 s of blocking IMAP work
     * under one mutex, on one radio wake-up. The default is a budget of its own, which is the right
     * answer for the single-folder callers and the wrong one for a fan-out.
     */
    suspend fun notifyDiff(
        context: Context,
        credentials: AccountCredentials,
        mailboxId: String,
        folderName: String?,
        emails: List<Email>,
        baselineIds: Collection<String>,
        departedIds: List<String> = emptyList(),
        previewSources: Map<String, PreviewSource> = emptyMap(),
        previewBudget: NotificationPreviews.PreviewBudget =
            NotificationPreviews.PreviewBudget(System::currentTimeMillis),
    ) {
        // One notification per conversation: the uncollapsed folder sync hands us every new
        // member of a thread, so a reply burst would otherwise fire one notification per
        // message. Collapse the new-mail set by thread (COALESCE(threadId, id) — an IMAP or
        // thread-less message is its own thread), keeping the newest member as the
        // representative; the whole burst still enters the baseline via [seed] below, so a
        // skipped member can never resurface as "new" on a later pass.
        val newMail = newSince(context, credentials.id, mailboxId, emails)
            .filter { !it.isSeen }
            .groupBy { it.threadId ?: it.id }
            .map { (_, members) -> members.maxBy { it.receivedAt.orEmpty() } }
        // Codeberg #19: a message we announced that has since been read — in the app or on
        // another device (a remote read arrives here as a re-synced $seen change) — should
        // have its notification cleared. Only touch ids that still have a live notification.
        val active = Notifications.activeChildIds(context, credentials.id)
        val readIds = emails
            .filter { it.isSeen && Notifications.isChildActive(active, credentials.id, it.id) }
            .map { it.id }
        // Codeberg #134: a message deleted from another client (Thunderbird, a webmail) is gone
        // from this folder, yet its banner stayed on screen and re-opened it on tap. The ids come
        // from what the SERVER said left the folder, never from a cache eviction, and a folder
        // that still holds one of them keeps its banner.
        //
        // Two steps, and the order is what makes the second one free: this narrows the delta's
        // report down to the ids that actually have a banner to take down, and only THOSE are put
        // to the server. A delta cannot tell a departure from a flag change (`Email/queryChanges`
        // reports a merely-starred row as `removed`, and the app's spare only knows about its OWN
        // mutations), so a star put on an unread message from another client arrives here as a
        // departure protected by nothing — and cancelling it hides mail the user never sees.
        val candidates = Notifications.departuresToCancel(active, credentials.id, departedIds, emails.map { it.id })
        // The confirmation, from the layer that talks to the server. It confirms NOTHING when it
        // cannot ask (offline, dead transport, unreadable answer) and does not throw: leaving a
        // banner up is survivable, and this pass has new mail to announce. That swallowed failure
        // is deliberate — the destroy path next door does the exact opposite, on purpose.
        val repository = (context.applicationContext as Application).container.mailRepository
        val departed = repository.confirmDepartures(credentials, mailboxId, candidates)
        if (newMail.isNotEmpty() || readIds.isNotEmpty() || departed.isNotEmpty()) {
            val (silent, content) = options(context)
            // Who announces this batch, decided BEFORE anything is posted (Codeberg #56): the
            // group summary if this account is left with enough notifications to get one, the
            // messages themselves otherwise — a single arrival gets no summary, so silencing it
            // "under" one would silence it outright.
            //
            // Hoisted into a `val` because the SAME set is what the group summary must decide on
            // at the bottom of this pass: recomputing it there, or re-reading the shade, would
            // read a system list the pass's own cancels have not reached yet (#134).
            val live = Notifications.liveChildIdsAfter(active, credentials.id, readIds + departed, newMail.map { it.id })
            val summarised = Notifications.summaryShownFor(live.size)
            // ⛔ The openings are read HERE, in one pre-pass, BEFORE the first notification is
            // posted — never inside the loop below. A network round trip between two posts would
            // put an exception in the middle of a half-announced batch, and the [seed] at the
            // bottom of this function would be skipped: the folder's baseline would not move, the
            // next pass would re-announce every message in it, and a re-announcement MOVES MAIL
            // server-side through `unarchiveThreadsOnReply`. [NotificationPreviews] therefore
            // throws nothing, and posts nothing it has not already gathered — a notification
            // amended a second later flickers, and late mail is not news.
            NotificationPreviews.postGatheredFirst(
                newMail,
                keyOf = { it.id },
                gather = {
                    NotificationPreviews.gather(
                        // Each argument on its own line, and each one pinned: `content` is what
                        // makes the whole pre-pass conditional on the reader having asked for a
                        // body, and `newMail` is what makes it read the mail being announced
                        // rather than the first six rows of a fifty-message page. Both would
                        // compile if they were replaced, and neither would look wrong on screen.
                        content,
                        newMail.map { it.id },
                        previewSources,
                        // The account pass's own budget, so this folder does not start afresh.
                        previewBudget,
                        numbering = { repository.recordedNumbering(credentials.id, mailboxId) },
                        fetch = { source, budgetMs ->
                            repository.notificationPreview(credentials, mailboxId, source, budgetMs)
                        },
                    )
                },
                // [mailboxId] is the folder this diff pass is for, i.e. where the mail arrived —
                // so the tap can put the list there (issue #91). Passed for the inbox too, where
                // [folderName] is deliberately null: the sub-text is about marking non-inbox
                // mail, the id is about where Back must land.
                //
                // The gathered opening travels ON the message, so every path that posts a mail
                // notification — this one and the snooze wake-up — reads it from the same field,
                // and the privacy setting keeps deciding on its own whether it is ever shown.
                // `?: email.preview` is what leaves JMAP's own preview alone: nothing was gathered
                // there, and the row already carries one.
                post = { email, preview ->
                    Notifications.notifyNewMail(
                        context,
                        email.copy(preview = preview ?: email.preview),
                        credentials.id,
                        silent,
                        folderName,
                        mailboxId,
                        // The privacy setting this pass read, and the same value the gathering was
                        // gated on: on its own line because it is what decides what a lock screen
                        // shows, and a positional argument is invisible to anything but a whole-line
                        // pin (the class of Codeberg #84).
                        content,
                        summarised,
                    )
                },
            )
            readIds.forEach { Notifications.cancelChild(context, credentials.id, it) }
            departed.forEach { Notifications.cancelChild(context, credentials.id, it) }
            // Rebuilt from ALL active children so successive per-folder passes accumulate
            // instead of the last folder overwriting the whole account's summary. It re-posts,
            // so it would ring: it may only do so when this pass actually brought new mail —
            // a message read on another device, or deleted from one, refreshes the group in
            // silence.
            Notifications.updateGroupSummary(
                context,
                credentials.id,
                credentials.username,
                silent = silent || newMail.isEmpty(),
                // The set computed BEFORE anything was posted or cancelled, i.e. what this
                // account is left with now that the pass is done. Named, and never recomputed
                // here: `active` above is the pre-pass read, and that is exactly the one wanted.
                expectedChildIds = live,
                // What this pass took down, and only that. Everything else in the group stays
                // in `children` and is put back if the summary falls: seconds go by between the
                // read of the shade at the top of this function and here (a server round trip
                // for the departures, then the preview pre-pass), and a snooze wake-up posting
                // into this same group in that window would otherwise be cascaded away for good.
                cancelledChildIds = Notifications.childIdsOf(credentials.id, readIds + departed),
            )
        }
        seed(context, credentials.id, mailboxId, baselineIds)
    }

    /** Whether [email] was received at or after [floorMs] (lenient: unknown dates pass). Shared
     *  with the read that produces the candidates — see [app.sterna.core.data.mail.announceableAt]. */
    private fun receivedAfter(email: Email, floorMs: Long): Boolean = announceableAt(email.receivedAt, floorMs)

    /**
     * The user settings every posted mail notification must obey: [silent] for the
     * quiet-hours window, [content] for how much the notification may reveal.
     */
    data class Options(val silent: Boolean, val content: NotificationContent)

    /**
     * Reads both notification settings for the moment the notification is posted. Shared
     * with the snooze wake-up ([app.sterna.snooze.SnoozeWorker], Codeberg #84) so the two
     * paths cannot drift apart — the wake-up used to post with the function defaults and
     * so ignored both quiet hours and the content setting.
     */
    suspend fun options(context: Context): Options {
        val settings = (context.applicationContext as Application).container.settingsRepository
        return Options(quietHoursActive(context), settings.notificationContent.first())
    }

    /** Whether new mail should be posted silently right now (quiet-hours window). */
    private suspend fun quietHoursActive(context: Context): Boolean {
        val settings = (context.applicationContext as Application).container.settingsRepository
        if (!settings.quietHoursEnabled.first()) return false
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return SettingsRepository.isWithinQuietHours(
            minutes,
            settings.quietHoursStart.first(),
            settings.quietHoursEnd.first(),
        )
    }
}
