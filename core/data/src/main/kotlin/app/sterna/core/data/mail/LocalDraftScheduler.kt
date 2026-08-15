package app.sterna.core.data.mail

/**
 * Arms the background job that puts ONE draft the phone kept (#95) onto the server. Implemented in
 * the app layer (which owns WorkManager and the worker class) and injected into [MailRepository],
 * exactly as [OutboxScheduler] is for a send — so a save can book the deferred upload without the
 * data module depending on the app.
 *
 * ⛔ It only ever BOOKS work. There is no `cancel` here and there must not be one: a send can be
 * called off, a draft cannot, and the only thing that ends a draft's wait is an upload that went
 * through — which is what consumes its row.
 */
fun interface LocalDraftScheduler {
    /** Arm the upload of [id] on [accountId], first running after [initialDelayMillis]. */
    fun schedule(accountId: String, id: String, initialDelayMillis: Long)
}
