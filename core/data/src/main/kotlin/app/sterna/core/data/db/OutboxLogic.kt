package app.sterna.core.data.db

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Pure outbox decisions, kept out of the worker/UI so they can be unit-tested on the JVM:
 * when an item is due to send, what happens after a failed attempt, and how the badge counts.
 */
object OutboxLogic {
    /** Auto-retry attempts before an item is parked as FAILED for manual handling. */
    const val MAX_ATTEMPTS = 5

    /**
     * A HELD item is due once its undo window has passed; QUEUED is always due. A scheduled send is
     * NOT held here — it waits in its own table and only enters the outbox when ScheduledSendWorker
     * fires, which queues it like any other send (holdMs = 0). So the only hold this deadline ever
     * carries is the undo window, a few seconds at most — which is what bounds the badge grace in
     * [activeCount]: no path can write a far-future notBeforeMillis on an outbox row.
     */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED, OutboxState.SENDING -> true
        // Parked (manual only, #70), saved as a draft on the phone (#95) or open in the composer
        // (#70): the worker must never send any of the three on its own. KEPT_AS_DRAFT is the one
        // that would be a data loss rather than a nuisance — the user tapped "save as a draft", and
        // sending is precisely what they did not ask for.
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING -> false
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * The state a reopened item returns to once its edit ends — the composer was closed untouched,
     * or a process death is being recovered at startup. An item whose auto-retry was already
     * exhausted (parked FAILED, its [attemptCount] at the cap) must stay parked for manual handling,
     * NOT silently rejoin the queue and send itself: reopening a FAILED item to look at it, then
     * closing it, must not restart delivery — only Retry/Send may (#70 regression from EDITING).
     * [attemptCount] is the faithful proxy for "was FAILED": the cap is reached only on the
     * FAILED-parking path (see [shouldRetry]). Everything else goes back to [OutboxState.QUEUED].
     */
    fun stateAfterEdit(attemptCount: Int): OutboxState =
        if (attemptCount >= MAX_ATTEMPTS) OutboxState.FAILED else OutboxState.QUEUED

    /**
     * Whether an outbox item can be reopened in the composer. Three things must hold.
     *
     * ⛔ **It must carry no entity the composer cannot rebuild** —
     * [carriesUnreplayablePrebuiltEntity]. Careful: "carries an entity" is NOT the rule, and
     * writing it that way is a regression. Reopening a row throws the entity file away and, on
     * Send, enqueues a brand-new row built from the composer's fields alone. Whether that is
     * harmless depends entirely on whether those fields can produce the entity again:
     *
     *  - a SIGNED row can. The body is in the row, the composer signs it afresh at send time, and
     *    the new entity is as good as the old. It has had Edit since #70 and keeps it.
     *  - an RFC 8098 read receipt cannot. Its row holds the human-readable half; the
     *    `message/disposition-notification` part — the fields that make it a receipt at all —
     *    exists nowhere but in that file, and nothing in the composer knows how to write it. Edit
     *    would silently turn a receipt into an odd little mail.
     *
     * ⚠ The argument has NO default. It used to be the PGP mode that decided this, which answered
     * "editable" for every entity arriving without one, and a default would let the next call site
     * inherit that same wrong answer in silence.
     *
     * It must not be ENCRYPTED either. Almost the same rows, but not by construction — the mode is
     * what says the plaintext was blanked at rest ([OutboxEntity.pgpMode]), so a row that somehow
     * lost its file would still open empty and send an empty message.
     *
     * And it must be genuinely waiting — QUEUED, HELD or FAILED. A [OutboxState.SENDING] row has a
     * send in flight: reopening it lets the worker's updateOutboxState clobber the EDITING flag,
     * leaving the edit orphaned or the message sent twice. An [OutboxState.EDITING] row is already
     * open in a composer. Neither may be taken. An action that cannot do what its label promises is
     * not offered (#70); Retry and Delete stay — both work on the row as it stands.
     */
    fun canEdit(pgpMode: String?, state: OutboxState, carriesUnreplayablePrebuiltEntity: Boolean): Boolean =
        !carriesUnreplayablePrebuiltEntity && !pgpMode.equals("ENCRYPT", ignoreCase = true) &&
            when (state) {
                // KEPT_AS_DRAFT reopens like the rest (#95): the row is the copy of that message
                // the Outbox screen is showing, and refusing Edit on it would leave the user
                // looking at a line they cannot act on.
                OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT -> true
                OutboxState.SENDING, OutboxState.EDITING -> false
            }

    /**
     * Whether a row's pre-built entity is one the composer could NOT produce again — the single
     * expression behind [canEdit]'s first rule, so the screen and the state flip cannot disagree
     * about it and neither has to spell it out.
     *
     * The test is "an entity with no PGP mode". A mode is exactly what says the composer built this
     * entity and can build it again (`ComposeViewModel` writes one for every send that is not
     * `PgpMode.OFF`, and stores the mode beside it); its absence is what says the entity came from
     * somewhere else — today, RFC 8098's receipt, which is assembled once from a message that will
     * not be in front of the reader again.
     *
     * @param pgpMode [OutboxEntity.pgpMode]. ⚠ `PgpMode.OFF` is never stored — `enqueueSend` writes
     *   null for it — so null here genuinely means "no PGP was involved".
     * @param entityPath [OutboxEntity.pgpEntityPath]: the row's entity file, or null.
     */
    fun isUnreplayablePrebuiltEntity(pgpMode: String?, entityPath: String?): Boolean =
        entityPath != null && pgpMode == null

    /**
     * How long a message on its way stays off the badge past the instant it was allowed to go
     * (#82). Online a send is delivered in a second or two, so counting it the moment the undo
     * window elapsed made the badge flash on every message that went out perfectly well — the eye
     * drawn to nothing. This is a later threshold on the same deadline, not a new state: the clock
     * still decides on its own, so a message that is NOT going out still reaches the badge (#70).
     */
    const val BADGE_GRACE_MILLIS = 30_000L

    /**
     * Items shown on the badge. FAILED counts at once: it is over, it needs the user, and no delay
     * would make it any less true. Anything still on its way (HELD, QUEUED, SENDING) counts only
     * [BADGE_GRACE_MILLIS] past its [OutboxBadgeItem.notBeforeMillis] — the instant from which the
     * row was allowed to leave: the end of the undo window, the moment it was queued when there is
     * no window (holdMs = 0), or the moment Retry was pressed. A send that works is gone well
     * before that and never shows (#82); a send that is stuck stays put and shows up.
     *
     * The clock, not the state, still decides silence: offline the delivery worker is gated on
     * connectivity and never runs, so nothing takes the row out of HELD, and a state-based count
     * left the message waiting in the Outbox with no dot and no counter (#70). The grace only moves
     * the threshold — it never makes a waiting message invisible, it delays it by half a minute.
     *
     * The grace hangs off that one instant and is never restarted, which is what makes it bounded.
     * A row put back to QUEUED after a failed attempt (see [shouldRetry]) keeps the deadline it was
     * inserted with, so a transient hiccup that heals inside the grace stays silent, and one that
     * drags on counts as soon as the grace has passed and keeps counting through every further
     * retry — a message that is still not gone half a minute later is exactly what the badge is for.
     */
    fun activeCount(items: List<OutboxBadgeItem>, now: Long): Int = items.count { countsAt(it, now) }

    /** Whether one row belongs on the badge at [now]; see [activeCount] for the reasoning. */
    private fun countsAt(item: OutboxBadgeItem, now: Long): Boolean = when (item.state) {
        // An item open in the composer (#70) is being handled right now, not waiting to send:
        // keep it off the badge, exactly as the queue keeps it off the send worker.
        OutboxState.EDITING -> false
        // Both count AT ONCE, and for the same reason: neither is on its way anywhere, so no delay
        // would make the silence any truer. KEPT_AS_DRAFT is not an alarm — it is the only thing on
        // screen saying where that message went (#95) — which is why it counts on the badge while
        // it deliberately stays off the failure banner ([needsFailureBanner]).
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING ->
            now >= item.notBeforeMillis + BADGE_GRACE_MILLIS
    }

    /**
     * Whether a row is one of those the Outbox screen lists — everything except a row currently
     * open in the composer. This is the single predicate behind both `MailRepository.outboxFlow`
     * (what the screen shows) and [queuedCount] (what the menu entry announces), so the two can
     * never drift apart: the number in the menu is by construction the number of lines the screen
     * will put in front of the user.
     */
    fun isWaitingInOutbox(state: OutboxState): Boolean = state != OutboxState.EDITING

    /**
     * Messages sitting in the Outbox right now, with NO grace at all — deliberately not the same
     * count as [activeCount] (#70).
     *
     * This one feeds the count on the Outbox entry INSIDE the overflow menu, which does not exist
     * on screen until the user opens that menu. Opening it is going to look on purpose, so there is
     * nothing to hold back: a message queued a second ago is already in the Outbox and must be
     * announced as such. The dot on the toolbar button is the opposite case — permanently in the
     * field of view, hence [BADGE_GRACE_MILLIS] so a send that goes out fine never draws the eye to
     * nothing (#82). A count locked inside a closed menu cannot draw an eye, so the grace would buy
     * nothing there and only deny #70 its answer.
     *
     * The visible consequence is intended, do NOT "harmonise" it away: during the grace the dot is
     * absent while the menu shows "(1)". They are not two readings of one thing — the dot says "this
     * has been waiting long enough that you should know", the count says "here is what is in the
     * Outbox".
     *
     * EDITING is excluded for the same reason as in [countsAt]: a row open in the composer is being
     * handled right now, it is not waiting to leave, and the Outbox screen does not list it either.
     */
    fun queuedCount(items: List<OutboxBadgeItem>): Int = items.count { isWaitingInOutbox(it.state) }

    /**
     * When the badge must next be recomputed on its own: the earliest counting threshold still
     * ahead. FAILED already counts and EDITING never does, so neither has a wake-up to schedule.
     */
    fun nextBadgeChange(items: List<OutboxBadgeItem>, now: Long): Long? = items
        .filter { countsOnlyAfterTheGrace(it.state) }
        .map { it.notBeforeMillis + BADGE_GRACE_MILLIS }
        .filter { it > now }
        .minOrNull()

    /**
     * Whether a row's place on the badge still depends on the clock — the only rows with a wake-up
     * to schedule. Written as an exhaustive `when` and not as a list of `!=` so that a new state
     * has to answer the question out loud: a value quietly falling on the wrong side here books a
     * pointless wake-up (harmless) or, the other way round, never books the one it needed.
     */
    private fun countsOnlyAfterTheGrace(state: OutboxState): Boolean = when (state) {
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING -> true
        // These three are already settled: FAILED and KEPT_AS_DRAFT count from the first emission,
        // EDITING never counts at all. See [countsAt].
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING -> false
    }

    /**
     * The badge count over time. Room re-emits on every outbox change, and each emission schedules
     * exactly one wake-up: the earliest counting threshold still ahead ([nextBadgeChange]). So the
     * badge appears on its own once a message has been waiting past the grace, and disappears the
     * moment the row leaves — with no polling, and nothing to reset when the row goes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun badgeCount(
        items: Flow<List<OutboxBadgeItem>>,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Int> = items
        .flatMapLatest { rows ->
            flow {
                while (true) {
                    val instant = now()
                    emit(activeCount(rows, instant))
                    val next = nextBadgeChange(rows, instant) ?: break
                    delay(next - instant)
                }
            }
        }
        .distinctUntilChanged()

    /**
     * Whether one row is what the Inbox's "Some messages didn't send. Tap to review." banner is
     * about — a genuine send failure, and nothing else.
     *
     * ⛔ [OutboxState.KEPT_AS_DRAFT] is NOT one (#95), and this is the whole reason it is a state of
     * its own rather than a reuse of [OutboxState.FAILED]. Nothing failed: the user asked for a
     * draft and got one, on the phone. The banner sits across the Inbox, tints the outbox dot red
     * and is cleared by nothing — there is no deferred upload whose success could ever take it down
     * again — so a message saved exactly as asked would leave a permanent alarm behind it.
     *
     * Exhaustive on purpose: a sixth state has to answer this out loud rather than inherit an
     * answer from an `else`.
     */
    fun needsFailureBanner(state: OutboxState): Boolean = when (state) {
        OutboxState.FAILED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        -> false
    }

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { needsFailureBanner(it) }
}
