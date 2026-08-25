package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [UploadSentCopyWiringTest].
 * The decision itself is executed next door in `core:data` (`ShowOnlySubscribedFoldersTest`); what
 * no test in this module can run is Compose code and an `AndroidViewModel` reaching into the app
 * container.
 *
 * Four silent edits it exists to catch:
 *  1. the switch writing only on "Save" (or on nothing at all): this screen has one Save button for
 *     the text fields, and a toggle that waits for it is a toggle the user leaves behind by pressing
 *     back — the state on screen would not be the stored one;
 *  2. the switch seeded from something other than the stored value, so it shows "on" over an
 *     account that is off — and this switch's "on" is the one that HIDES folders;
 *  3. the view model no longer writing through the store, so the switch moves and nothing is kept;
 *  4. ⛔ the row drifting INTO the `if (isImap)` block, where the neighbouring "Upload sent
 *     messages" switch legitimately lives. This setting is about what the FOLDER LISTS show, and
 *     both protocols carry the subscription (IMAP `LSUB`, JMAP `Mailbox.isSubscribed`): inside that
 *     branch it would silently disappear for every JMAP account, which is the app's default
 *     protocol and the reporter's own case (#174).
 */
class ShowOnlySubscribedFoldersWiringTest {

    @Test fun `the switch is seeded from the stored setting`() {
        assertEquals(
            "the 'Show only subscribed folders' switch no longer starts from the account's stored " +
                "value; seeded from anything else it shows a state the account is not in — and " +
                "seeded from `true` it claims folders are hidden on an account that hides none.",
            listOf("var showOnlySubscribedFolders by remember(accountId) { mutableStateOf(account.showOnlySubscribedFolders) }"),
            block(SETTINGS_SCREEN, "var showOnlySubscribedFolders by remember", 1),
        )
    }

    @Test fun `the switch writes at the toggle, not at Save`() {
        assertEquals(
            "the 'Show only subscribed folders' switch changed shape. It must store the new value " +
                "the moment it is toggled: routed through markEdited()/save() instead, the choice " +
                "is lost by leaving the screen with the back gesture.",
            listOf(
                "SettingSwitch(",
                "title = stringResource(R.string.settings_subscribed_folders_title),",
                "subtitle = stringResource(R.string.settings_subscribed_folders_subtitle),",
                "checked = showOnlySubscribedFolders,",
                "onCheckedChange = {",
                "showOnlySubscribedFolders = it",
                "viewModel.setShowOnlySubscribedFolders(accountId, it)",
                "},",
                ")",
            ),
            blockEndingAt(SETTINGS_SCREEN, "title = stringResource(R.string.settings_subscribed_folders_title),", 1, 9),
        )
    }

    @Test fun `the row sits OUTSIDE the IMAP-only block, with the settings both protocols share`() {
        val lines = codeLines(SETTINGS_SCREEN)
        val imapOnlyRow = only(lines, "title = stringResource(R.string.settings_upload_sent_title),")
        val syncSection = only(lines, "SettingsSection(stringResource(R.string.settings_sync_section)) {")
        val pgpSection = only(lines, "SettingsSection(stringResource(R.string.settings_pgp_section)) {")
        val row = only(lines, "title = stringResource(R.string.settings_subscribed_folders_title),")
        assertTrue(
            "the switch must sit in the Sync section (line $syncSection, before the PGP section at " +
                "line $pgpSection), which both protocols reach; it is at line $row. The IMAP-only " +
                "block that holds the 'Upload sent messages' row (line $imapOnlyRow) is not a place " +
                "for it: a JMAP account would never see it, and JMAP carries the subscription too.",
            row in (syncSection + 1) until pgpSection,
        )
        assertTrue(
            "the switch is at line $row, ABOVE the IMAP-only 'Upload sent messages' row (line " +
                "$imapOnlyRow): it has drifted up into the server-settings block, half of which " +
                "only IMAP accounts are shown.",
            row > imapOnlyRow,
        )
    }

