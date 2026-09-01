package app.sterna.core.data.account

/**
 * What a pruned shared mailbox leaves behind, so that re-granting it does not hand the user a
 * factory-fresh account.
 *
 * The problem it solves. [AccountStore.reconcileLinkedAccounts] adds and prunes, and never
 * updates: when a JMAP server stops exposing a delegated mailbox (a reloaded ACL, a share
 * re-created, a server restart) the sub-account's [StoredAccount] is destroyed, and the next
 * session that exposes it again mints a brand-new record with the constructor's defaults. The
 * notifications the user had turned OFF for that share come back on their own, and the account
 * colour, the watched folders (so the push on them) and the folded state of its tree are gone.
 *
 * The memory is keyed `loginId -> the records pruned under that login` (each already carries its
 * own [StoredAccount.jmapAccountId]). It is COMFORT state, deliberately: losing it costs exactly
 * today's behaviour and never an account, which is why [AccountStore] reads and writes it outside
 * the account blob's gate.
 *
 * Pure, `internal`, and free of Android on purpose: the whole decision runs in a JVM unit test
 * ([LinkedAccountMemoryTest]), where the store itself cannot be built.
 */

/** The account list and the leftover memory, as [reconciledAccounts] computed them together. */
internal data class ReconciledAccounts(
    val accounts: List<StoredAccount>,
    val memory: Map<String, List<StoredAccount>>,
)

/**
 * File every [pruned] record under its login, so a later re-grant can restore its settings.
 *
 * A record with no [StoredAccount.loginId] or no [StoredAccount.jmapAccountId] is IGNORED: it
 * names no login, or nothing to match a re-granted server account against, so remembering it could
 * only ever restore it onto the wrong record. A deposit on a (login, jmap account) pair already
 * present REPLACES it — the newest prune is the one that carries the user's latest settings.
 */
internal fun rememberPrunedLinked(
    memory: Map<String, List<StoredAccount>>,
    pruned: List<StoredAccount>,
): Map<String, List<StoredAccount>> {
    var result = memory
    pruned.forEach { record ->
        val loginId = record.loginId ?: return@forEach
        val jmapAccountId = record.jmapAccountId ?: return@forEach
        val kept = result[loginId].orEmpty().filterNot { it.jmapAccountId == jmapAccountId }
        result = result + (loginId to (kept + record))
    }
    return result
}

/** The remembered record for a (login, server account) pair, or null when nothing was filed. */
internal fun recallLinked(
    memory: Map<String, List<StoredAccount>>,
    loginId: String,
    jmapAccountId: String,
): StoredAccount? = memory[loginId]?.firstOrNull { it.jmapAccountId == jmapAccountId }

/**
 * Drop one (login, server account) entry. A sub-map left empty is REMOVED with it: a ghost key
 * would keep a login alive in the memory long after its last share, and grow forever.
 */
internal fun forgetLinked(
    memory: Map<String, List<StoredAccount>>,
    loginId: String,
    jmapAccountId: String,
): Map<String, List<StoredAccount>> {
    val kept = memory[loginId]?.filterNot { it.jmapAccountId == jmapAccountId } ?: return memory
    return if (kept.isEmpty()) memory - loginId else memory + (loginId to kept)
}

/**
 * Forget what an explicit REMOVAL made meaningless. A record the user deleted by hand must not
 * come back wearing its old settings: deleting it IS the decision.
 *
 * A removed LOGIN ([StoredAccount.loginId] null) takes its whole sub-map with it — its shares
 * cannot outlive the credential they borrow. A removed SUB-ACCOUNT takes only its own entry, so a
 * sibling share removed on its own leaves the others' settings alone.
 */
internal fun forgetRemovedLinked(
    memory: Map<String, List<StoredAccount>>,
    removed: List<StoredAccount>,
): Map<String, List<StoredAccount>> {
    var result = memory
    removed.forEach { record ->
        val loginId = record.loginId
        if (loginId == null) {
            result = result - record.id
        } else {
            val jmapAccountId = record.jmapAccountId ?: return@forEach
            result = forgetLinked(result, loginId, jmapAccountId)
        }
    }
    return result
}

