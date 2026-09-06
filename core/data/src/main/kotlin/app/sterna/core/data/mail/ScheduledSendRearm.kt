package app.sterna.core.data.mail

import app.sterna.core.data.db.ScheduledSendEntity

/** One work item to book: which scheduled row, and how long from now. */
data class ScheduledSendJob(val id: Long, val delayMillis: Long)

/** How long from [nowMillis] until [sendAtMillis] — never negative, so an overdue row goes at once. */
fun scheduledSendDelayMillis(sendAtMillis: Long, nowMillis: Long): Long =
    (sendAtMillis - nowMillis).coerceAtLeast(0)

/**
 * The work a startup has to book for the [rows] of `scheduled_sends`.
 *
 * **Every row gets one, overdue or not**, and that is the decision of this seam. The rows arrive
 * from `MailRepository.scheduledSends`, which asks the table about every instant rather than about
 * now, precisely so a message whose hour passed with no work item behind it is not left with none
 * for ever. It is the queue that waits the LONGEST — hours, by construction — so it is the one most
 * likely to outlive the job that was going to fire it, the row is the only copy of the message, and
 * nothing else ever comes and asks about it: without a job it sits in the "Scheduled" screen showing
 * an hour in the past, for good. An overdue row therefore goes out with a delay coerced to zero,
 * i.e. LATE rather than never.
 *
 * **What can actually separate a row from its job — and it is NOT a restore.** `allowBackup` is
 * `false` and `sterna.db` is excluded from cloud backup and from device transfer alike
 * (`app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`), so a restore brings back
 * neither the row nor the job; and clearing the app's storage takes both. What is readable in this
 * repository is that the two live in different files — the table in `databases/sterna.db`,
 * WorkManager's jobs in `no_backup/androidx.work.workdb` — so one can be lost without the other;
 * and that `ComposeViewModel.scheduleSend` writes the row and books its job as TWO operations, so a
 * process death, or a throw its `catch` turns into an error banner, leaves a durable row that
 * nothing was ever attached to. The vector is not claimed to have been OBSERVED on a real device:
 * what this seam repairs is the ABSENCE of a safety net.
 *
 * And no "too old to bother" threshold: that is a magic number, a second behaviour to carry, and
 * a string to translate into nine languages before the screen could stop lying.
 */
fun scheduledSendJobs(rows: List<ScheduledSendEntity>, nowMillis: Long): List<ScheduledSendJob> =
    rows.map { ScheduledSendJob(it.id, scheduledSendDelayMillis(it.sendAtMillis, nowMillis)) }