    /**
     * ⭐ The store write, then the folder sync that ON owes — and the SCOPE that sync runs on (#174).
     *
     * The decision is executed next door ([app.sterna.core.data.account.subscriptionSyncOnToggle],
     * run against a real IMAP server by `SubscriptionToggleAsksTheServerTest`); what only a source
     * lint can see here is that this view model asks it at all, hands it THIS account and THIS
     * value, and passes its answer through untouched. Drop the launch and the shipped defect is
     * back whole: the box moves, no `LSUB` goes out, nothing is hidden until a pull-to-refresh
     * happens to re-list the folders.
     *
     * ⛔ **`appScope`, never `viewModelScope` — and this line is the fix, not a preference.** This
     * screen is a `NavHost` destination, so its `ViewModelStoreOwner` is the `NavBackStackEntry`:
     * `popBackStack()` clears the store and CANCELS `viewModelScope`. The gesture in the report is
     * exactly tick-then-back, and the sync is a whole IMAP round trip (connect, `LIST`, `LSUB`,
     * `SELECT`, `FETCH`) — cancelled halfway, `mailboxDao.replaceAll` is never reached, nothing is
     * cached, and the only rescue left is the next full listing, i.e. the pull-to-refresh this
     * branch exists to remove. Same reasoning, and the same house pattern, as
     * `ComposeViewModel.releaseLocalDraftEdit` and `ScheduledSendsViewModel.cancel`.
     *
     * ⛔ `runCatching` stays (offline must not take the settings screen down, and the setting is
     * already written one line up) but it may no longer swallow the failure without a word:
     * `onFailure` logs it. A silent catch on a scope that can be cancelled is how this defect hid
     * in the first place.
     */
    @Test fun `the view model stores the value, then syncs the folders on a scope the back gesture cannot kill`() {
        assertEquals(
            "AccountsViewModel.setShowOnlySubscribedFolders changed shape. It must (a) write " +
                "through the store, or the switch moves and the choice is gone at the next cold " +
                "start, (b) ask subscriptionSyncOnToggle what the move owes and carry its answer " +
                "— account id and value — into the folder refresh, or the tick never sends the " +
                "LSUB the whole feature reads, (c) run that refresh on appScope, or the back " +
                "gesture cancels it before a single folder row is written, and (d) leave a trace " +
                "when it fails instead of swallowing it.",
            listOf(
                "fun setShowOnlySubscribedFolders(id: String, enabled: Boolean) {",
                "store.setShowOnlySubscribedFolders(id, enabled)",
                "refresh()",
                "val sync = subscriptionSyncOnToggle(id, enabled) ?: return",
                "appScope.launch {",
                "runCatching { mail.refreshFolderList(sync.accountId, sync.onlySubscribed) }",
                ".onFailure { android.util.Log.w(\"SternaAccounts\", \"the subscribed-folders sync failed; every folder stays in the drawer\", it) }",
                "}",
                "}",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun setShowOnlySubscribedFolders(", 9),
        )
        assertEquals(
            "the folder sync must run on the APP scope. `viewModelScope` here belongs to the " +
                "NavBackStackEntry of the settings destination and dies with the back gesture, " +
                "halfway through the IMAP round trip, before anything is written.",
            listOf("private val appScope = application.container.appScope"),
            block(ACCOUNTS_VIEW_MODEL, "private val appScope =", 1),
        )
    }

    // ── instrument (copied from [UploadSentCopyWiringTest]) ──────────────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix, exact = false)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [count] code lines starting [before] lines above the ONE line equal to [anchor]. */
    private fun blockEndingAt(file: File, anchor: String, before: Int, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, anchor)
        return lines.subList(maxOf(at - before, 0), minOf(at - before + count, lines.size))
    }

    /** The index of the single matching code line; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String, exact: Boolean = true): Int {
        val hits = lines.indices.filter { if (exact) lines[it] == needle else lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines match `$needle` — this lint reads the shipped source and " +
                    "must be taught the new shape rather than left green over something it never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
        val ACCOUNTS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")
        }
    }
}
