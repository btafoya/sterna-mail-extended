package app.sterna.ui.settings

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `SettingsScreen.kt` as text and proves nothing about
 * what is drawn. It exists because a setting added to a screen and forgotten in [SETTINGS_INDEX]
 * becomes one the search cannot find, and nothing else would say so.
 *
 * It reads the bodies of the screens the search covers, finds every call of a setting row
 * ([ROW_TITLE_PARAMETER]) and takes the `R.string` key its title is written with. Each key must
 * have an index entry for that screen, or an exemption below with its reason. The other way round,
 * an entry whose row, or whose parent row, is no longer drawn where it says fails too. Every
 * `SettingsSection` of those screens must hold such a row, be an entry itself (flashed on its
 * header), or be exempt below: no section is left out of the search without a written reason.
 *
 * Of the four holes `CONTRIBUTING.md` lists, three apply. (1) The title is read as the text of its
 * argument: a title written as `stringResource(if (a) R.string.x else R.string.y)` yields both keys,
 * and nothing here says which one is drawn when; a title with no `R.string` literal at all is
 * refused rather than guessed. (2) and (3) The code around a call is not read: a row inside
 * `if (isImap)` counts as drawn, so whether [SettingEntry.condition] copies the screen's condition
 * is checked by nobody here. [SettingsSearchTest] runs the conditions as the index declares them,
 * not as the screens do. Hole (4) does not apply: no value is collected.
 *
 * What it does not see: rows drawn by a composable not in [SCREENS] (`RelayAddressSection`, the
 * identity rows, a screen added later); a trailing `//` comment holding a bracket inside a row
 * call, which would throw the argument split off.
 */
class SettingsIndexGuardTest {

    @Test fun `every setting row a covered screen draws is in the index, or exempt with a reason`() {
        val gaps = missingFromIndex(SETTINGS_INDEX)
        assertEquals(
            "a setting row is drawn but the search cannot find it. Add an entry to SETTINGS_INDEX " +
                "(SettingsIndex.kt) with the same screen, or an exemption in this test with its " +
                "reason:\n" + gaps.joinToString("\n"),
            emptyList<String>(),
            gaps,
        )
    }

    @Test fun `removing an entry from the index is caught`() {
        val gaps = missingFromIndex(SETTINGS_INDEX.filterNot { it.key == "pure_black" })
        assertEquals(listOf("AppearanceScreen: SettingSwitch titled settings_pure_black_title"), gaps)
    }

    @Test fun `every section of a covered screen has a way in, or an exemption with its reason`() {
        val gaps = sectionsWithoutWayIn(SETTINGS_INDEX)
        assertEquals(
            "a section holds no setting row and the search cannot reach it. Add an entry with " +
                "anchorKind = AnchorKind.SECTION, or an exemption in this test with its reason:\n" +
                gaps.joinToString("\n"),
            emptyList<String>(),
            gaps,
        )
    }

    @Test fun `removing a section entry from the index is caught`() {
        val gaps = sectionsWithoutWayIn(SETTINGS_INDEX.filterNot { it.key == "image_allowlist" })
        assertEquals(listOf("PrivacySecurityScreen: section titled settings_image_allowlist_section"), gaps)
    }

    @Test fun `every section exemption still names a drawn section without rows, and none is indexed`() {
        val bare = sections.filter { rowCalls(it.screen, it.body).isEmpty() }.flatMap { it.keys }.toSet()
        val indexed = SETTINGS_INDEX.mapNotNull { it.anchor }.map(::nameOf).toSet()
        assertEquals(emptyList<String>(), EXEMPT_SECTIONS.keys.filter { it !in bare || it in indexed })
    }

