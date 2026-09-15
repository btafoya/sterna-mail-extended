package app.sterna.core.data.mail

/** The give-up decision of the scheduled-send worker, where giving up destroys the only copy. */
object ScheduledSendRetry {
    /** Delivery failures retried before the next one gives up. */
    const val MAX_ATTEMPTS = 3

    /**
     * [priorFailures] is the count stored on the row, never WorkManager's `runAttemptCount`: that
     * one is raised before every run, including the runs that attempted nothing, and never resets.
     */
    fun shouldRetry(priorFailures: Int): Boolean = priorFailures < MAX_ATTEMPTS
}
