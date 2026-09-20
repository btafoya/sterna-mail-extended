package app.sterna.core.data.mail

import app.sterna.core.data.account.SPECIAL_FOLDER_ROLES
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.imap.ImapFolder
import app.sterna.core.jmap.model.Mailbox

/*
 * The account's own choice of which folder carries a special role ([StoredAccount.specialFolders]),
 * applied where a role is decided, the listing that becomes cache rows ([imapMailboxEntity],
 * [toEntities]), and never where one is read. The `role` column then holds the effective role, so
 * the drawer, the search, the notification banner and the delete follow from the cache alone,
 * offline included.
 *
 * It sits above the listing's own election rather than replacing it: with nothing pinned, every
 * function here answers what `ImapClient.assignRoles` settled, or what the JMAP server stated.
 *
 * One decision for the two protocols, which is why it is expressed on [RoleCandidate] rather than
 * on a listing type: IMAP and JMAP resolving roles apart is how they came to disagree about which
 * folder a delete aims at.
 */

/**
 * One listed folder reduced to what the choice needs of it. The two protocols agree on nothing else:
 * an IMAP id is a path the user can read and a JMAP id is an opaque server string, so a rule written
 * over either listing type would learn things about ids that do not hold on the other.
 */
internal data class RoleCandidate(
    val id: String,
    /** The role this folder carries when nothing is pinned, as its own listing settled it. */
    val automaticRole: String?,
    /** The role it claimed while another folder of the same listing was elected to it. */
    val lostClaim: String? = null,
    /** No role may be moved onto the inbox, and each protocol recognises its own. */
    val isInbox: Boolean = false,
)

/** The inbox is recognised by the role the listing settled and by the path RFC 3501 §5.1 reserves,
 *  in any case: a listing that failed to elect an inbox must not become a way through that guard. */
internal fun ImapFolder.roleCandidate(): RoleCandidate = RoleCandidate(
    id = path,
    automaticRole = role,
    lostClaim = unelectedRole,
    isInbox = role == "inbox" || path.equals("INBOX", ignoreCase = true),
)

/**
 * A JMAP listing's candidates, a role stated twice settled first-listed-wins as `assignRoles`
 * settles an IMAP one. RFC 8621 §2 asks for one holder per role and a server that answers two is
 * held to an election here rather than to a map write keeping whichever came last: the loser is
 * marked and stays out of search, and a pinned folder cannot be overwritten by either holder.
 *
 * A JMAP id is opaque, so the reserved path is not read here: on a server whose ids happen to be
 * readable, a mailbox whose id spells INBOX is an ordinary folder and stays pinnable.
 */
internal fun jmapRoleCandidates(mailboxes: List<Mailbox>): List<RoleCandidate> {
    val taken = mutableSetOf<String>()
    return mailboxes.map { mailbox ->
        val elected = mailbox.role?.takeIf { taken.add(it) }
        RoleCandidate(
            id = mailbox.id,
            automaticRole = elected,
            lostClaim = if (elected == null) mailbox.role else null,
            isInbox = mailbox.role == "inbox",
        )
    }
}

/**
 * The role table a JMAP gesture routes by: the send's copy, the draft, the archive and the delete.
 * It answers the same decision the cache rows do, or a pinned Sent folder would be the drawer's
 * while the send went on filing into the one the server named.
 *
 * A role whose holder was dispossessed leaves the table rather than pointing at a marked row
 * ([unmarkedRole]): the gestures that resolve `trash` and `archive` through it create the folder
 * when it answers nothing, and a `~trash` handed back as a mailbox id would be a move to a folder
 * that does not exist.
 *
 * The election in [jmapRoleCandidates] is what lets this end on a map write: no two candidates can
 * answer one role, so nothing here quietly decides between two holders.
 */
internal fun jmapRolesToMailboxId(mailboxes: List<Mailbox>, chosen: Map<String, String>): Map<String, String> {
    val candidates = jmapRoleCandidates(mailboxes)
    val honoured = honouredSpecialFolders(chosen, candidates)
    return candidates.mapNotNull { candidate ->
        unmarkedRole(specialRole(candidate, honoured))?.let { it to candidate.id }
    }.toMap()
}