/**
 * The [StoredAccount] for a newly granted share [sub] under [login], wearing [remembered]'s
 * settings when this share was seen before.
 *
 * ⛔ The `copy` below is a RESET list, never a KEEP list, and that is the whole point. Every field
 * named here takes its value from the FRESH record; everything not named is kept from the
 * remembered one BY DEFAULT. Written the other way round — listing the settings worth keeping —
 * the next setting added to [StoredAccount] would silently be lost on every re-grant, which is
 * exactly the defect this function exists to fix. Adding a field must cost nothing; forgetting one
 * must be the loud choice, not the quiet one.
 *
 * What is reset, and why: identity and wiring ([StoredAccount.id] is new, and server / username /
 * loginId / protocol / authType belong to the login as it is TODAY, not as it was when the share
 * vanished), the name the server just announced, and everything that is stale device/sync state
 * (inbox id and name, unread, the server-discovered identities, the import flag, the short-lived
 * OAuth material). What survives is what the USER chose: notifications, colour, watched folders,
 * the fold registry, sync window, signature and manual identities, subscription filter, PGP.
 */
internal fun mintLinkedAccount(
    login: StoredAccount,
    sub: DiscoveredMailAccount,
    loginId: String,
    remembered: StoredAccount?,
    newId: String,
): StoredAccount {
    val fresh = StoredAccount(
        id = newId,
        server = login.server,
        username = login.username,
        accountName = sub.name,
        loginId = loginId,
        jmapAccountId = sub.jmapAccountId,
        protocol = login.protocol,
        authType = login.authType,
    )
    return remembered?.copy(
        id = fresh.id,
        server = fresh.server,
        username = fresh.username,
        accountName = fresh.accountName,
        loginId = fresh.loginId,
        jmapAccountId = fresh.jmapAccountId,
        protocol = fresh.protocol,
        authType = fresh.authType,
        inboxId = fresh.inboxId,
        inboxName = fresh.inboxName,
        unread = fresh.unread,
        serverIdentities = fresh.serverIdentities,
        importPending = fresh.importPending,
        oauthAccessToken = fresh.oauthAccessToken,
        oauthAccessExpiresAt = fresh.oauthAccessExpiresAt,
        oauthTokenEndpoint = fresh.oauthTokenEndpoint,
        oauthClientId = fresh.oauthClientId,
    ) ?: fresh
}

/**
 * Apply [diff] to [list] and carry the memory across: the WHOLE list transformation
 * [AccountStore.reconcileLinkedAccounts] performs, lifted out of the store so it can be executed
 * in a unit test, plus the remember/recall the store alone could never prove.
 *
 * ⛔ A newly minted record is APPENDED, never slotted back where it used to be. List order is age
 * order, and the duplicate self-healing in [diffLinkedAccounts] keeps the OLDEST of two records
 * tracking one server account. A memory that also restored a record's seniority would let a
 * restored share outrank a live one and get the live one pruned.
 *
 * The memory is CONSUMED on recall: a share that came back is no longer a share that went away,
 * and leaving the entry behind would let a much later re-grant restore settings the user has since
 * changed on the live record.
 */
internal fun reconciledAccounts(
    list: List<StoredAccount>,
    login: StoredAccount,
    loginId: String,
    diff: LinkedAccountsDiff,
    memory: Map<String, List<StoredAccount>>,
    mintId: () -> String,
): ReconciledAccounts {
    val updated = list.toMutableList()
    var carried = memory
    // Pin the login's own JMAP account id on first discovery, so later reconciles can tell the
    // login apart from its sub-accounts and matching stays stable.
    diff.pinPrimaryId?.let { pin ->
        updated[updated.indexOfFirst { it.id == loginId }] = login.copy(jmapAccountId = pin)
    }
    diff.toAdd.forEach { sub ->
        val remembered = recallLinked(carried, loginId, sub.jmapAccountId)
        updated += mintLinkedAccount(login, sub, loginId, remembered, mintId())
        if (remembered != null) carried = forgetLinked(carried, loginId, sub.jmapAccountId)
    }
    if (diff.prunedIds.isNotEmpty()) {
        carried = rememberPrunedLinked(carried, list.filter { it.id in diff.prunedIds })
        updated.removeAll { it.id in diff.prunedIds }
    }
    return ReconciledAccounts(updated, carried)
}
