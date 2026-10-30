package app.sterna.ui.message

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: `MessageViewModel` is not instantiable on the JVM and nothing here
 * composes. [LabelSheetTest] runs the decisions; this pins that the reader's menu offers the entry
 * on [showLabelsEntry] and opens the sheet through the holder both hosts hand over, that the sheet
 * writes through `setLabel`, and that `setLabel` shows the change at once, writes on the app scope
 * under a key per (account, message, label), and hands a refusal to [writeLabelOrRevert] with the
 * cached row as the confirmed state and a `show` that puts back both the action bar's copy and the
 * header's. Without an account it shows nothing, so the screen never leaves the confirmed state.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`current`, `credentials`, `loaded` and `active` are only
 * names), 2 (a negation or an `||` written around a pinned call is seen only where the whole line
 * is pinned), 3 (each block is pinned whole, but nothing says it runs, nor in which order against
 * code around it), and 4 (no sheet is drawn: what `accountLabels` and `accountIsJmap` collect is not
 * held by anything here).
 */
class LabelSheetWiringTest {

    @Test fun `the menu offers the entry on the pure decision and opens the sheet for the settled message`() {
        val lines = actions()
        val at = lines.indexOf("if (showLabelsEntry(accountIsJmap, accountLabels)) {")
        assertEquals(
            listOf(
                "if (showLabelsEntry(accountIsJmap, accountLabels)) {",
                "DropdownMenuItem(",
                "text = { Text(stringResource(R.string.message_labels)) },",
                "leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },",
                "onClick = { menuOpen = false; labelSheet.open(active.emailId, active.accountId) },",
                ")",
                "}",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 7, lines.size)),
        )
        assertEquals(1, lines.count { "labelSheet.open(" in it })
        assertEquals(
            listOf(
                "val labelSheet = LocalLabelSheet.current",
                "val labelOpenFor by labelSheet.openFor.collectAsStateWithLifecycle()",
                "val labelPicker = LabelSheetOpen.shows(labelOpenFor, active.emailId, active.accountId)",
                "val accountIsJmap by viewModel.accountIsJmap.collectAsStateWithLifecycle()",
                "val accountLabels by viewModel.accountLabels.collectAsStateWithLifecycle()",
            ),
            lines.filter { it.startsWith("val labelSheet") || it.startsWith("val labelOpenFor") || it.startsWith("val labelPicker") || it.startsWith("val accountIsJmap") || it.startsWith("val accountLabels") },
        )
    }

    @Test fun `the sheet reads the open message's labels and writes each change through setLabel`() {
        val lines = actions()
        val at = lines.indexOf("if (labelPicker && accountIsJmap) {")
        assertEquals(
            listOf(
                "if (labelPicker && accountIsJmap) {",
                "val labelName by labelSheet.name.collectAsStateWithLifecycle()",
                "val listedLabels by labelSheet.listed.collectAsStateWithLifecycle()",
                "val heldLabels = labelsOf(loaded.email.keywords)",
                "val labelRows = sheetLabels(listedLabels, accountLabels, heldLabels)",
                "SideEffect { labelSheet.keep(labelRows) }",
                "LabelSheet(",
                "labels = labelRows,",
                "held = heldLabels,",
                "name = labelName,",
                "onNameChange = labelSheet::type,",
                "onToggle = viewModel::setLabel,",
                "onAdd = { id -> viewModel.setLabel(id, true); labelSheet.type(\"\") },",
                "onDismiss = labelSheet::close,",
                ")",
                "}",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 16, lines.size)),
        )
        assertEquals(1, lines.count { it == "LabelSheet(" })
    }

    @Test fun `the holder is a parameter of the reader, handed over by both hosts from the inbox ViewModel`() {
        val screen = DeclarationSource.kotlinLines(DeclarationSource.file(SCREEN))
        assertEquals(
            listOf(
                "labelSheet: LabelSheetOpen,",
                "CompositionLocalProvider(LocalSignatureSheet provides signatureSheet, LocalMoveSheet provides moveSheet, LocalLabelSheet provides labelSheet, LocalPickInstant provides pickInstant) {",
            ),
            screen.filter { it.startsWith("labelSheet: ") || "LocalLabelSheet provides" in it },
        )
        for (host in listOf(STERNA_APP, READING_PANE)) {
            assertEquals(
                host,
                listOf("labelSheet = inboxViewModel.labelSheet,"),
                DeclarationSource.kotlinLines(DeclarationSource.file(host)).filter { "labelSheet" in it },
            )
        }
        val inbox = DeclarationSource.kotlinLines(DeclarationSource.file(INBOX_VM))
        assertEquals(
            listOf("val labelSheet = LabelSheetOpen({ key -> savedState.get<String>(key) }, { key, value -> savedState[key] = value })"),
            inbox.filter { "LabelSheetOpen(" in it },
        )
        assertEquals(listOf("labelSheet.close()"), inbox.filter { "labelSheet" in it && "LabelSheetOpen(" !in it })
        assertEquals(
            "the sheet is closed by a fresh reading only",
            true,
            "labelSheet.close()" in DeclarationSource.functionBody(DeclarationSource.file(INBOX_VM), "fun startFreshReading()"),
        )
    }

    @Test fun `setLabel shows the change, then writes it on the app scope and takes it back on a refusal`() {
        assertEquals(
            listOf(
                "fun setLabel(id: String, held: Boolean) {",
                "val current = (_state.value as? MessageState.Loaded)?.email ?: return",
                "if ((id in labelsOf(current.keywords)) == held) return",
                "val credentials = credentials() ?: return",
                "showLabel(current.id, id, held)",
                "ReaderLabelWrites.submit(appScope, Triple(credentials.id, current.id, id), held) { wanted ->",
                "writeLabelOrRevert(",
                "wanted,",
                "write = { repo.setLabel(credentials, current.id, id, it) },",
                "confirmed = { repo.cachedLabel(credentials.id, current.id, id) },",
                "show = { shown -> showLabel(current.id, id, shown) },",
                "report = { t ->",
                "val app = getApplication<Application>()",
                "_actionStatus.value = app.getString(actionFailureMessage(t, online = hasUsableNetwork(app)))",
                "},",
                ")",
                "}",
            ),
            DeclarationSource.functionBody(DeclarationSource.file(VM), "fun setLabel("),
        )
    }

    @Test fun `showLabel gives the action bar's copy and the header the same value`() {
        assertEquals(
            listOf(
                "private fun showLabel(emailId: String, id: String, held: Boolean) {",
                "(_state.value as? MessageState.Loaded)?.email?.takeIf { it.id == emailId }?.let {",
                "_state.value = MessageState.Loaded(it.withLabel(id, held))",
                "}",
                "updateMessage(emailId) { it.copy(header = it.header.withLabel(id, held), body = it.body?.withLabel(id, held)) }",
            ),
            DeclarationSource.functionBody(DeclarationSource.file(VM), "private fun showLabel("),
        )
    }

    @Test fun `the entry's two inputs come from the open message's own account`() {
        val vm = DeclarationSource.kotlinLines(DeclarationSource.file(VM))
        val jmap = vm.indexOf("val accountIsJmap: StateFlow<Boolean> = _ownerAccountId")
        assertEquals(
            listOf(
                "val accountIsJmap: StateFlow<Boolean> = _ownerAccountId",
                ".map { id -> id != null && credentials()?.protocol == MailProtocol.JMAP }",
            ),
            if (jmap < 0) emptyList() else vm.subList(jmap, jmap + 2),
        )
        val labels = vm.indexOf("val accountLabels: StateFlow<List<String>> = _ownerAccountId.flatMapLatest { id ->")
        assertEquals(
            listOf(
                "val accountLabels: StateFlow<List<String>> = _ownerAccountId.flatMapLatest { id ->",
                "if (id == null) flowOf(emptyList()) else repo.observeAccountLabels(id).catch { emit(emptyList()) }",
            ),
            if (labels < 0) emptyList() else vm.subList(labels, labels + 2),
        )
    }

    private fun actions(): List<String> =
        DeclarationSource.functionBody(DeclarationSource.file(SCREEN), "private fun MessageActions(")

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val VM = "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt"
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val READING_PANE = "app/src/main/kotlin/app/sterna/ui/inbox/ReadingPane.kt"
        const val INBOX_VM = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
    }
}
