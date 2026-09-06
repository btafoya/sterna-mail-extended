package app.sterna.core.data.mail

/**
 * The Sent folder to hand [ImapMailService.send], i.e. whether it APPENDs a copy of the message it
 * has just submitted: [sentMailboxOfRole] (the folder carrying the `sent` role) when the account's
 * "Upload sent messages" setting is on, and null when it is off.
 *
 * A FUNCTION, because it is the only part of this path anything can run. `ImapMailService.send`
 * wants a real account, a real socket and a token refresher, and `MailRepository.performDelivery`
 * wants the whole repository — so a condition written inline there would be exercised by nothing,
 * on either side of it. Here the two branches are executed (`SentCopyUploadTest`), and the wiring
 * that reaches them is pinned separately (`SentCopyCallSiteTest`).
 *
 * null is not a new state for the caller: it is what a freshly added account already produces,
 * before a folder has been seen carrying the `sent` role. `send` skips the APPEND on it and the
 * SMTP submission succeeds regardless — which is exactly what the setting turned off must do, and
 * why cutting the copy needs no new failure path.
 *
 * The decision belongs to this layer, not to `ImapMailService`: the setting is per account and
 * the service knows nothing of accounts. `send()` keeps taking a folder, and one caller decides.
 */
fun sentMailboxToUpload(uploadSentCopy: Boolean, sentMailboxOfRole: String?): String? =
    if (uploadSentCopy) sentMailboxOfRole else null