/**
 * [chosen] minus what this listing cannot honour: a key that is not one of [SPECIAL_FOLDER_ROLES],
 * a folder the listing does not carry (deleted on the server, unsubscribed, a listing that failed),
 * and the inbox, which no role may be moved onto.
 *
 * Dropping an unresolvable folder protects mail rather than tidying up. A role no folder carries
 * does not make a gesture refuse: `MailRepository.trashMailboxId` and
 * `MailRepository.archiveMailboxId` then fall back on a folder recognised by its name, and the
 * delete creates a bin when even that answers nothing, so the message lands, visibly, in a folder
 * nobody designated. The automatic role puts it back where it went before the choice was made, and
 * the choice itself stays in the account record: the folder may come back.
 *
 * The inbox is refused as a target because a row carries one role: overwriting it leaves the
 * account with no folder answering `MailRepository.cachedInboxMailboxId`, so unarchiving moves
 * nothing at all, and under `trash` or `junk` the inbox drops out of every search result.
 */
internal fun honouredSpecialFolders(
    chosen: Map<String, String>,
    listed: List<RoleCandidate>,
): Map<String, String> {
    if (chosen.isEmpty()) return emptyMap()
    val targets = listed.filterNot { it.isInbox }.mapTo(mutableSetOf()) { it.id }
    return chosen.filterKeys { it in SPECIAL_FOLDER_ROLES }.filterValues { it in targets }
}

/**
 * The role [folder]'s cache row carries under [chosen], its account's honoured choices.
 *
 * A pinned folder takes the role whole, and the folder that would have carried it automatically is
 * dispossessed: two holders of one role are two bins in the drawer and a delete aiming at whichever
 * the listing returned first. A dispossessed holder takes the mark of a lost claim, exactly like a
 * loser of the listing's own election ([UNELECTED_ROLE_MARK]), which is what keeps a folder the
 * server calls junk out of every search result whether it won that election or not.
 *
 * A folder pinned to two roles settles in [SPECIAL_FOLDER_ROLES] order, a row carrying one role.
 */
internal fun specialRole(folder: RoleCandidate, chosen: Map<String, String>): String? {
    SPECIAL_FOLDER_ROLES.firstOrNull { chosen[it] == folder.id }?.let { return it }
    val automatic = folder.automaticRole
    if (automatic != null && !chosen.containsKey(automatic)) return automatic
    return storedLostClaim(automatic ?: folder.lostClaim)
}

/** The role a folder claimed and did not get, as the cache stores it; only the roles a search skips
 *  are marked, the rest read as no role at all. Named away from `Mailbox.lostRoleClaim`, the boolean
 *  the drawer reads: a lint sweeps every module for that field's readers by name. */
private fun storedLostClaim(role: String?): String? =
    role?.takeIf { it in NOT_SEARCHED_BASE_ROLES }?.let { UNELECTED_ROLE_MARK + it }

/**
 * The folders [chosen] pins to a role other than [role]: the by-name fallbacks must skip them.
 *
 * Pin `archive` onto the folder the server declares its bin and the `trash` role is left with no
 * holder, which is exactly when `MailRepository.trashMailboxId` falls back on the name table, and
 * that table recognises the very same folder, so a delete would file into the Archive the user
 * designated, in plain sight. Only [SPECIAL_FOLDER_ROLES] count: no other key names a folder a
 * message is routed to.
 */
internal fun pinnedToAnotherRole(role: String, chosen: Map<String, String>): Set<String> =
    chosen.filterKeys { it in SPECIAL_FOLDER_ROLES && it != role }.values.toSet()

/** The listing a by-name fallback for [role] may elect from: [this] minus what the account pinned
 *  elsewhere ([pinnedToAnotherRole]). */
