package app.sterna.core.data.account

/**
 * Whether [loginId]'s push subscription is held for a linked sub-account rather than for the login
 * itself (#31): true as soon as one sub-account still wants new mail, whatever the login's own
 * switch says. A sub-account owns no subscription, so this one answer binds both ends of a single
 * subscription's life. `UnifiedPushManager.tearDownAfterSyncOff` refuses to hand back a
 * subscription it keeps alive, so the fallback cycle owes a renewal to every login it keeps, and a
 * renewal only: that login is out of the polled set by the user's own choice, and putting a fetch
 * or a worker on it is what #204 closed. Read from one end alone, the two drift in silence, and
 * the record here reads ACTIVE long after the server dropped a subscription nobody renewed.
 *
 * An extension rather than a member of [AccountStore], which opens shared preferences while being
 * constructed: a member could only ever be read by a JVM test, never run by one.
 */
fun AccountStorage.keepsPushForLinkedAccount(loginId: String): Boolean =
    linkedAccounts(loginId).any { it.notificationsEnabled }
