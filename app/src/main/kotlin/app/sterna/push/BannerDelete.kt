package app.sterna.push

/**
 * What the Delete button of a new-mail banner is to do — see [bannerDeleteAct].
 */
sealed interface BannerDeleteAct {
    /**
     * Nothing at all: no move, no local eviction, and the banner STAYS UP — a banner that does not
     * fall is the statement that nothing happened. The same gesture is one tap away in the list,
     * where a destroy has its cancelable Undo window (#23) and this button has none.
     */
    data object DoNothing : BannerDeleteAct

    /** Move to Trash, exactly as this button always has, and then take the banner down. */
    data object MoveToTrash : BannerDeleteAct
}

/**
 * Whether a banner's Delete would actually MOVE the message, decided from the local caches alone.
 * The screen asks the same question over the NETWORK, which a receiver under goAsync's ~10 s cannot
 * pay for, so this is fed by two local reads: the role the folder cache holds for the message's own
 * folder, and whether that cache names a Trash for the account at all.
 *
 * Two inputs make this button destructive in the local sense, and both end in [DoNothing]. The
 * message is ALREADY in Trash: `delete()` then moves nothing and drops the cached row and its
 * search-index row, so the message stays on the server and leaves the screen. Or the account has NO
 * Trash: `delete()` throws, after unlisting the message from a pending Empty-trash order and, with
 * `markReadOnDelete` on, marking it read.
 *
 * That second case needs [cachedRole] to be non-null, and it is not decoration: a known role is
 * the proof that this account's folder list HAS been synced, without which an unfilled cache reads
 * exactly like an account with no Trash. Everything else moves, the harmless direction. `"trash"`
 * is compared exactly: an unelected trash is stored marked and a delete really does move out of it.
 *
 * The same staleness the other way round is accepted: a Trash created server-side since the last
 * sync is not in the cache, so an ordinary Delete is refused although `delete()` would have moved
 * the message. Residual hole, accepted rather than hidden: a Trash that was never synced holds no
 * cached rows, so a message deleted in the app and Deleted again from its banner falls through the
 * "no cached row" arm.
 */
fun bannerDeleteAct(cachedRole: String?, hasCachedTrash: Boolean): BannerDeleteAct = when {
    cachedRole == TRASH_ROLE -> BannerDeleteAct.DoNothing
    cachedRole != null && !hasCachedTrash -> BannerDeleteAct.DoNothing
    else -> BannerDeleteAct.MoveToTrash
}

private const val TRASH_ROLE = "trash"
