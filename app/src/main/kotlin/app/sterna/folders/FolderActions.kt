package app.sterna.folders

import android.content.Context
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.mail.MailFolders
import app.sterna.core.jmap.model.Mailbox
import app.sterna.push.NewMailNotifier

/**
 * What [FolderActions] needs from the app, and nothing more: an interface so a create, a rename and
 * a delete can be run in a JVM test, where `NewMailNotifier` and WorkManager cannot.
 */
internal interface FolderActionsHost {
    fun credentials(accountId: String): AccountCredentials?
    suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String?)

    /** Returns the folder's id after the rename, a new one on IMAP where the id is the path. */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String

    /** Moves the new-mail baselines of [oldMailboxId] and its subfolders to [newMailboxId]. */
    fun followBaseline(accountId: String, oldMailboxId: String, newMailboxId: String)
    fun setFolderCollapsed(accountId: String, mailboxId: String, collapsed: Boolean)
    suspend fun hideLocally(accountId: String, mailboxIds: List<String>)
    suspend fun relist(credentials: AccountCredentials)
    fun scheduleDelete(accountId: String, mailboxId: String, holdBackMs: Long)
    fun cancelDelete(accountId: String, mailboxId: String)
}

/**
 * Create, rename and delete a folder of a NAMED account. No UI state lives here: each screen keeps
 * its own snackbar and its own undo window, and hands in the account it shows.
 *
 * The per-folder registries an IMAP rename re-keys and the clean-up of a delete stay in
 * [MailFolders]; this class must not do them a second time.
 */
internal class FolderActions(private val host: FolderActionsHost) {

    /** Creates [name] (trimmed) under [parentId], then unfolds the parent so the new child shows.
     *  Throws what the server answered; does nothing for an account with no credentials. */
    suspend fun create(accountId: String, name: String, parentId: String? = null) {
        val credentials = host.credentials(accountId) ?: return
        host.createFolder(credentials, name.trim(), parentId)
        if (parentId != null) host.setFolderCollapsed(accountId, parentId, false)
    }

    /** Renames [mailboxId] to [newName] (trimmed). An IMAP id is a path, so a new id moves the
     *  new-mail baselines with it: left on the old key, the renamed folder renotifies all its mail. */
    suspend fun rename(accountId: String, mailboxId: String, newName: String) {
        val credentials = host.credentials(accountId) ?: return
        val newId = host.renameFolder(credentials, mailboxId, newName.trim())
        if (newId != mailboxId) host.followBaseline(accountId, mailboxId, newId)
    }

    /**
     * Takes the folder and every subfolder off the local list at once, and leaves the server delete
     * to [FolderDeleteWorker] after [holdBackMs]: persisted work, so a confirmed delete is not lost
     * with the screen. False, and nothing done, for an account with no credentials.
     */
    suspend fun delete(accountId: String, deletion: FolderDeletion, holdBackMs: Long): Boolean {
        if (host.credentials(accountId) == null) return false
        host.scheduleDelete(accountId, deletion.mailboxId, holdBackMs)
        host.hideLocally(accountId, deletion.hiddenIds)
        return true
    }

    /** Cancels a delete still inside its hold-back. Nothing was destroyed yet. */
    fun cancelDelete(accountId: String, mailboxId: String) = host.cancelDelete(accountId, mailboxId)

    /** [cancelDelete], then lists the account's folders again to bring the hidden ones back. */
    suspend fun undoDelete(accountId: String, mailboxId: String) {
        host.cancelDelete(accountId, mailboxId)
        val credentials = host.credentials(accountId) ?: return
        host.relist(credentials)
    }

    companion object {
        fun of(context: Context, store: AccountStore, folders: MailFolders): FolderActions = FolderActions(
            object : FolderActionsHost {
                override fun credentials(accountId: String) = store.credentials(accountId)
                override suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String?) =
                    folders.createFolder(credentials, name, parentId)
                override suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String) =
                    folders.renameFolder(credentials, mailboxId, newName)
                override fun followBaseline(accountId: String, oldMailboxId: String, newMailboxId: String) =
                    NewMailNotifier.rename(context, accountId, oldMailboxId, newMailboxId)
                override fun setFolderCollapsed(accountId: String, mailboxId: String, collapsed: Boolean) =
                    store.setFolderCollapsed(accountId, mailboxId, collapsed)
                override suspend fun hideLocally(accountId: String, mailboxIds: List<String>) {
                    folders.hideMailboxesLocally(accountId, mailboxIds)
                }
                override suspend fun relist(credentials: AccountCredentials) {
                    folders.refreshMailboxes(credentials)
                }
                override fun scheduleDelete(accountId: String, mailboxId: String, holdBackMs: Long) =
                    FolderDeleteWorker.schedule(context, accountId, mailboxId, holdBackMs)
                override fun cancelDelete(accountId: String, mailboxId: String) =
                    FolderDeleteWorker.cancel(context, accountId, mailboxId)
            },
        )
    }
}

/** A confirmed folder delete: the folder, the name the undo snackbar prints, and the subfolders the
 *  server delete takes with it. */
internal data class FolderDeletion(val mailboxId: String, val name: String, val subfolderIds: List<String>) {
    /** The second warning, "Also delete subfolders?", is owed exactly when something goes down with it. */
    val asksAboutSubfolders: Boolean get() = subfolderIds.isNotEmpty()
    val hiddenIds: List<String> get() = listOf(mailboxId) + subfolderIds
}

/** The delete of [mailbox], with its subfolders found in [all], the folder list of the SAME account:
 *  IMAP ids are paths, so another account's list answers for a namesake tree. */
internal fun folderDeletion(mailbox: Mailbox, all: List<Mailbox>): FolderDeletion =
    FolderDeletion(mailbox.id, mailbox.name, folderDescendantIds(mailbox.id, all))

/**
 * Subfolders (recursive) of [mailboxId] in [all], by JMAP `parentId` or the IMAP path prefix,
 * mirroring the drawer's tree building. `seen` guards a `parentId` cycle, which a server can answer.
 */
internal fun folderDescendantIds(mailboxId: String, all: List<Mailbox>): List<String> {
    val seen = mutableSetOf(mailboxId)
    val result = mutableListOf<String>()
    fun childrenOf(id: String): List<Mailbox> = all.filter { m ->
        if (m.id == id) return@filter false
        if (m.parentId != null) return@filter m.parentId == id
        val delim = when {
            m.id.contains('/') -> "/"
            m.id.contains('.') -> "."
            else -> return@filter false
        }
        m.id.substringBeforeLast(delim, "") == id
    }
    fun visit(id: String) {
        childrenOf(id).forEach { child ->
            if (seen.add(child.id)) {
                result += child.id
                visit(child.id)
            }
        }
    }
    visit(mailboxId)
    return result
}

/** Whether a folder-name dialog may be confirmed. Nothing else is checked: a duplicate or a forbidden
 *  character is the server's to refuse, and its answer is shown. */
internal fun folderNameMayBeSubmitted(name: String): Boolean = name.isNotBlank()
