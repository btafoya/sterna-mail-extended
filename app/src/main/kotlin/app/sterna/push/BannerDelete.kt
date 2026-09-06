package app.sterna.push

/**
 * What the Delete button of a new-mail banner is to do — see [bannerDeleteAct].
 */
sealed interface BannerDeleteAct {
    /**
     * Nothing at all: no move, no local eviction, and the banner STAYS UP. A banner that does not
     * fall is the statement that nothing happened (the Reply path says it the same way,
     * [QuickReplyAct.DoNothing]), and the same gesture is one tap away in the list — where a
     * destroy has its cancelable Undo window (Codeberg #23) and this button has none.
     */
    data object DoNothing : BannerDeleteAct

    /** Move to Trash, exactly as this button always has, and then take the banner down. */
    data object MoveToTrash : BannerDeleteAct
}

/**
 * Whether a banner's Delete would actually MOVE the message, decided from the local caches alone.
 *
 * The screen asks the same question through `MailRepository.deleteWouldDestroy`, which resolves
 * the Trash over the NETWORK (IMAP LIST, JMAP connect()). A broadcast receiver runs under
 * goAsync's ~10 s and cannot pay for that, so this rule is fed by two purely local reads instead:
 * the role the folder cache holds for the message's own folder ([cachedRole], from
 * `MailRepository.mailboxRole`), and whether that cache names a Trash for the account at all
 * ([hasCachedTrash], from `MailRepository.hasCachedTrash`).
 *
 * Two inputs make this button destructive in the local sense, and both end in [DoNothing]:
 *
 *  - the message is ALREADY in Trash. `delete()` then asks the server for a move that moves
 *    nothing, and drops the cached row AND its search-index row — the message stays on the server
 *    and leaves the screen until the folder is synced again. (That eviction is deliberate for the
 *    list's own delete, which the user aimed at a row in front of them; it is not something a
 *    stale banner should trigger.)
 *  - the account has NO Trash. `delete()` throws — after it has already unlisted the message from
 *    a pending Empty-trash order and, with `markReadOnDelete` on, marked it read. The banner's
 *    catch swallows the throw, so those two writes are all the user gets.
 *
 * The second case needs [cachedRole] to be non-null, and that is not decoration: a known role is
 * the proof that this account's folder list HAS been synced. Without it a cache that was never
 * filled reads exactly like an account with no Trash, and a perfectly ordinary Delete would be
 * refused.
 *
 * Everything else moves, which is the harmless direction and the one `noOpEvictionFor` already
 * takes: no cached row, or a folder the cache knows no role for, means "unknown", and an unknown
 * folder is not a Trash. `"trash"` is compared exactly, as `MailboxDao.idForRole` does — an
 * unelected trash is stored marked (`"~trash"`), it is not THE trash, and a delete really does
 * move out of it.
 *
 * And the same staleness the other way round, equally accepted: a Trash created server-side since
 * the last folder sync is not in the cache, so an ordinary Delete on such an account is refused
 * although `delete()` — which resolves the role live — would have moved the message. Nothing is
 * lost by it: the banner stays, and the gesture waits in the list.
 *
 * Residual hole, accepted rather than hidden: a Trash that was never synced holds no cached rows
 * at all, so a message deleted in the app and then Deleted again from its banner falls through the
 * "no cached row" arm and the defect survives there. It is narrower than the defect, it loses no
 * data, and closing it would cost the network round trip this rule exists to avoid.
 */
fun bannerDeleteAct(cachedRole: String?, hasCachedTrash: Boolean): BannerDeleteAct = when {
    cachedRole == TRASH_ROLE -> BannerDeleteAct.DoNothing
    cachedRole != null && !hasCachedTrash -> BannerDeleteAct.DoNothing
    else -> BannerDeleteAct.MoveToTrash
}

/** The folder role a delete aims at, spelled as the folder cache stores it. */
private const val TRASH_ROLE = "trash"