    @Test fun `every index entry still has its row where it says`() {
        // Folder management is drawn by FolderManagementScreen, on the app's graph and outside
        // SCREENS; its route is checked by the next test but one.
        val stale = SETTINGS_INDEX.filter { it.host != SettingHost.CurrentAccountFolders }.mapNotNull { entry ->
            val title = nameOf(entry.title)
            val screen = screenOf(entry)
            val drawn = if (entry.anchorKind == AnchorKind.SECTION) sectionTitlesOn(screen) else titlesOn(screen)
            when (val anchor = entry.anchor) {
                null -> if (title in drawn) null else "$title: no hub row carries this rubric"
                entry.title -> if (title in drawn) null else "$title: no row with this title on $screen"
                else -> when {
                    nameOf(anchor) !in drawn -> "$title: its parent ${nameOf(anchor)} is not drawn on $screen"
                    "R.string.$title" !in settingsSource -> "$title: not drawn anywhere in SettingsScreen.kt"
                    else -> null
                }
            }
        }
        assertEquals(
            "index entries that lead to a screen without their row:\n" + stale.joinToString("\n"),
            emptyList<String>(),
            stale,
        )
    }

    @Test fun `every exemption still names a drawn row, and none of them is indexed`() {
        val drawn = rows.flatMap { it.keys }.toSet()
        val indexed = SETTINGS_INDEX.map { nameOf(it.title) }.toSet()
        assertEquals(emptyList<String>(), EXEMPT.keys.filter { it !in drawn || it in indexed })
    }

    @Test fun `every row title is written as a string resource at its call site`() {
        val unread = rows.filter { it.keys.isEmpty() }.map { "${it.screen}: ${it.component}(${it.titleExpression})" }
        assertEquals(
            "a setting row whose title carries no R.string literal cannot be checked against the " +
                "index. Write the title as stringResource(R.string.…) at the call site:\n" +
                unread.joinToString("\n"),
            emptyList<String>(),
            unread,
        )
    }

