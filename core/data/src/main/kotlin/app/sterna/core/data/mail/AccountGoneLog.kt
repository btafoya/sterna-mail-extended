package app.sterna.core.data.mail

/**
 * What an [AccountGoneException] is allowed to CLAIM in a log line, given whether the stored
 * account list could be read at that moment.
 *
 * Why this is a function and not a string at each call site. [checkAccountStillConfigured] throws
 * when the id of the pass is not in the list of configured ids, and `AccountStore.accounts()`
 * answers an UNREADABLE blob with an empty list, exactly as it answers "no account is configured"
 * (see the paragraph of [checkAccountStillConfigured]). So the very same exception carries two
 * different facts, and the catch used to print only one of them: "signed out". A storage failure
 * then read as a deliberate gesture in the logcat a reporter sends us, and the hunt started in the
 * wrong place.
 *
 * The decision is NOT [checkAccountStillConfigured]'s to make: it receives a list of ids and
 * cannot know where the emptiness came from. It belongs to the caller, which holds the store — so
 * the caller passes `AccountStore.accountsUnreadable()` here, and this answers what to say.
 *
 * Pure on purpose: it takes the verdict as a boolean instead of a store, so a unit test can EXECUTE
 * it and pin both sentences by value. Nothing may restate the choice elsewhere.
 *
 * This says WHAT HAPPENED, never what the caller did about it: "dropped", "skipped" and the log tag
 * stay with the caller, which is the only one that knows.
 *
 * Which is what [passKind] is for, and why it is a parameter rather than a word chosen here. The
 * two sites are not the same kind of pass: `FetchAndNotify` is a background push/worker PASS, while
 * `MailRepository.refreshAllInboxes` is a WALK over the accounts, and its line said "mid-walk" long
 * before this function existed. A first version of this file swallowed that word and printed
 * "mid-pass" at both sites — the shared sentence must carry what the callers really have in common,
 * not flatten what distinguishes them. The caller names its own pass; this only decides WHICH of
 * the two claims is true.
 *
 * @param accountId the id the pass was writing under. Printed WHOLE: it is the only thing that ties
 *   the line to an account in a logcat a reporter sends us, and these ids are UUIDs — a prefix is
 *   not an identifier.
 * @param passKind the caller's own word for what was running ("pass", "walk"), read as "mid-<kind>".
 */
fun accountGoneCause(accountId: String, accountsUnreadable: Boolean, passKind: String): String =
    if (accountsUnreadable) {
        "the stored account list could not be read, so $accountId was not found in it (not a sign-out)"
    } else {
        "$accountId signed out mid-$passKind"
    }
