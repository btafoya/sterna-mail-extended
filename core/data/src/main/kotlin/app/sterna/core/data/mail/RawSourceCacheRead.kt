package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol

/**
 * Whether the in-memory raw-source cache may answer THIS read, and its answer when it may.
 *
 * Pure plumbing decision, extracted from [MailRepository.fetchRawSource] exactly as [byIdsChunked]
 * was: no Room, no context, no socket, so it really runs in the JVM.
 *
 * WHY IT IS A DECISION AND NOT A LOOKUP. The cache is keyed `accountId:emailId` and holds NO
 * numbering: an IMAP id is `imap:<account>:<folder>:<uid>` and a UID means one message under one
 * UIDVALIDITY and another message under the next. So a hit answers "the octets we last read for
 * that text", which is exactly what a caller carrying a frozen numbering must not be given — it
 * would sail past the SELECT that opposes the numbering and hand the move between accounts a
 * message nobody ticked. Then only the Trash half would refuse, and the copy already appended on
 * the other account would stay: a permanent duplicate, with no Undo on that path (#189).
 *
 * So: on IMAP, ANYTHING [frozen] — [FrozenNumbering.Frozen], its null value included — means the
 * caller wants the server asked, and the cache is SKIPPED, not consulted and discarded but skipped,
 * because consulting it is the whole cost. [FrozenNumbering.NothingFrozen] — every ordinary read:
 * opening a message, viewing its headers, saving it as `.eml`, retrying a decryption — is the cache
 * as it always was, four entries deep and the reason an interaction retry does not refetch
 * megabytes.
 *
 * WHY `Frozen(null)` SKIPS TOO, and it is the whole reason this takes a type rather than a
 * `Long?`. That shape is a ticked row with no numbering to offer, and it is REFUSED downstream
 * (`numberingToOppose`). A cache hit would answer before the refusal was ever reached, the octets
 * would be appended on the other account, and only the bin would refuse afterwards: a permanent
 * duplicate on B, with no Undo on that path. The refusal must not arrive after a hit.
 *
 * AND WHY [protocol] IS AN ARGUMENT. This runs ABOVE `fetchRawSource`'s protocol test, so it is
 * the ONE reader of a [FrozenNumbering] that no gate protects — and on JMAP every ticked row
 * carries `Frozen(null)`, because `numberingStampsOfRows` answers an empty map there by
 * construction. Skipping on that would make every cross-account move re-download a whole blob to
 * reach a refusal that cannot exist on that protocol: a JMAP `blobId` belongs to no numbering, so
 * there is nothing to oppose and the cache is exactly as safe as it has always been. So the skip is
 * IMAP-only, and it is a decision rather than a lookup for the same reason as the rest of this
 * file: it is run by [RawSourceFromCacheTest], not read out of a body.
 *
 * Only the READ is refused. The write is not this function's business: octets fetched under a
 * numbering that was just confirmed are as good as any, and dropping them would make the retry of
 * an OpenPGP interaction refetch the whole message.
 */
internal fun <T : Any> rawSourceFromCache(
    protocol: MailProtocol,
    frozen: FrozenNumbering,
    cached: () -> T?,
): T? = if (protocol == MailProtocol.IMAP && frozen is FrozenNumbering.Frozen) null else cached()
