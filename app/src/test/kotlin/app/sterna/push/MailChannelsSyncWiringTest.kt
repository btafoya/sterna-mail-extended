package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint: `MailChannels.sync` needs a real `NotificationManager`, and its callers are an
 * `Application` and a ViewModel. `MailChannelReconcileTest` runs the decision. This file holds
 * only that it is called, with which arguments, and what `sync` hands `reconcile`.
 * Holes (CONTRIBUTING.md): 1, `appContext`, `app` and `store` are held by name only; 3, beyond
 * the neighbour lines pinned below, nothing says a call is reached.
 */
class MailChannelsSyncWiringTest {

    @Test fun `the channels are reconciled at every start`() {
        val lines = codeLinesNaming(APPLICATION.readText(), "")
        val at = lines.indexOf("runCatching { app.sterna.push.MailChannels.sync(appContext, accountStore) }")
        assertTrue(
            "the start-up reconcile is gone. It is the only call that deletes the shared channel " +
                "of older builds and creates the channel of an account no Save has touched since " +
                "the update: without it, those accounts keep ringing through Android's defaults",
            at > 0,
        )
        assertEquals(
            "…and it runs off the main thread, in the application scope, where a failure is logged " +
                "and retried at the next start instead of crashing the process",
            listOf(
                "appScope.launch {",
                "runCatching { app.sterna.push.MailChannels.sync(appContext, accountStore) }",
                ".onFailure { Log.w(\"Sterna\", \"notification channels not reconciled; retried next start\", it) }",
            ),
            lines.subList(at - 1, at + 2),
        )
    }

    @Test fun `a pruned sub-account loses its channel with its baselines`() {
        val lines = codeLinesNaming(APPLICATION.readText(), "")
        val at = lines.indexOf("mailRepository.accounts.onAccountPruned = {")
        assertTrue("the `onAccountPruned` hook is gone from SternaApplication.kt", at >= 0)
        assertEquals(
            "a linked sub-account the server stopped listing is removed from the store here, with " +
                "no sign-out and no Save to reconcile after it: without this line its channel " +
                "stays in Android's settings for good, named after an account that is gone",
            listOf(
                "mailRepository.accounts.onAccountPruned = {",
                "app.sterna.push.NewMailNotifier.clear(appContext, it)",
                "app.sterna.push.MailChannels.sync(appContext, accountStore)",
                "}",
            ),
            lines.subList(at, at + 4),
        )
    }

    @Test fun `a sign-out deletes the channel after the banners are cancelled`() {
        val body = codeLinesNaming(bodyOf(VIEW_MODEL, "signOut"), "")
        assertEquals(
            "signOut must reconcile once, on the store it has just written, and right after the " +
                "banners are cancelled: the account's channel otherwise outlives it in Android's " +
                "settings, and deleting it any earlier takes down banners the cancel line owns",
            listOf(
                "removed.forEach { Notifications.cancelAccount(app, it) }",
                "MailChannels.sync(app, store)",
            ),
            body.filter { "MailChannels.sync(" in it || "Notifications.cancelAccount(" in it },
        )
        val cancelAt = body.indexOf("removed.forEach { Notifications.cancelAccount(app, it) }")
        assertEquals(
            "…and nothing stands between the two lines",
            "MailChannels.sync(app, store)",
            body.getOrNull(cancelAt + 1),
        )
    }

    @Test fun `a save renames the account's channel`() {
        assertEquals(
            "save writes the account's name, and the channel carries it: without this call the " +
                "channel keeps the old name in Android's settings until the next start",
            listOf("MailChannels.sync(getApplication<Application>(), store)"),
            codeLinesNaming(bodyOf(VIEW_MODEL, "save"), "MailChannels.sync("),
        )
    }

    @Test fun `sync hands reconcile the real guard and the real shade`() {
        assertEquals(
            "`MailChannelReconcileTest` injects its own lambdas, so only this pin holds what the " +
                "real ones are. `unreadable` must be `store.accountsUnreadable()`: an account " +
                "list that failed to decode reads as empty, and a constant false there deletes " +
                "every account's channel with what the user set on it. `anyActiveOn` must read " +
                "the shade by `channelId`: a constant false deletes the shared channel of older " +
                "builds while a send failure it carries may hold the only copy of a text",
            listOf(
                "val manager = context.getSystemService(NotificationManager::class.java)",
                "val shelf = object : MailChannelShelf {",
                "override fun channelIds(): List<String> = manager.notificationChannels.map { it.id }",
                "override fun anyActiveOn(channelId: String): Boolean =",
                "manager.activeNotifications.any { it.notification.channelId == channelId }",
                "override fun upsert(accountId: String, accountLabel: String) =",
                "Notifications.ensureMailChannel(context, accountId, accountLabel)",
                "override fun delete(channelId: String) = manager.deleteNotificationChannel(channelId)",
                "}",
                "reconcile(shelf, accounts = { store.accounts() }, unreadable = { store.accountsUnreadable() })",
            ),
            codeLinesNaming(bodyOf(MAIL_CHANNELS, "sync"), "").drop(1).dropLast(1),
        )
    }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block body of the first `fun [name]` in [file], braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name', did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    private companion object {
        const val MAIL_CHANNELS_PATH = "app/src/main/kotlin/app/sterna/push/MailChannels.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MAIL_CHANNELS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath}: this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val MAIL_CHANNELS: File by lazy { File(root, MAIL_CHANNELS_PATH) }
        val APPLICATION: File by lazy { File(root, "app/src/main/kotlin/app/sterna/SternaApplication.kt") }
        val VIEW_MODEL: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt") }
    }
}
