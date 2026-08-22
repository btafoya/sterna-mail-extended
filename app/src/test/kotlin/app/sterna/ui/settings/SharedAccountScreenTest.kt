package app.sterna.ui.settings

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The delegated-account settings screen (issue #31): the two pure rules that decide WHICH account
 * the `sharedAccount/{id}` route may show and WHICH route a shortcut opens, plus source lints over
 * the screen they feed and the two entries into the settings graph.
 *
 * The tests at the top EXECUTE [sharedAccountFor] and [settingsRouteFor]; inverting either guard
 * makes them red. The ones below READ source text, which is a last resort and is admitted as one:
 * this
 * module has no Robolectric, a `@Composable` cannot be invoked here, and the wiring of a tap to a
 * view-model call is exactly what the batch is about. What a source lint proves is that the CALL
 * with THOSE ARGUMENTS is written; it proves nothing about what happens on screen, and it breaks on
 * innocent reformatting. That is the price of reading text instead of running it.
 */
class SharedAccountScreenTest {

    private fun login() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun delegated(id: String = "jordan-uuid", name: String = "jordan.lee@example.org") =
        StoredAccount(
            id = id,
            server = "https://mail.example.org",
            // A sub-account carries the LOGIN's address as username; its own is the account name.
            username = "alex.rivera@example.org",
            accountName = name,
            loginId = "login-uuid",
            jmapAccountId = "u",
        )

    @Test fun `a delegated account resolves to itself`() {
        val accounts = listOf(login(), delegated())

        assertSame(accounts[1], sharedAccountFor("jordan-uuid", accounts))
    }

    @Test fun `a login resolves to nothing, so this screen can never write on its row`() {
        // The guard of the whole batch: the two writes below are made with the id this route was
        // opened with. Were a login to resolve here, its colour and its notifications would be set
        // from a screen that says "Shared account", and the drawer's shortcut would have a target
        // it must not have.
        assertNull(sharedAccountFor("login-uuid", listOf(login(), delegated())))
    }

    @Test fun `an unknown id resolves to nothing`() {
        // A revoked share is removed on the next discovery pass while the screen is still open.
        assertNull(sharedAccountFor("gone-uuid", listOf(login(), delegated())))
    }

    /**
     * THE GUARD OF THE BATCH, read as whole statements. The tests above execute [sharedAccountFor];
     * nothing but this pins the fact that the SCREEN asks it. Look the id up here instead
     * (`accounts.firstOrNull { it.id == accountId }`) and the helper becomes dead code while the
     * route opens on a LOGIN: title = the login's address, subtitle "Shared account", and both
     * writes below landing on the login's row.
     */
    @Test fun `the screen resolves its account through the shared-account guard`() {
        assertEquals(
            listOf(
                "val accounts by viewModel.accounts.collectAsStateWithLifecycle()",
                "val account = sharedAccountFor(accountId, accounts)",
                "if (account == null) {",
            ),
            statementsAfter(SCREEN, GUARD_MARKER, 3),
        )
    }

    /**
     * What each row OPENS on: the value the account already holds, never a constant. A switch
     * hard-coded to `true` shows "on" for an already-silent mailbox, and the first tap then writes
     * `false` while looking inert — the user believes she is turning off what was off.
     */
    @Test fun `both rows open on the value the account already holds`() {
        assertEquals(
            listOf(
                "var colorArgb by remember(accountId) { mutableStateOf(account.color) }",
                "var notificationsEnabled by remember(accountId) " +
                    "{ mutableStateOf(account.notificationsEnabled) }",
            ),
            statementsAfter(SCREEN, INITIAL_STATE_MARKER, 2),
        )
    }

    /**
     * Read in ABSENCE over the SCREEN'S BODY — the composable's signature to the end of the file.
     * The pure resolvers above it are left out on purpose: they legitimately look an id up in the
     * list, and they are EXECUTED by the tests at the top of this class.
     *
     * ⛔ Every token here is reachable FROM UI CODE. A token that exists nowhere in the app (the
     * first version of this list forbade `setPassword`, and a store-only method) reads as a
     * guarantee and protects nothing.
     */
    @Test fun `the screen holds none of the writes that belong to a login`() {
        val found = FORBIDDEN.filter { it in screenBody() }

        assertTrue(
            "SharedAccountScreen's body must call none of these, found: $found",
            found.isEmpty(),
        )
    }

    /**
     * The colour section, statement by statement — whole lines, never a substring: a `contains` is
     * blind to any argument ADDED to a line, which is the exact shape the defect would take
     * (`setColor(account.loginKey(), argb)`). The account id the route was opened with is what both
     * writes must carry.
     */
    @Test fun `the colour swatches write the shared account's own id`() {
        assertEquals(
            listOf(
                "Row(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".horizontalScroll(rememberScrollState())",
                ".padding(horizontal = 16.dp, vertical = 8.dp),",
                "horizontalArrangement = Arrangement.spacedBy(12.dp),",
                ") {",
                "ColourSwatch(color = null, selected = colorArgb == null) {",
                "colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()",
                "}",
                "AccountPalette.colors.forEach { swatch ->",
                "val argb = swatch.toArgb()",
                "ColourSwatch(color = swatch, selected = colorArgb == argb) {",
                "colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()",
            ),
            statementsAfter(SCREEN, COLOUR_MARKER, 14),
        )
    }

    /**
     * The notifications section, same reading. Pins the toggle's write AND the whole condition of
     * the "not watched" note with its THREE arguments: `account.isLinked` first, so a delegated
     * account (always linked) never shows a note that would tell it to go and turn on a setting
     * that changes nothing for it.
     */
    @Test fun `the notifications toggle writes the shared account's own id and asks for all three`() {
        assertEquals(
            listOf(
                "SettingSwitch(",
                "title = stringResource(R.string.settings_account_notifications_title),",
                "subtitle = stringResource(R.string.settings_account_notifications_subtitle),",
                "checked = notificationsEnabled,",
                "onCheckedChange = {",
                "notificationsEnabled = it",
                "viewModel.setNotificationsEnabled(accountId, it)",
                "},",
                ")",
                "if (PushController.shouldShowUnwatchedNote(account.isLinked, " +
                    "viewModel.isWatched(accountId), notificationsEnabled)) {",
            ),
            statementsAfter(SCREEN, NOTIFICATIONS_MARKER, 10),
        )
    }

    // ---- the shortcut into the settings graph (volet 2) ----

    @Test fun `a delegated account opens the short shared-mailbox screen`() {
        assertEquals("sharedAccount/jordan-uuid", settingsRouteFor("jordan-uuid", listOf(login(), delegated())))
    }

    @Test fun `a login opens the full account screen`() {
        assertEquals("account/login-uuid", settingsRouteFor("login-uuid", listOf(login(), delegated())))
    }

    @Test fun `a delegated account whose login is gone keeps its own screen`() {
        // isShared only asks for a non-blank loginId, so an orphaned share is still a share. It
        // must NOT fall back on the login editor: that screen writes a credential under
        // [StoredAccount.loginKey], i.e. under an id that names nothing here.
        val orphan = delegated().copy(loginId = "gone-uuid")

        assertEquals("sharedAccount/jordan-uuid", settingsRouteFor("jordan-uuid", listOf(orphan)))
    }

    @Test fun `an id the list does not hold still opens something`() {
        // A dead shortcut is worse than one that opens the account screen and finds it empty.
        val route = settingsRouteFor("gone-uuid", listOf(login(), delegated()))

        assertEquals("account/gone-uuid", route)
    }

    /**
     * SOURCE LINT over the TWO entries into the settings graph, read as whole statements.
     *
     * Why it exists: the graph never re-reads [StoredAccount.isShared] — it only ever sees a route
     * string — so the decision has to be taken at both entries, and the second one is invisible to
     * every executable test in this module. The drawer's deep link IS the start destination (the
     * hub is not composed at all on that path), so `onOpenAccount` is never traversed there;
     * wiring only the tap would send a shared mailbox's chip to the login editor, which is worse
     * than the state this batch fixes.
     *
     * What it does NOT prove: nothing about what renders, nothing about the values passed at run
     * time, nothing about the guard inside `navigateOnce`. It proves that the call is WRITTEN with
     * THOSE arguments, and it breaks on innocent reformatting. Whole statements, never a
     * substring: a `contains` is blind to an argument ADDED to a line.
     */
    @Test fun `both entries into the settings graph ask settingsRouteFor`() {
        assertEquals(
            listOf(
                "val startDestination = remember(initialAccountId) {",
                "if (initialAccountId != null) {",
                "settingsRouteFor(initialAccountId, accountsViewModel.accounts.value)",
                "} else {",
                "\"hub\"",
                "}",
                "}",
            ),
            statementsAfter(SETTINGS, START_DESTINATION_MARKER, 7),
        )
        // The route is COMPUTED above and only handed over here: what the NavHost receives must be
        // that frozen value, not a fresh expression re-evaluated on every recomposition (the graph
        // is keyed on it, and a share revoked while settings are open would rebuild it in place).
        assertEquals(
            listOf("startDestination = startDestination,"),
            statementsAfter(SETTINGS, NAV_HOST_MARKER, 1),
        )
        assertEquals(
            listOf(
                "viewModel = accountsViewModel,",
                "onBack = { entry.navigateOnce { nav.popBackStack() } },",
                "onOpenAccount = { id -> entry.navigateOnce " +
                    "{ nav.navigate(settingsRouteFor(id, accountsViewModel.accounts.value)) } },",
            ),
            statementsAfter(SETTINGS, ACCOUNTS_SCREEN_MARKER, 3),
        )
    }

    /**
     * SOURCE LINT tying each ROUTE STRING to the composable that serves it, and to the argument it
     * reads on the way — the one link in this batch that no executable test in this module can
     * reach, and the one whose two failure modes are both silent.
     *
     * Swap the screen this route opens and the drawer's chip for a shared mailbox lands on the full
     * login editor, password field included, whose save writes under [StoredAccount.loginKey] and
     * overwrites the secret shared by every account grouped under that login. Rename the path
     * argument by one word (`sharedAccount/{accountId}`) and `getString("id")` returns null,
     * `.orEmpty()` makes it blank, the guard resolves to nothing: a blank screen with a back arrow.
     * Both compile, and every other test in the repo stays green through either.
     *
     * Whole statements, never a substring, and the placeholder name is INSIDE the first pinned
     * statement rather than in the marker: renaming it must fail on a diff, not on a missing marker.
     * What this does NOT prove: nothing renders here, no argument is read at run time, and it breaks
     * on innocent reformatting. It proves those calls are WRITTEN, with THOSE arguments.
     */
    @Test fun `the shared route opens the shared screen with the id it carries`() {
        assertEquals(
            listOf(
                "composable(\"sharedAccount/{id}\") { entry ->",
                "val id = entry.arguments?.getString(\"id\").orEmpty()",
                "SharedAccountScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
            ),
            statementsAfter(SETTINGS, SHARED_ROUTE_MARKER, 5),
        )
    }

    /**
     * The BELT on the other route, pinned in BOTH branches (same reading, same limits as above):
     * the condition is the SAME pure function the route strings are built from, never a second
     * wording of it, and inverting it is exactly the mutation that sends a shared mailbox into the
     * login editor while every other test in the repo stays green.
     *
     * Its own test rather than a second assertion next to the shared route's: JUnit stops at the
     * first failing assertion, so a method holding both would have hidden whichever mutation landed
     * second — which is how this pair was measured.
     */
    @Test fun `the account route keeps a shared mailbox off the login editor`() {
        assertEquals(
            listOf(
                "composable(\"account/{id}\") { entry ->",
                "val id = entry.arguments?.getString(\"id\").orEmpty()",
                "val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()",
                "if (sharedAccountFor(id, accounts) != null) {",
                "SharedAccountScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
                "onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },",
                "onAccountsChanged = onAccountsChanged,",
                ")",
                "} else {",
                "AccountDetailScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
            ),
            statementsAfter(SETTINGS, ACCOUNT_ROUTE_MARKER, 14),
        )
    }

    /**
     * The drawer's account chip, same reading: it hands over the id of the account being LOOKED
     * AT, never a login's. Same limits as the lint above — this is text, not behaviour.
     */
    @Test fun `the drawer chip opens the account on screen, shared or not`() {
        assertEquals(
            listOf(
                ".clickable {",
                "onOpenAccountSettings(currentAccountId)",
                "scope.launch { drawerState.close() }",
                "},",
            ),
            statementsAfter(INBOX, ACCOUNT_CHIP_MARKER, 4),
        )
    }

    // ---- the accounts list (volet 3) ----

    /**
     * SOURCE LINT over the accounts list, read as whole statements: the list builds its rows with
     * [StoredAccount.accountsScreenRows] and hands each row's OWN `underLogin` to the row's
     * `indented` flag.
     *
     * Why it exists: `AccountsScreen` is a `@Composable` with a view model, so this module cannot
     * invoke it; the ordering rule itself IS executed, by SharedAccountTest in core:data. What is
     * invisible to every executable test here is that the screen ASKS that rule — filter the list
     * in place again (`accounts.filter { !it.importPending }`) and every core:data test stays green
     * while shared mailboxes lose their rows once more, which is the whole defect of this batch.
     *
     * It also pins the two values a DELEGATED row is drawn with, because `username` is the wrong
     * answer for both and it is a plausible-looking one: a sub-account carries its LOGIN's address
     * there (`StoredAccount.kt`, `AccountStore` copies it at discovery). `email = account.username`
     * printed the login's own address under the shared mailbox's name — the same address twice on
     * two consecutive rows — and `seed = account.username` handed the monogram of a colourless
     * shared mailbox the LOGIN's seed, so the same mailbox came out one colour here and another in
     * the drawer, which derives it from the label (`InboxScreen.kt`). Non-shared rows keep
     * `username` on purpose: those colours are already on screen.
     *
     * What it does NOT prove: nothing about what renders, nothing about the indent actually being
     * drawn, nothing about the tap. It proves those calls are WRITTEN with THOSE arguments, and it
     * breaks on innocent reformatting. `indented = row.underLogin` is pinned as a whole statement
     * for one reason: `indented = true` and `indented = false` are both a working screen with a
     * silent lie in it, and a substring match on "indented" would accept either.
     */
    @Test fun `the accounts list draws the rows the ordering rule gives it`() {
        assertEquals(
            listOf(
                "items(StoredAccount.accountsScreenRows(accounts), key = { it.account.id }) { row ->",
                "val account = row.account",
                "AccountRow(",
                "seed = if (account.isShared) account.label() else account.username,",
                "label = account.label(),",
                "email = if (account.isShared) stringResource(R.string.account_shared) " +
                    "else account.username,",
                "isCurrent = account.id == currentId,",
                "color = accountColorOf(account.color),",
                "indented = row.underLogin,",
            ),
            statementsAfter(SETTINGS, ACCOUNTS_LIST_MARKER, 9),
        )
    }

    /**
     * SOURCE LINT over the INDENT ITSELF, in [AccountRow]'s own file — the other half of the lint
     * above, which only pins that the screen PASSES `indented`.
     *
     * Why it exists: with the subtitle now saying "Shared account" rather than an address, the
     * start padding is the only thing on screen tying a shared mailbox to the login above it, and
     * it is invisible to every test this repo can run — no Robolectric, no Compose test. Flatten it
     * (`start = 16.dp`) and the two rows read as unrelated accounts; SWAP the branches and the
     * screen indents the logins under their own shared mailboxes, which is a confident lie. Both
     * compile, and every other test in the repo stays green through either.
     *
     * Whole statements, never a substring: the condition AND both values live on one line, so a
     * `contains` on "indented" or on "32.dp" would accept the swap. The `.padding(` call is pinned
     * with it so the value cannot drift onto some other modifier.
     *
     * What it does NOT prove: NOTHING about the real rendering. No dp is measured here, no row is
     * laid out, and a change of `AccountRow`'s Row for another container would not be seen. It
     * proves that line is WRITTEN, with those two values and that condition, and it breaks on
     * innocent reformatting.
     */
    @Test fun `a shared mailbox's row is drawn further from the left edge than its login`() {
        assertEquals(
            listOf(
                ".padding(",
                "start = if (indented) 32.dp else 16.dp,",
            ),
            statementsAfter(COMPONENTS, INDENT_MARKER, 2),
        )
    }

    /**
     * The first [count] statements after [marker]: trimmed lines, comments and blanks dropped.
     * Fails LOUDLY when the marker is not there exactly once, so renaming or duplicating a section
     * cannot quietly turn either rule above into a no-op.
     */
    private fun statementsAfter(file: String, marker: String, count: Int): List<String> {
        val lines = source(file).lines()
        val hits = lines.withIndex().filter { it.value.trim() == marker }.map { it.index }
        assertEquals("expected exactly one '$marker' in $file, found $hits", 1, hits.size)
        return lines.drop(hits.single() + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            .take(count)
    }

    /**
     * The composable's own text: from its signature to the end of the file, so the absence lint
     * reads what the SCREEN does, not what the pure helpers above it are allowed to do. Fails
     * loudly when the signature is not there exactly once.
     */
    private fun screenBody(): String {
        val lines = source(SCREEN).lines()
        val hits = lines.withIndex().filter { it.value.trim() == SCREEN_SIGNATURE }.map { it.index }
        assertEquals("expected exactly one '$SCREEN_SIGNATURE' in $SCREEN, found $hits", 1, hits.size)
        return lines.drop(hits.single()).joinToString("\n")
    }

    private fun source(relative: String): String = File(repoRoot, relative).readText()

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/SharedAccountScreen.kt"
        const val SETTINGS = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val INBOX = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val COMPONENTS = "app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"

        /** The composable the absence lint reads, and nothing above it. */
        const val SCREEN_SIGNATURE = "internal fun SharedAccountScreen("

        const val GUARD_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the guard, statement by statement"
        const val INITIAL_STATE_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 what each row opens on"

        const val COLOUR_MARKER =
            "SettingsSection(stringResource(R.string.settings_account_colour_section)) {"
        const val NOTIFICATIONS_MARKER =
            "SettingsSection(stringResource(R.string.settings_account_notifications_section)) {"

        const val INDENT_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the indent, statement by statement"

        const val ACCOUNTS_LIST_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the accounts list, statement by statement"

        /** The line the NavHost's own arguments start after — the first entry is right below it. */
        const val NAV_HOST_MARKER = "navController = nav,"

        const val START_DESTINATION_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the start destination, statement by statement"
        const val ACCOUNT_ROUTE_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the account route, statement by statement"
        const val SHARED_ROUTE_MARKER =
            "// \u26d4 PINNED BY SharedAccountScreenTest \u2014 the shared route, statement by statement"
        const val ACCOUNTS_SCREEN_MARKER = "AccountsScreen("
        const val ACCOUNT_CHIP_MARKER = ".graphicsLayer { translationX = accountOffset.value }"

        /**
         * Doors a SCREEN can open, and what the user loses through each one — half a line apiece.
         */
        val FORBIDDEN = listOf(
            // Writes server, credential and identities under the LOGIN, for every mailbox grouped there.
            "viewModel.save(",
            // Drops this row: the colour and the notification choice made here go with it (#31).
            "viewModel.signOut(",
            // Re-runs the device flow and rewrites the token the whole login group signs in with.
            "viewModel.startAccountOAuth(",
            // Flips this row to BASIC and clears its OAuth material: the mailbox stops connecting.
            "viewModel.switchAccountToAppPassword(",
            // Writes a secret, and a sub-account's secret is the login's (`loginKey()`).
            "updatePassword",
            // The Save button's enablement: its presence means this screen grew a Save button.
            "canSaveAccount",
            // The unsaved-changes exit: nothing is pending here, so it would ask about nothing.
            "SaveChangesDialog",
            // The caption promising a save scope this screen does not have.
            "settings_save_scope",
            // The one shape by which the isShared guard can be short-circuited onto a login's row.
            "accounts.firstOrNull",
        )

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
