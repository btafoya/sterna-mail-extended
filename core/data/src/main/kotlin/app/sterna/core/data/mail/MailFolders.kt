package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.jmap.JmapClient

/**
 * What [MailFolders] needs from [MailRepository] and nothing more. It is an interface so a create,
 * a rename and a delete can be entered in a JVM test with a stand-in, which reaching them through
 * the repository, its eleven DAOs and a warm JMAP session made impossible.
 */
interface MailFolderHost {

    /** The account's JMAP session, from the repository's cache when it is warm. */
    suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession

    /** Drop the in-memory sync bookkeeping, the warm JMAP session included. */
    fun resetSyncState()
}

/**
 * The folders themselves: creating one, renaming it, deleting it with everything under it, and
 * re-listing an account's folder tree into the cache (#174). Reached as [MailRepository.folders].
 *
 * The four paths that resolve or create a role folder stay in the repository: each writes the role
 * map of the cached JMAP session, and owning that cache is the repository's, not this class's.
 */
class MailFolders(
    private val client: JmapClient,
    private val imap: ImapMailService,
    private val mailboxDao: MailboxDao,
    private val emailDao: EmailDao,
    private val accountStore: AccountStorage,
    private val host: MailFolderHost,
) {

    /** Create a folder, then refresh the cache. [name] and [parentId] are both Unicode, an IMAP folder
     *  id is its path decoded from modified UTF-7 (#101), so the encoding happens at the socket. */
    suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String? = null) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // IMAP nests by path; the parent's id is its full path.
            val path = if (parentId.isNullOrEmpty()) {
                name.trim()
            } else {
                val delim = if (parentId.contains('/')) "/" else if (parentId.contains('.')) "." else "/"
                "$parentId$delim${name.trim()}"
            }
            imap.createFolder(credentials, path)
        } else {
            val ctx = host.jmapSession(credentials)
            client.createMailbox(ctx.session, ctx.accountId, name.trim(), role = null, ctx.auth, parentId = parentId)
        }
        refreshMailboxes(credentials)
    }

    /** Rename a folder, then refresh; returns its id. IMAP ids are paths, so the per-folder registries
     *  are re-keyed to follow: the watch flags (#16), the drawer's fold choices (#185), the hidden
     *  folders (#207), the folder each special role is pinned to (#227). */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            val delim = imap.listImapFolders(credentials).firstOrNull { it.path == mailboxId }?.delimiter
                ?: if (mailboxId.contains('/')) "/" else if (mailboxId.contains('.')) "." else "/"
            val parent = mailboxId.substringBeforeLast(delim, "")
            val newPath = if (parent.isEmpty()) newName.trim() else "$parent$delim${newName.trim()}"
            imap.renameFolder(credentials, mailboxId, newPath)
            accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)
            accountStore.replaceCollapsedFolder(credentials.id, mailboxId, newPath, delim)
            accountStore.replaceHiddenFolder(credentials.id, mailboxId, newPath, delim)
            accountStore.replaceSpecialFolder(credentials.id, mailboxId, newPath, delim)
            // The listing decides each cached role from the choices the account object carries,
            // and the caller's were read before `replaceSpecialFolder` re-keyed them: they name
            // the path the rename left behind, which the server no longer lists, so
            // `honouredSpecialFolders` hands the role back to the automatically elected folder
            // and the drawer paints it on a folder the mail no longer reaches. Falling back on
            // the caller's object keeps the refresh owed when the record cannot be read, and
            // repaints that same stale role: there is no fresher source of the choices then.
            refreshMailboxes(accountStore.credentials(credentials.id) ?: credentials)
            return newPath
        }
        val ctx = host.jmapSession(credentials)
        client.renameMailbox(ctx.session, ctx.accountId, mailboxId, newName.trim(), ctx.auth)
        refreshMailboxes(credentials)
        return mailboxId
    }

    /** Delete a folder and its subfolders, deepest first (servers refuse to destroy a parent that still
     *  has children), plus their cached messages and watch flags. Children match on Unicode paths (#101). */
    suspend fun deleteFolder(credentials: AccountCredentials, mailboxId: String): List<String> {
        val targets: List<String>
        if (credentials.protocol == MailProtocol.IMAP) {
            val folders = imap.listImapFolders(credentials)
            // The folder's own delimiter from LIST: a guess breaks on names containing '/' or '.'.
            val delim = folders.firstOrNull { it.path == mailboxId }?.delimiter ?: "/"
            targets = folders.map { it.path }
                .filter { it == mailboxId || it.startsWith(mailboxId + delim) }
                .sortedByDescending { it.length }
                .ifEmpty { listOf(mailboxId) } // already gone server-side: still clean up locally
            targets.forEach { runCatching { imap.deleteFolder(credentials, it) } }
        } else {
            val ctx = host.jmapSession(credentials)
            val childrenOf = client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).groupBy { it.parentId }
            val ordered = mutableListOf<String>()
            fun visit(id: String) { // post-order: children before their parent
                childrenOf[id].orEmpty().forEach { visit(it.id) }
                ordered += id
            }
            visit(mailboxId)
            targets = ordered
            targets.forEach { client.deleteMailbox(ctx.session, ctx.accountId, it, ctx.auth) }
        }
        targets.forEach {
            accountStore.setFolderWatched(credentials.id, it, watched = false)
            emailDao.replaceMailbox(credentials.id, it, emptyList())
        }
        // A hide key left on a destroyed path makes a folder later created at that path be born
        // hidden, which nothing on screen can explain.
        accountStore.forgetHiddenFolders(credentials.id, targets)
        // And a role key left on one would make that folder be born the bin: deletes would land in
        // it, it would drop out of every search, and emptying the trash would destroy the mail the
        // user filed there. A folder merely missing from a listing keeps its role, it may be back;
        // this one she destroyed here.
        accountStore.forgetSpecialFolders(credentials.id, targets)
        refreshMailboxes(credentials)
        return targets
    }

    /**
     * Pin [folderId] as the folder carrying [role] on [accountId], or hand the role back to the
     * automatic rule with null, and make the folder cache say so at once ([specialFolderRoleDelta]):
     * the drawer, the search and the delete read the cached role, so a choice left to the next
     * network refresh is a setting the app visibly ignores.
     *
     * The record is read before the write: which folder a change of choice lets go is the one the
     * record named, and whether its folder ids are paths is what tells the inbox apart there. It
     * also names the roles this folder is taken away from, which `withSpecialFolder` drops from the
     * record at the same time: a folder carries one pinned role, and the cache has to be made to
     * agree here or the two would decide different things about the same folder. Then the whole
     * in-memory sync bookkeeping goes, every folder of every account included, because the warm
     * JMAP session inside it carries a role table built from the choice just replaced.
     */
    suspend fun setSpecialFolder(accountId: String, role: String, folderId: String?) {
        val account = accountStore.accounts().firstOrNull { it.id == accountId }
        val idsArePaths = account?.protocol == MailProtocol.IMAP
        val released = if (folderId == null) {
            emptyList()
        } else {
            account?.specialFolders.orEmpty().filterKeys { it != role }.filterValues { it == folderId }.keys.toList()
        }
        accountStore.setSpecialFolder(accountId, role, folderId)
        // The release runs first and the pin below reads the rows again rather than sharing its
        // reading: the two are equivalent as written, a pin being insensitive to the role the row
        // it takes carried, and the fresh reading is what keeps them equivalent if the order is
        // ever turned around.
        released.forEach { lost ->
            mailboxDao.applyRoles(
                accountId,
                specialFolderRoleDelta(
                    rows = mailboxDao.rolesForAccount(accountId),
                    role = lost,
                    previousChoice = folderId,
                    choice = null,
                    idsArePaths = idsArePaths,
                ),
            )
        }
        val delta = specialFolderRoleDelta(
            rows = mailboxDao.rolesForAccount(accountId),
            role = role,
            previousChoice = account?.specialFolders?.get(role),
            choice = folderId,
            idsArePaths = idsArePaths,
        )
        mailboxDao.applyRoles(accountId, delta)
        host.resetSyncState()
        // Then ask the server for the listing again, because the delta is all the cache can prove:
        // only `trash` and `junk` leave a lost claim behind, so a `sent`, `drafts` or `archive`
        // handed back to the automatic rule is left with no holder at all until a listing elects
        // one, and the drawer loses the entry. Best effort, and the degradation is real: offline it
        // fails here, the choice stands either way, and the role comes back at the next sync.
        val credentials = accountStore.credentials(accountId) ?: return
        runCatching { refreshMailboxes(credentials) }
    }

    /** Drop folders from the local cache only (a folder delete's undo window); a refresh restores them. */
    suspend fun hideMailboxesLocally(accountId: String, mailboxIds: List<String>) =
        mailboxDao.deleteByIds(accountId, mailboxIds)

    /** Re-fetch one account's folder list under a stated [onlySubscribed] (#174). The value is an
     *  argument, not a second reading of the store: `LSUB` only goes out on a listing that asked. */
    suspend fun refreshFolderList(accountId: String, onlySubscribed: Boolean) {
        val credentials = accountStore.credentials(accountId) ?: return
        refreshMailboxes(credentials, onlySubscribed)
    }

    suspend fun refreshMailboxes(credentials: AccountCredentials) =
        refreshMailboxes(credentials, accountStore.showOnlySubscribedFolders(credentials.id))

    /** [refreshMailboxes] for a caller that already knows what to ask the server about (#174). */
    private suspend fun refreshMailboxes(credentials: AccountCredentials, onlySubscribed: Boolean) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Empty `onPage` and null `cachedPreviewsFor`: this caller caches no message. Still SELECTs the inbox.
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)
            mailboxDao.replaceAll(credentials.id, load.mailboxes)
            return
        }
        val ctx = host.jmapSession(credentials)
        mailboxDao.replaceAll(credentials.id, client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).toEntities(credentials))
    }
}
