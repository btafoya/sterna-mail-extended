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
     * The `lastError` a row carries when its edit was INTERRUPTED: the composer closed over a lost,
     * empty body and parked the row rather than queue it (`MailRepository.parkInterruptedOutboxEdit`).
     *
     * A SENTINEL, translated by the Outbox screen at display time (`outboxFailureReason`) — never a
     * localized sentence stored in the row, which would stay in the language of the day it was
     * written. It is stored in every user's database from the first version that ships it, so it
     * never changes: `EditInterruptedRowTest` pins the literal.
     */
    const val EDIT_INTERRUPTED = "edit-interrupted"

    /**
     * A HELD item is due once its undo window has passed; QUEUED is always due. A scheduled send is
     * NOT held here — it waits in its own table and only enters the outbox when ScheduledSendWorker
     * fires, which queues it like any other send (holdMs = 0). So the only hold this deadline ever
     * carries is the undo window, a few seconds at most — which is what bounds the badge grace in
     * [activeCount]: no path can write a far-future notBeforeMillis on an outbox row.
     *
     * ⛔ [OutboxState.SENDING] is NOT due, and that is the SECOND gate of the interrupted-send fix,
     * independent of [parkOnPickup]: a row reading SENDING is a delivery already begun. Handing it
     * to the worker again is the duplicate measured on the bench — the same message delivered
     * twice, under two Message-Ids, with an empty outbox afterwards and nothing saying so.
     * [OutboxState.INTERRUPTED] is refused out loud for the same reason: parking a row is worth
     * nothing if the next pass hands it straight back. Getting an interrupted message out again is
     * Retry's job, which the user presses knowingly.
     */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED -> true
        // Already begun (see above), parked for manual handling (#70), saved as a draft on the
        // phone (#95) or open in the composer (#70): the worker must never send any of these on
        // its own. KEPT_AS_DRAFT is the one that would be a data loss rather than a nuisance — the
        // user tapped "save as a draft", and sending is precisely what they did not ask for.
        OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
    }

    /**
     * The state the send worker must PARK the row it has just read in, or null when there is
     * nothing to park and the delivery may go on as usual. Called on the row as it was read, before
     * anything else is decided about it.
     *
     * Why finding [OutboxState.SENDING] proves an interruption: the worker READS the row first and
     * writes SENDING only afterwards, immediately before handing the message to the server. So no
     * run ever reads its own SENDING. Reading one means an earlier run wrote it and never came
     * back — the process was killed, the work was cancelled, the phone rebooted mid-delivery — and
     * that run may have got as far as the server. The row is the only witness left, and it cannot
     * say which side of the handover it died on.
     *
     * ⛔ Which is exactly why the answer is not "send it again". Re-delivery is irreversible for
     * the recipient (measured: the same message twice, 46 s apart, two Message-Ids); parking loses
     * nothing at all — the row stays in the Outbox, says it was interrupted, and Retry sends it by
     * hand when the user decides to.
     *
     * Exhaustive with NO `else`: a seventh state has to answer this out loud. Inheriting "nothing
     * to park" from an `else` is how a state that needs parking would get delivered twice in
     * silence.
     */
    fun parkOnPickup(state: OutboxState): OutboxState? = when (state) {
        OutboxState.SENDING -> OutboxState.INTERRUPTED
        // Nothing begun, nothing to park. HELD/QUEUED have not been handed to anyone yet; FAILED,
        // KEPT_AS_DRAFT, EDITING and INTERRUPTED are already parked, and re-parking them would
        // overwrite what they say (an exhausted retry, a save the user asked for, an open composer).
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.FAILED,
        OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING, OutboxState.INTERRUPTED,
        -> null
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * The state an outbox row goes to after a failed delivery attempt — the whole decision the send
     * worker takes in its `catch`, in the one place a JVM test can execute it.
     *
     * Two answers, and the FIRST one is not about counting. A [JmapException] carrying
     * `permanent` is a delivery the SERVER refused: it read the recipient, said no, and nothing
     * about that verdict will be different in thirty seconds. ⛔ Retrying it is not merely useless,
     * it does damage that the original defect did not:
     *
     *  - the message has already been handed over and filed in Sent, so five auto-retries leave
     *    FIVE copies there for the user to find;
     *  - and on a PARTIAL refusal — one address that exists, one that does not — every retry
     *    re-delivers the message to the recipient who is perfectly valid. She receives it five
     *    times, and nothing can recall any of them.
     *
     * So a permanent failure is parked as [OutboxState.FAILED] on the spot, whatever the attempt
     * count: the row stays in the outbox with the server's own words on it, the badge counts it,
     * the banner names it, and Retry is there for the user who has fixed the address.
     *
     * ⛔ Everything else keeps the behaviour it always had, through [shouldRetry] and nothing else.
     * A network that dropped, a server that hiccupped, an HTTP 500: those say NOTHING about whether
     * the message left, and the retries are exactly what gets a queued send out when the link comes
     * back. Widening "permanent" to cover them would park messages that only needed a second try.
     */
    fun stateAfterFailure(attemptCount: Int, failure: Throwable): OutboxState = when {
        failure is JmapException && failure.permanent -> OutboxState.FAILED
        shouldRetry(attemptCount) -> OutboxState.QUEUED
        else -> OutboxState.FAILED
    }

    /**
     * How many attempts the row is written back with after a failed one — and on a PERMANENT
     * refusal that number is the CAP, not `previousAttempts + 1`.
     *
     * ⛔ This is not bookkeeping, it is the guard [stateAfterFailure] would otherwise only appear
     * to give. Parking a refused row as [OutboxState.FAILED] with an attemptCount of 1 leaves two
     * doors wide open, both of which put the message back on the wire with nobody asking:
     *
     *  1. **The reopen door (#70).** [canEdit] offers Edit on a FAILED row — which is exactly what
     *     the user does, because the whole point is that an address needs fixing. Closing the
     *     composer untouched runs [stateAfterEdit] on that count, `1 >= 5` is false, and the row
     *     goes back to [OutboxState.QUEUED] and is re-armed on the spot. Every "open it, look at
     *     it, come back out" is one more delivery: another copy in Sent, and on a partial refusal
     *     another copy to the recipient whose address was right all along.
     *  2. **The startup door.** A row left in EDITING by a process death is swept by
     *     `MailRepository.parkInterruptedOutboxEdits`: `OutboxDao.revertEditingExhaustedToFailed`
     *     claims rows at `attemptCount >=` [MAX_ATTEMPTS] first, and the rest are parked under the
     *     interrupted-edit reason. (They used to fall through to QUEUED, so a refused row left on
     *     its own at the next cold start.) The count no longer decides whether it leaves — nothing
     *     does without a gesture — but it still decides which reason the row keeps: below the cap,
     *     a refused row would be relabelled "editing was interrupted" and the server's reply lost.
     *
     * [stateAfterEdit] states the invariant those two rely on: the count is "the faithful proxy for
     * was-FAILED, because the cap is reached only on the FAILED-parking path". [stateAfterFailure]
     * parks WITHOUT reaching the cap, so this function is what keeps that sentence true rather than
     * leaving it quietly false. The same ruse is named in `MailRepository.parkOutboxEdit` — forcing
     * the count to the cap is what was needed "while the parked state was FAILED", and here it is.
     *
     * ⭐ It costs the user nothing: `MailRepository.retryOutbox` sets `attemptCount = 0` before
     * re-arming, so Retry on a corrected address works exactly as before. What is spent is the
     * AUTOMATIC reserve, and a refusal has no automatic attempt left to spend by definition.
     *
     * ⛔ Everything else is untouched: an ordinary failure counts one more attempt, no more and no
     * less, or a dropped network would park sends that only needed a second try.
     */
    fun attemptsAfterFailure(previousAttempts: Int, failure: Throwable): Int =
        if (failure is JmapException && failure.permanent) maxOf(MAX_ATTEMPTS, previousAttempts + 1)
        else previousAttempts + 1

    /**
     * The state a reopened item returns to once its edit ends — the composer was closed untouched,
     * or a process death is being recovered at startup. An item whose auto-retry was already
     * exhausted (parked FAILED, its [attemptCount] at the cap) must stay parked for manual handling,
     * NOT silently rejoin the queue and send itself: reopening a FAILED item to look at it, then
     * closing it, must not restart delivery — only Retry/Send may (#70 regression from EDITING).
     * [attemptCount] is the faithful proxy for "was FAILED": the cap is reached only on the
     * FAILED-parking path (see [shouldRetry]). Everything else goes back to [OutboxState.QUEUED].
     *
     * [OutboxState.INTERRUPTED] and [OutboxState.KEPT_AS_DRAFT] are deliberately not cases here and
     * need none: [canEdit] refuses both, so such a row never enters [OutboxState.EDITING] and never
     * comes back out of one.
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
     *
     * ⛔ And NOT an [OutboxState.INTERRUPTED] row either — refusing Edit there is load-bearing, not
     * tidiness. Reopening a row parks it in EDITING, and closing the composer untouched runs
     * [stateAfterEdit] on an attemptCount of 0, which answers QUEUED: merely LOOKING at an
     * interrupted message would put it back on the wire on its own, i.e. the duplicate this state
     * exists to prevent, through the #70 door. Retry and Delete stay, and Retry is the one deliberate
     * gesture that sends it again.
     *
     * ⛔ And NOT a [OutboxState.KEPT_AS_DRAFT] row either — the same door, and a worse fall. Reopening
     * parks the row in EDITING, and closing the composer untouched runs [stateAfterEdit] on an
     * attemptCount of 0, which answers QUEUED: merely LOOKING at a message kept as a draft would
     * send it on its own, i.e. precisely the send the user refused (#95 × #70), the data loss this
     * state exists to prevent. This is NOT a line the user cannot act on: the kept draft opens from
     * Drafts (`LocalDraftRows`, tapping it opens the local draft in the composer — #95, commit
     * 758dcd3c — as long as the account has a Drafts folder and the unread-only filter is off, see
     * `showsLocalDrafts`); the outbox row is only its copy, and reopening the copy put it back on
     * the wire. Retry and Delete stay, and Retry is the one gesture that sends it.
     */
    fun canEdit(pgpMode: String?, state: OutboxState, carriesUnreplayablePrebuiltEntity: Boolean): Boolean =
        !carriesUnreplayablePrebuiltEntity && !pgpMode.equals("ENCRYPT", ignoreCase = true) &&
            when (state) {
                OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED -> true
                OutboxState.SENDING, OutboxState.EDITING, OutboxState.INTERRUPTED, OutboxState.KEPT_AS_DRAFT -> false
            }

    /**
     * What goes in [OutboxEntity.pgpMode] for a message being queued — a STRING, and deliberately
     * not `mode.name`.
     *
     * ⛔ That column does not say which mode the author chose. It says how the row's body must be
     * treated AT REST, and for that the two encrypting modes are the same thing: the body is blank
     * and the message lives in its entity file. The signature has already been consumed when the
     * entity was built; the outbox row has no use for it whatsoever.
     *
     * So [PgpMode.ENCRYPT_UNSIGNED] is stored as "ENCRYPT", and two holes stay shut: [canEdit]
     * compares this column as TEXT, so a new name would slip past it and offer Edit on a row whose
     * body was blanked (the composer opens empty and sends an empty message); and an APK rolled
     * back to a version that has never heard of the name would fail `PgpMode.valueOf` on reopen
     * and drop the composer to OFF without a word.
     *
     * ⚠ Delivery itself does NOT read this column — it goes on the pre-built entity file — so do
     * not credit this shortcut with keeping the send path right. It keeps the SCREEN right.
     *
     * ⚠ Consequence, accepted with eyes open: an unsigned encrypted row reads back as ENCRYPT.
     * Nothing reopens either of them today (Edit is refused on both). ⛔ If Edit ever becomes
     * possible on an encrypted row, this shortcut must fall FIRST.
     */
    fun outboxPgpModeName(mode: PgpMode?): String? = when (mode) {
        null, PgpMode.OFF -> null
        PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED -> "ENCRYPT"
        PgpMode.SIGN -> "SIGN"
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
        // All three count AT ONCE, and for the same reason: none of them is on its way anywhere,
        // so no delay would make the silence any truer. KEPT_AS_DRAFT is not an alarm — it is the
        // only thing on screen saying where that message went (#95) — which is why it counts on the
        // badge while it deliberately stays off the failure banner ([needsFailureBanner]).
        // INTERRUPTED is the same shape: its delivery has STOPPED, so the grace (which exists so a
        // send that works never draws the eye, #82) would buy nothing and only delay the one line
        // telling the user a message needs a decision.
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED -> true
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
        // These four are already settled: FAILED, KEPT_AS_DRAFT and INTERRUPTED count from the
        // first emission, EDITING never counts at all. See [countsAt].
        OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT, OutboxState.EDITING,
        OutboxState.INTERRUPTED,
        -> false
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
     * ⛔ Nor is [OutboxState.INTERRUPTED], and there the claim would be worse than untrue: that row
     * is a delivery that may well have REACHED the server. "Some messages didn't send" over a
     * message that did send is an assertion nothing would ever take back.
     *
     * Exhaustive on purpose: a seventh state has to answer this out loud rather than inherit an
     * answer from an `else`.
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
