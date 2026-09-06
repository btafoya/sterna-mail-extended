package app.sterna.core.data.db

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.jmap.JmapException
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
     * The `lastError` a row carries when its edit was INTERRUPTED. A sentinel, translated at
     * display time by `outboxFailureReason` — never a localized sentence, which would stay in the
     * language of the day it was written. The literal never changes.
     */
    const val EDIT_INTERRUPTED = "edit-interrupted"

    /**
     * The `lastError` a row carries when its delivery could not be ARMED (`armOrNote`): the row is
     * inserted and stays QUEUED or HELD, since arming is not queueing. A sentinel like
     * [EDIT_INTERRUPTED]; the literal never changes.
     */
    const val NOT_ARMED = "not-armed"

    /**
     * The historic opening of what 1.5.2/1.5.3 wrote into `outbox.lastError` when arming failed.
     * Frozen, and here to be RECOGNISED, never written: no migration touches that column, so a
     * row queued on a phone that ran either version still carries the sentence. `unarmedNote`
     * builds its own from this constant so the two cannot drift.
     */
    const val NOT_ARMED_LEGACY_PREFIX = "Queued, but delivery could not be armed:"

    /**
     * A HELD item is due once its undo window has passed; QUEUED is always due. A scheduled send
     * waits in its own table, so the only hold this deadline carries is the undo window — which
     * bounds the badge grace in [activeCount].
     *
     * [OutboxState.SENDING] is NOT due: that row is a delivery already begun, and handing it to
     * the worker again delivers the same message twice. INTERRUPTED is refused for the same reason.
     */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED -> true
        // Already begun (see above), parked for manual handling (#70), saved as a draft on the
        // phone (#95) or open in the composer. KEPT_AS_DRAFT would be a data loss, not a nuisance.
        OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
    }

    /**
     * The state the send worker must PARK the row it has just read in, or null when the delivery
     * may go on. Finding [OutboxState.SENDING] proves an interruption: the worker reads the row
     * first and writes SENDING immediately before handing the message over, so no run reads its
     * own SENDING. The answer is not "send it again": re-delivery is irreversible, parking loses
     * nothing.
     */
    fun parkOnPickup(state: OutboxState): OutboxState? = when (state) {
        OutboxState.SENDING -> OutboxState.INTERRUPTED
        // Nothing begun, nothing to park. FAILED, KEPT_AS_DRAFT, EDITING and INTERRUPTED are
        // already parked, and re-parking would overwrite what they say.
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.FAILED,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING, OutboxState.INTERRUPTED,
        -> null
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * The state an outbox row goes to after a failed delivery attempt. A [JmapException] carrying
     * `permanent` is a delivery the SERVER refused, and retrying it does damage: the message is
     * already filed in Sent, and on a partial refusal every retry re-delivers to the address that
     * was valid. Everything else keeps [shouldRetry].
     */
    fun stateAfterFailure(attemptCount: Int, failure: Throwable): OutboxState = when {
        failure is JmapException && failure.permanent -> OutboxState.FAILED
        shouldRetry(attemptCount) -> OutboxState.QUEUED
        else -> OutboxState.FAILED
    }

    /**
     * How many attempts the row is written back with — on a PERMANENT refusal the CAP, not
     * `previousAttempts + 1`. Not bookkeeping: with a count of 1, [canEdit] offers Edit on the
     * FAILED row and closing the composer untouched re-arms it through [stateAfterEdit] (#70), and
     * `OutboxDao.revertEditingExhaustedToFailed` would relabel it "editing was interrupted". It
     * costs nothing: `retryOutbox` zeroes the count before re-arming.
     */
    fun attemptsAfterFailure(previousAttempts: Int, failure: Throwable): Int =
        if (failure is JmapException && failure.permanent) maxOf(MAX_ATTEMPTS, previousAttempts + 1)
        else previousAttempts + 1

    /**
     * The state a reopened item returns to once its edit ends. An item whose auto-retry was
     * exhausted ([attemptCount] at the cap) stays parked: reopening a FAILED item to look at it
     * must not restart delivery (#70). [attemptCount] is the faithful proxy for "was FAILED"
     * because the cap is reached only on the FAILED-parking path.
     */
    fun stateAfterEdit(attemptCount: Int): OutboxState =
        if (attemptCount >= MAX_ATTEMPTS) OutboxState.FAILED else OutboxState.QUEUED

    /**
     * Whether an outbox item can be reopened in the composer. Three things must hold.
     *
     * No entity the composer cannot rebuild ([carriesUnreplayablePrebuiltEntity]) — a SIGNED row
     * is signed afresh, while an RFC 8098 receipt's `message/disposition-notification` part exists
     * nowhere but in that file. Not ENCRYPTED: the mode says the plaintext was blanked at rest.
     * And genuinely waiting — QUEUED, HELD or FAILED: reopening parks the row in EDITING, and
     * closing the composer untouched runs [stateAfterEdit] on an attemptCount of 0, which answers
     * QUEUED, so merely LOOKING at an INTERRUPTED or KEPT_AS_DRAFT message would re-send it (#95).
     */
    fun canEdit(pgpMode: String?, state: OutboxState, carriesUnreplayablePrebuiltEntity: Boolean): Boolean =
        !carriesUnreplayablePrebuiltEntity && !pgpMode.equals("ENCRYPT", ignoreCase = true) &&
            when (state) {
                OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED -> true
                OutboxState.SENDING, OutboxState.EDITING, OutboxState.INTERRUPTED, OutboxState.KEPT_AS_DRAFT -> false
            }

    /**
     * What goes in [OutboxEntity.pgpMode] for a message being queued — a string, deliberately not
     * `mode.name`: the column says how the body must be treated AT REST, and both encrypting modes
     * are the same thing there. Storing [PgpMode.ENCRYPT_UNSIGNED] as "ENCRYPT" keeps two holes
     * shut: [canEdit] compares this column as text, and a rolled back APK would fail
     * `PgpMode.valueOf` and drop the composer to OFF. If Edit ever becomes possible on an
     * encrypted row, this shortcut must fall first.
     */
    fun outboxPgpModeName(mode: PgpMode?): String? = when (mode) {
        null, PgpMode.OFF -> null
        PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED -> "ENCRYPT"
        PgpMode.SIGN -> "SIGN"
    }

    /**
     * Whether a row's pre-built entity is one the composer could not produce again — the single
     * expression behind [canEdit]'s first rule. The test is "an entity with no PGP mode".
     *
     * @param pgpMode [OutboxEntity.pgpMode]. `PgpMode.OFF` is never stored, so null means "no PGP".
     * @param entityPath [OutboxEntity.pgpEntityPath]: the row's entity file, or null.
     */
    fun isUnreplayablePrebuiltEntity(pgpMode: String?, entityPath: String?): Boolean =
        entityPath != null && pgpMode == null

    /**
     * How long a message on its way stays off the badge past the instant it was allowed to go
     * (#82). A later threshold on the same deadline, not a new state: a message that is NOT going
     * out still reaches the badge (#70).
     */
    const val BADGE_GRACE_MILLIS = 30_000L

    /**
     * Items shown on the badge. FAILED counts at once; anything still on its way counts only
     * [BADGE_GRACE_MILLIS] past its [OutboxBadgeItem.notBeforeMillis], the instant from which the
     * row was allowed to leave. The clock, not the state, decides — offline the delivery worker
     * never runs, so a state-based count left the message waiting with no dot (#70) — and the grace
     * hangs off that one instant and is never restarted, which is what bounds it.
     */
    fun activeCount(items: List<OutboxBadgeItem>, now: Long): Int = items.count { countsAt(it, now) }

    /** Whether one row belongs on the badge at [now]; see [activeCount] for the reasoning. */
    private fun countsAt(item: OutboxBadgeItem, now: Long): Boolean = when (item.state) {
        // An item open in the composer (#70) is being handled right now, not waiting to send.
        OutboxState.EDITING -> false
        // All three count at once: none is on its way anywhere. KEPT_AS_DRAFT is not an alarm — it
        // is the only thing on screen saying where that message went (#95), which is why it counts
        // here while staying off the failure banner.
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING ->
            now >= item.notBeforeMillis + BADGE_GRACE_MILLIS
    }

    /**
     * Whether a row is one the Outbox screen lists — everything except a row open in the composer.
     * The single predicate behind both `MailRepository.outboxFlow` and [queuedCount].
     */
    fun isWaitingInOutbox(state: OutboxState): Boolean = state != OutboxState.EDITING

    /**
     * Messages sitting in the Outbox right now, with NO grace at all — deliberately not
     * [activeCount] (#70). This feeds the count inside the overflow menu, which does not exist on
     * screen until the user opens it, so there is nothing to hold back; the toolbar dot is
     * permanently in the field of view, hence [BADGE_GRACE_MILLIS] (#82).
     *
     * The visible consequence is intended, do not "harmonise" it away: during the grace the dot
     * is absent while the menu shows "(1)".
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
     * to schedule. Exhaustive rather than a list of `!=` so a new state has to answer out loud.
     */
    private fun countsOnlyAfterTheGrace(state: OutboxState): Boolean = when (state) {
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING -> true
        // These four are already settled: FAILED, KEPT_AS_DRAFT and INTERRUPTED count from the
        // first emission, EDITING never counts. See [countsAt].
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
    }

    /**
     * The badge count over time. Room re-emits on every outbox change, and each emission schedules
     * exactly one wake-up ([nextBadgeChange]) — no polling, nothing to reset.
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
     * Whether one row is what the Inbox's "Some messages didn't send" banner is about — a genuine
     * send failure, and nothing else.
     *
     * [OutboxState.KEPT_AS_DRAFT] is not one (#95), which is why it is a state of its own:
     * nothing failed, and the banner is cleared by nothing, so a message saved exactly as asked
     * would leave a permanent alarm behind it. Nor is [OutboxState.INTERRUPTED] — that row may
     * well have reached the server, and "didn't send" over a message that did is an assertion
     * nothing would take back.
     */
    fun needsFailureBanner(state: OutboxState): Boolean = when (state) {
        OutboxState.FAILED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING, OutboxState.INTERRUPTED,
        -> false
    }

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { needsFailureBanner(it) }
}