internal fun List<Mailbox>.notPinnedToAnotherRole(role: String, chosen: Map<String, String>): List<Mailbox> {
    val pinned = pinnedToAnotherRole(role, chosen)
    return if (pinned.isEmpty()) this else filterNot { it.id in pinned }
}

/** The inbox among cache rows, recognised as the two listings recognise it ([roleCandidate],
 *  [jmapRoleCandidates]): by the role its own listing settled, and where an id is a path
 *  ([idsArePaths], IMAP) by the one RFC 3501 §5.1 reserves. A row carries one role, so a server
 *  announcing `\Trash` on INBOX leaves that row reading a lost claim rather than `inbox`: the
 *  cached role alone is no guard, and the reserved path is the whole of it on IMAP. */
private fun MailboxIdRole.isInbox(idsArePaths: Boolean): Boolean =
    role == "inbox" || (idsArePaths && id.equals("INBOX", ignoreCase = true))

/**
 * The cache rows whose stored role a change of choice rewrites at once, without waiting for a
 * network refresh: the drawer, the search and the delete all read the cached role, so a choice left
 * to the next sync is a setting the app visibly ignores. [rows] are one account's cached
 * (folder, role) pairs as stored, marks included, and the answer is the rows to write, in the order
 * to write them.
 *
 * A delta, not a recount: the stored role is the effective one and no column holds the automatic
 * role, so nothing here can rebuild what a folder losing its pin carried.
 *
 * Pinning hands [choice] the role whole and dispossesses whoever effectively held it: the folder
 * the listing elected takes the mark of a lost claim, as a loser of that election does, while the
 * folder [previousChoice] names is dropped to no role, it never claimed one. Unpinning is the
 * reverse and stops at what the cache can prove: the role leaves the folder [previousChoice] named,
 * and a folder still marked with that claim takes it back. A folder the server declared `sent`,
 * `drafts` or `archive` carries no mark, so an unpin, and a second choice replacing a first, leave
 * that folder role-less until the next refresh; no mail is misfiled meanwhile, a send and a delete
 * resolve their folder through the server.
 *
 * The folder taking the role is written last, so no reader ever meets two holders of one role.
 * Guards are [honouredSpecialFolders]', over these rows, the inbox told apart by [isInbox] on both
 * branches: a pin moves no role onto it, and an unpin hands none back to it either, the folder a
 * claim returns to being picked off these rows alone.
 */
internal fun specialFolderRoleDelta(
    rows: List<MailboxIdRole>,
    role: String,
    previousChoice: String?,
    choice: String?,
    idsArePaths: Boolean,
): List<MailboxIdRole> {
    val written = mutableMapOf<String, String?>()
    if (choice != null) {
        val listed = rows.map {
            RoleCandidate(
                id = it.id,
                automaticRole = unmarkedRole(it.role),
                isInbox = it.isInbox(idsArePaths),
            )
        }
        if (honouredSpecialFolders(mapOf(role to choice), listed).isEmpty()) return emptyList()
        // The folder the user had designated is left with no role: the mark says a folder claimed a
        // role and lost an election, and it keeps that folder out of every search result. Only the
        // holder its own listing elected ever claimed anything.
        rows.filter { it.role == role }.forEach {
            written[it.id] = if (it.id == previousChoice) null else storedLostClaim(role)
        }
        written[choice] = role
    } else {
        // The folder the choice NAMED, never whoever answers the role now: an unhonoured choice (its
        // folder gone from the listing) leaves the role with the folder that wins it automatically,
        // and stripping that one would leave the account with no such folder at all.
        val unpinned = rows.firstOrNull { it.id == previousChoice && it.role == role } ?: return emptyList()
        written[unpinned.id] = null
        rows.firstOrNull { it.role == UNELECTED_ROLE_MARK + role && !it.isInbox(idsArePaths) }
            ?.let { written[it.id] = role }
    }
    return rows.filter { it.id in written && written[it.id] != it.role }
        .map { MailboxIdRole(it.id, written[it.id]) }
        .sortedBy { if (it.role == role) 1 else 0 }
}
