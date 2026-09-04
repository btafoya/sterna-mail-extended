package app.sterna.core.data.mail

/**
 * Whether the in-memory raw-source cache may answer THIS read, and its answer when it may.
 *
 * Pure plumbing decision, extracted from [MailRepository.fetchRawSource] exactly as [byIdsChunked]
 * was: no Room, no context, no socket, so it really runs in the JVM.
 *
 * ⛔ WHY IT IS A DECISION AND NOT A LOOKUP. The cache is keyed `accountId:emailId` and holds NO
 * numbering: an IMAP id is `imap:<account>:<folder>:<uid>` and a UID means one message under one
 * UIDVALIDITY and another message under the next. So a hit answers "the octets we last read for
 * that text", which is exactly what a caller carrying a frozen numbering must not be given — it
 * would sail past the SELECT that opposes the numbering and hand the move between accounts a
 * message nobody ticked. Then only the Trash half would refuse, and the copy already appended on
 * the other account would stay: a permanent duplicate, with no Undo on that path (#189).
 *
 * So: a stated [expectedUidValidity] means the caller wants the server asked, and the cache is
 * SKIPPED — not consulted and discarded, skipped, because consulting it is the whole cost. `null`
 * — every ordinary read: opening a message, viewing its headers, saving it as `.eml`, retrying a
 * decryption — is the cache as it always was, four entries deep and the reason an interaction
 * retry does not refetch megabytes.
 *
 * ⚠ Only the READ is refused. The write is not this function's business: octets fetched under a
 * numbering that was just confirmed are as good as any, and dropping them would make the retry of
 * an OpenPGP interaction refetch the whole message.
 */
internal fun <T : Any> rawSourceFromCache(expectedUidValidity: Long?, cached: () -> T?): T? =
    if (expectedUidValidity != null) null else cached()