    @Test fun `a title the lint cannot read is reported, not skipped`() {
        val found = rowCalls(
            "Probe",
            """
            SettingSwitch(
                title = title,
                subtitle = stringResource(R.string.settings_pure_black_subtitle),
            )
            SettingsCategoryRow(Icons.Filled.Info, stringResource(R.string.settings_about_version), "x") { }
            SettingTextField(
                label = stringResource(if (token) R.string.settings_api_token_label else R.string.settings_password_label),
                value = password,
            )
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                emptyList(),
                listOf("settings_about_version"),
                listOf("settings_api_token_label", "settings_password_label"),
            ),
            found.map { it.keys },
        )
    }

    @Test fun `every screen a result flashes a row on declares the focus argument`() {
        val focusable = Regex("""composable\(focusable\("([^"]+)"\)""").findAll(settingsSource).map { it.groupValues[1] }.toSet()
        val plain = Regex("""composable\("([^"]+)"\)""").findAll(settingsSource).map { it.groupValues[1] }.toSet()
        val missing = SETTINGS_INDEX.mapNotNull { entry ->
            val routes = when (val host = entry.host) {
                is SettingHost.Screen -> listOf(host.route)
                SettingHost.CurrentAccount -> listOf("account/{id}", "sharedAccount/{id}")
                SettingHost.CurrentAccountFolders -> emptyList()
            }
            val declared = if (entry.anchor == null) focusable + plain else focusable
            routes.filter { it !in declared }.takeIf { it.isNotEmpty() }?.let { "${entry.key}: $it" }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `the folder management a result opens is a destination of the app graph`() {
        val app = File(root, APP_PATH).readText()
        assertTrue(
            "SternaApp.kt no longer declares the route searchResultRoute builds for folder management",
            "route = \"folders?accountId={accountId}\"" in app,
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    private data class RowCall(val screen: String, val component: String, val titleExpression: String, val keys: List<String>)

    private data class SectionCall(val screen: String, val keys: List<String>, val body: String)

    /** The sections that hold no row and are neither an entry nor exempt, one line per section. */
    private fun sectionsWithoutWayIn(index: List<SettingEntry>): List<String> = sections.mapNotNull { section ->
        val key = section.keys.singleOrNull()
        val indexed = index.any { entry ->
            entry.anchorKind == AnchorKind.SECTION && entry.anchor?.let(::nameOf) == key &&
                screenOf(entry) in screensOf(section.screen)
        }
        when {
            rowCalls(section.screen, section.body).isNotEmpty() -> null
            key != null && (key in EXEMPT_SECTIONS || indexed) -> null
            else -> "${section.screen}: section titled ${section.keys.ifEmpty { listOf("?") }.joinToString()}"
        }
    }

    private fun sectionTitlesOn(screen: String): Set<String> =
        sections.filter { it.screen == screen || (screen == ACCOUNT && SCREENS[it.screen] == ACCOUNT) }
            .flatMap { it.keys }.toSet()

    private val sections: List<SectionCall> by lazy {
        SCREENS.keys.flatMap { function -> sectionCalls(function, bodyOf(function)) }
    }

    private fun sectionCalls(screen: String, body: String): List<SectionCall> =
        Regex("""\bSettingsSection\(""").findAll(body).map { match ->
            val (args, end) = callAt(body, match.range.last + 1)
            SectionCall(screen, stringKeys(args.getOrElse(0) { "" }), trailingBlock(body, end + 1))
        }.toList()

    /** The `{ … }` block starting at the first non-blank character from [from], or "" if none. */
    private fun trailingBlock(text: String, from: Int): String {
        var open = from
        while (open < text.length && text[open].isWhitespace()) open++
        if (open >= text.length || text[open] != '{') return ""
        var braces = 0
        for (j in open until text.length) {
            when (text[j]) { '{' -> braces++; '}' -> braces-- }
            if (braces == 0) return text.substring(open, j + 1)
        }
        error("unclosed block in SettingsScreen.kt at offset $from")
    }

    /** The `R.string` keys an expression names, when it is a `stringResource(...)` call. */
    private fun stringKeys(expression: String): List<String> =
        if ("stringResource(" in expression) {
            Regex("""R\.string\.(\w+)""").findAll(expression).map { it.groupValues[1] }.toList()
        } else {
            emptyList()
        }

    /** The index entries that a drawn row lacks, one line per row. */
    private fun missingFromIndex(index: List<SettingEntry>): List<String> = rows.flatMap { row ->
        row.keys.filter { key ->
            key !in EXEMPT && index.none { nameOf(it.title) == key && screenOf(it) in screensOf(row.screen) }
        }.map { "${row.screen}: ${row.component} titled $it" }
    }

    /** The screen names an entry's host covers: the account is drawn by two composables. */
    private fun screenOf(entry: SettingEntry): String = when (val host = entry.host) {
        SettingHost.CurrentAccount -> ACCOUNT
        SettingHost.CurrentAccountFolders -> FOLDERS
        is SettingHost.Screen -> if (entry.anchor == null) HUB else SCREENS.entries.single { it.value == host.route }.key
    }

    private fun screensOf(function: String): List<String> =
        if (SCREENS[function] == ACCOUNT) listOf(ACCOUNT) else listOf(function)

    private fun titlesOn(screen: String): Set<String> =
        rows.filter { it.screen == screen || (screen == ACCOUNT && SCREENS[it.screen] == ACCOUNT) }
            .flatMap { it.keys }.toSet()

    private val rows: List<RowCall> by lazy {
        SCREENS.keys.flatMap { function -> rowCalls(function, bodyOf(function)) }
    }

    private fun rowCalls(screen: String, body: String): List<RowCall> =
        Regex("""\b(${ROW_TITLE_PARAMETER.keys.joinToString("|")})\(""").findAll(body).map { match ->
            val component = match.groupValues[1]
            val (args, _) = callAt(body, match.range.last + 1)
            val (name, position) = ROW_TITLE_PARAMETER.getValue(component)
            val named = args.firstOrNull { Regex("""^$name\s*=(?!=)""").containsMatchIn(it) }
            val expression = named?.substringAfter("=")?.trim() ?: args.getOrElse(position) { "" }
            RowCall(screen, component, expression, stringKeys(expression))
        }.toList()

    /** The arguments of the call whose `(` ends just before [from], split at top-level commas, and
     *  the offset of its closing `)`. */
    private fun callAt(text: String, from: Int): Pair<List<String>, Int> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') { current.append(c).append(text[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                current.append(c)
            } else when (c) {
                '"' -> { inString = true; current.append(c) }
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> {
                    if (depth == 0) {
                        if (current.isNotBlank()) args += current.toString().trim()
                        return args to i
                    }
                    depth--
                    current.append(c)
                }
                ',' -> if (depth == 0) { args += current.toString().trim(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
            i++
        }
        error("unclosed call in SettingsScreen.kt at offset $from")
    }

    /** The body of `fun [name](…) { … }`, comment-only lines dropped. */
    private fun bodyOf(name: String): String {
        val code = settingsSource.lines()
            .filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
            .joinToString("\n")
        val declarations = Regex("""\bfun $name\(""").findAll(code).toList()
        assertEquals("expected exactly one 'fun $name(' in SettingsScreen.kt", 1, declarations.size)
        var i = declarations.single().range.last + 1
        var parens = 1
        while (parens > 0) {
            when (code[i]) { '(' -> parens++; ')' -> parens-- }
            i++
        }
        val open = code.indexOf('{', i)
        var braces = 0
        for (j in open until code.length) {
            when (code[j]) { '{' -> braces++; '}' -> braces-- }
            if (braces == 0) return code.substring(open, j + 1)
        }
        error("'fun $name(' is never closed in SettingsScreen.kt")
    }

    private fun nameOf(id: Int): String = stringNames[id] ?: error("no R.string for id $id")

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val settingsSource: String by lazy { File(root, SETTINGS_SCREEN_PATH).readText() }

    private companion object {
        const val HUB = "SettingsHub"
        const val ACCOUNT = "account"
        const val FOLDERS = "FolderManagementScreen"

        /** The composables read, and the screen each one is. The account screen is two of them. */
        val SCREENS = mapOf(
            HUB to HUB,
            "AppearanceScreen" to "appearance",
            "ReadingScreen" to "reading",
            "NotificationsScreen" to "notifications",
            "PrivacySecurityScreen" to "privacy",
            "AccountDetailScreen" to ACCOUNT,
            "PgpAccountSection" to ACCOUNT,
        )

        /** Each row component, and where its title is: the parameter name, else its position. */
        val ROW_TITLE_PARAMETER = mapOf(
            "SettingSwitch" to ("title" to 0),
            "SettingChoiceRow" to ("title" to 0),
            "SettingMultiChoiceRow" to ("title" to 0),
            "SettingTextField" to ("label" to 0),
            "SettingCopyableRow" to ("label" to 0),
            "DeliveryModeOption" to ("title" to 0),
            "TimePickerRow" to ("label" to 0),
            "SettingsCategoryRow" to ("title" to 1),
        )

        /** Rows drawn but deliberately not searchable, each with its reason. */
        val EXEMPT = mapOf(
            // The four About rows open a web page: they are not settings.
            "settings_about_version" to "a link to the releases page",
            "settings_about_source" to "a link to the source",
            "settings_about_license" to "a link to the licence",
            "settings_about_author" to "a link to the author's page",
        )

        /** Sections drawn with no setting row and deliberately not searchable, each with its reason. */
        val EXEMPT_SECTIONS = mapOf(
            "settings_protocol_section" to "names the account's protocol, nothing in it can be changed",
            "settings_pgp_section" to "its rows are drawn by PgpAccountSection, which this lint reads on its own",
            "settings_storage_section" to "a message count and a cache button, not a setting",
        )

        const val APP_PATH = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
