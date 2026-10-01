package app.sterna.ui.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint: where "Cancel send" and its answer are wired. The decisions are run by
 * `ScheduledCancelExitTest` and `DraftsLandingTest`; this pins the call sites nothing in a JVM test
 * can execute (two composables, two ViewModels, the NavHost). Holes that apply: 1 (an argument name
 * says nothing about what it is bound to: `row.accountId` is trusted to be the displayed row's),
 * 2, 3 (no line's position in its control structure is checked, nor whether it runs), and 4.
 */
class ScheduledSendScreenWiringTest {

    @Test fun `the button cancels the displayed row, under the account of that row`() {
        assertEquals(
            listOf("onClick = { viewModel.cancel(row.id, row.accountId) },"),
            codeLines(DETAIL).filter { "viewModel.cancel(" in it },
        )
    }

    @Test fun `a cancellation that did not happen keeps the screen, and says so`() {
        assertEquals(
            "the row is still scheduled and still the only copy: leaving here reads as cancelled",
            listOf("ScheduledCancelExit.StayNotCancelled -> scope.launch { snackbarHostState.showSnackbar(text) }"),
            codeLines(DETAIL).filter { "StayNotCancelled" in it },
        )
    }

    @Test fun `the screen closes on a gone row only from a real answer of the table`() {
        val vm = codeLines(VIEW_MODEL)
        assertEquals(
            "an empty initial value reads every row as gone, and every opening closes at once",
            listOf(
                "val rows: StateFlow<List<ScheduledSendEntity>?> = table.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0),",
                "initialValue = null,",
                ")",
            ),
            vm.dropWhile { !it.startsWith("val rows") }.take(5),
        )
        val detail = codeLines(DETAIL)
        assertEquals(
            listOf("val rowGone = rows != null && row == null"),
            detail.filter { it.startsWith("val rowGone") },
        )
        assertEquals(
            "the two guards are the row's own state, read live: a constant reopens the moment where " +
                "our own cancellation deleted the row and the screen closes before its answer",
            listOf(
                "val closes = !leaving &&",
                "scheduledSendClosesAsGone(rowGone, cancelling = id in cancelling, answerPending = outcome?.id == id)",
            ),
            detail.dropWhile { !it.startsWith("val closes") }.take(2),
        )
    }

    @Test fun `the answer is published before the row leaves the cancelling set`() {
        assertEquals(
            "in the other order, there is a moment where the row is gone, not cancelling, and unanswered",
            listOf(
                "_outcome.value = ScheduledCancelOutcome(id, scheduledCancelExit(report, accountId))",
                "} finally {",
                "inFlight.remove(id)",
                "_cancelling.update { it - id }",
                "}",
            ),
            codeLines(VIEW_MODEL).dropWhile { !it.startsWith("_outcome.value = ") }.take(5),
        )
    }

    @Test fun `a parked landing records the switch it was asked after`() {
        val vm = codeLines(INBOX_VIEW_MODEL)
        assertEquals(
            "the store's current account after the switch, and the account the list still shows",
            listOf("draftsLandingFor = draftsLandingRequest(accountId, store.currentId(), currentAccountId.value)"),
            vm.filter { "draftsLandingRequest(" in it },
        )
    }

    @Test fun `the answer is the exit of the report the cancellation returned, for the row's account`() {
        val cancel = codeLines(VIEW_MODEL)
        assertEquals(
            listOf("_outcome.value = ScheduledCancelOutcome(id, scheduledCancelExit(report, accountId))"),
            cancel.filter { "scheduledCancelExit(" in it },
        )
        assertEquals(
            "the account is the tap's, handed by the screen that displays the row",
            listOf("fun cancel(id: Long, accountId: String) {"),
            cancel.filter { it.startsWith("fun cancel(") },
        )
    }

    @Test fun `the list offers no per-row cancel any more, and each row opens its own screen`() {
        val list = codeLines(LIST)
        assertEquals(
            "a cross on the row would cancel with nothing shown of what is cancelled",
            emptyList<String>(),
            list.filter {
                "Icons.Filled.Close" in it || "R.string.scheduled_cancel)" in it || "viewModel.cancel(" in it
            },
        )
        assertEquals(listOf(".clickable { onOpen(item.id) }"), list.filter { it.startsWith(".clickable") })
    }

    @Test fun `the NavHost opens a row by its id and lands on the Drafts of the account it was handed`() {
        val app = codeLines(NAV_HOST)
        assertEquals(
            listOf("onOpen = { id -> entry.navigateOnce { nav.navigate(\"scheduled/\$id\") } },"),
            app.filter { it.startsWith("onOpen = ") },
        )
        assertTrue("no route \"scheduled/{id}\"", app.contains("route = \"scheduled/{id}\","))
        assertEquals(
            "both halves of the landing take the account the answer named, never the current one",
            listOf(
                "onLandOnDrafts = { accountId, message ->",
                "draftsLandingAccountSwitch(accountId, currentAccountId, accounts.map { it.id })",
                "?.let(onSwitchAccount)",
                "inboxViewModel.showDraftsAfterCancel(accountId, message)",
                "leave(\"inbox\")",
            ),
            app.dropWhile { !it.startsWith("onLandOnDrafts") }.take(5),
        )
    }

    @Test fun `the list judges the landing on every folder list, and selects the folder it was given`() {
        val vm = codeLines(INBOX_VIEW_MODEL)
        assertEquals(
            "applyRestoredMeta(folders) must be followed by the landing, inside the folder-list onEach",
            "applyDraftsLanding(folders)",
            vm[vm.indexOf("applyRestoredMeta(folders)") + 1],
        )
        val apply = vm.dropWhile { !it.startsWith("private fun applyDraftsLanding(") }.take(9)
        assertEquals(
            listOf(
                "private fun applyDraftsLanding(folders: List<Mailbox>) {",
                "when (val step = draftsLanding(draftsLandingFor, currentAccountId.value, folders)) {",
                "DraftsLanding.Wait -> Unit",
                "DraftsLanding.Plain -> draftsLandingFor = null",
                "is DraftsLanding.Folder -> {",
                "draftsLandingFor = null",
                "select(step.mailbox)",
                "}",
                "}",
            ),
            apply,
        )
    }

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private companion object {
        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}: this test reads sources as text")

        val DETAIL = repoFile("app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendScreen.kt")
        val LIST = repoFile("app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendsScreen.kt")
        val VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendsViewModel.kt")
        val NAV_HOST = repoFile("app/src/main/kotlin/app/sterna/ui/SternaApp.kt")
        val INBOX_VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
    }
}
