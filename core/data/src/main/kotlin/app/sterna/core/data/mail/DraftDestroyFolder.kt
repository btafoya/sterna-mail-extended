package app.sterna.core.data.mail

/**
 * The folder a destroy of a SERVER draft is opposed to — as a function a JVM test EXECUTES.
 *
 * `MailRepository` needs Room, a `Context` and a socket, so a rule written inside it is a rule no
 * test ever runs: the `?:` that carries the fallback below can be deleted and every suite stays
 * green. It is the whole decision, so it lives here and the repository is left with the two Room
 * reads that feed it.
 *
 * ⛔ **What the fallback is worth.** The destroy of a draft carries this folder to the worker, and a
 * destroy that arrives without one destroys NOTHING, in silence: `destroyAll` checks the ids against
 * the folder wave by wave and spares everything when it is absent (#122). The cached row is the
 * ordinary answer — but a draft that was never cached, or whose cache line went in a purge, or in a
 * `resetSyncState` followed by a partial re-query, has none. Without [draftsRoleMailboxId] behind it
 * the caller gets null, enqueues nothing, and the local row is consumed anyway: the server draft is
 * unmasked and comes back carrying the text from before the edit (#95 × #69).
 *
 * [cachedMailboxId] first and not the role: a draft can sit outside the account's Drafts folder (it
 * was filed, or the account names two), and destroying it against the role's folder would be
 * checking it against a folder it is not in — which spares it, silently, again.
 *
 * Null when neither answers: no order at all, which leaves the draft where the user can still see
 * it rather than firing a destroy nothing can check.
 */
fun draftDestroyFolder(cachedMailboxId: String?, draftsRoleMailboxId: String?): String? =
    cachedMailboxId ?: draftsRoleMailboxId
