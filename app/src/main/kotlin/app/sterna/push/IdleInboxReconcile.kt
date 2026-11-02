package app.sterna.push

import android.app.Application
import android.content.Context
import android.util.Log
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.AccountGoneException
import app.sterna.core.data.mail.accountGoneCause

/**
 * The pull-to-refresh's own read of the inbox, run by `PushService` after the notification pass of
 * an IDLE wake: on IMAP it is the only path that retires a row deleted from another client (#278).
 *
 * After, because the notification pass diffs against its baseline before the rows move; this read
 * writes no baseline. Not folded into `refreshAccountFolders`: its page of fifty cannot say what
 * the window holds, and retiring off it would drop mail still on the server (#134). The inbox is
 * named by id, a null id bounding no eviction (#165).
 */
suspend fun reconcileImapInbox(context: Context, credentials: AccountCredentials) {
    val container = (context.applicationContext as Application).container
    val inboxId = container.mailRepository.cachedInboxMailboxId(credentials) ?: return
    val (limit, pruneBefore) = pullWindowArgs(container.accountStore.syncWindow(credentials.id), System.currentTimeMillis())
    try {
        container.mailRepository.refresh(credentials, inboxId, limit, pruneBefore)
    } catch (gone: AccountGoneException) {
        val cause = accountGoneCause(credentials.id, container.accountStore.accountsUnreadable(), passKind = "inbox reconcile")
        Log.i("PushService", "IDLE wake: $cause, dropped", gone)
    }
}
