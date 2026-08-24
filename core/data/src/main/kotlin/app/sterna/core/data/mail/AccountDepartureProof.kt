package app.sterna.core.data.mail

/**
 * Whether an account's DEPARTURE is proven — the only state in which a row that is the sole copy of
 * a message may be destroyed.
 *
 * Why this is a function and not an expression written at each site. `AccountStore.credentials`
 * answers `null` down several paths and only one of them is a departure: a stored secret that will
 * not decode (a regenerated Keystore key), a linked sub-account whose `pw_` slot was never written,
 * an account whose slot was deliberately withdrawn while the account itself is alive, and an
 * account blob that cannot be read at all. A `null` therefore proves nothing on its own, and every
 * site that reads one has to fetch the proof separately. The sites are the cancel path
 * (`cancelScheduledSend`'s `accountIsGone` seam), `ScheduledSendWorker`'s fire time, and the outbox
 * path, which reads the same `null` and is not wired here yet — three copies of one rule, which is
 * three chances for one of them to drift into destroying on an unproven `null`.
 *
 * ⛔ **Both halves are indispensable, and the first one is the non-obvious one.** `accounts()`
 * answers an UNREADABLE blob with an EMPTY list, exactly as it answers "no account is configured" —
 * which is precisely what `AccountStore.accountsUnreadable()` was built to say
 * (`AccountStore.kt:304`).
 * With the list half alone, a storage failure reads as "every account left at once", and the caller
 * destroys the only copy of a message it could have simply kept.
 *
 * ⛔ **Unproven ⇒ nothing is destroyed.** This answers `false` for everything that is not a proven
 * departure, and a caller may only ever destroy on `true`. Which is grid rule 1: an option that
 * cannot lose data wins, even slower and even uglier — a message left queued one hour longer is
 * recoverable, a deleted one is not.
 *
 * Pure on purpose: it takes the two verdicts as booleans instead of the store, so a unit test can
 * EXECUTE it and pin the four combinations by value. Nothing may restate the choice elsewhere.
 *
 * @param accountsUnreadable `AccountStore.accountsUnreadable()` — read AFTER the `null`, and about
 *   storage, not about this account.
 * @param accountStillListed whether the id is in `AccountStore.accounts()` right now.
 */
fun accountDepartureIsProven(accountsUnreadable: Boolean, accountStillListed: Boolean): Boolean =
    !accountsUnreadable && !accountStillListed
