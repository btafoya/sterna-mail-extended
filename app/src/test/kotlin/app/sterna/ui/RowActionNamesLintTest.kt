package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source and resource lint, not a behaviour test. A button repeated on every row of a list must be
 * spoken with what its row is about: five "Remove, button" in a row tell a screen reader's user
 * nothing about which line each one acts on. No decision is taken at these sites, each one hands
 * the text its row already shows to a string that takes it, so there is no pure function to run;
 * what is pinned is that the parameterised string is the one written, with the row's own text.
 *
 * Every line of a file naming one of the listed keys is compared whole, the bare key included:
 * a site that goes back to the string without a parameter adds or changes a line and fails.
 *
 * Holes (`CONTRIBUTING.md` § Tests) that apply here:
 *  1. `attachmentName`, `subject`, `senderName`, `displayName`, `removeName` and the three
 *     outbox names are names. Their declarations are pinned next to their uses where this change
 *     wrote them; `name` (folder screen) and `addr.display()` (participants) are older code,
 *     trusted as the text their rows show.
 *  3. A pinned line says nothing about where it sits: that the outbox `semantics` modifier is on
 *     the button of the same verb is held by writing action and name on one line; that
 *     `settings_vacation_clear_start` goes to the "from" row is only the order of the list below.
 *  4. Nothing here hears what a screen reader says. That a `contentDescription` set on a
 *     `TextButton` is read instead of its visible text, and not after it, is a property of the
 *     platform no JVM test can hold.
 *
 * Not covered: the rows of screens outside this list, and buttons that have no name at all.
 */
class RowActionNamesLintTest {

    @Test fun `removing an attachment names the file`() {
        assertEquals(
            "the cross of an attachment row must be spoken with the name the row shows",
            listOf(
                "val attachmentName = att.name ?: stringResource(R.string.compose_attachment_fallback)",
                "text = attachmentName,",
                "contentDescription = stringResource(R.string.a11y_remove_named, attachmentName),",
            ),
            linesOf(COMPOSE) { "attachmentName" in it || it.names("compose_remove", "a11y_remove_named") },
        )
    }

    @Test fun `the options of a folder name the folder`() {
        assertEquals(
            "the folder screen's menu button must be spoken with the name its section shows, the " +
                "same `name` the section takes as its title",
            listOf(
                "title = name,",
                "name = name,",
                "Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_folder_options_for, name))",
            ),
            linesOf(FOLDERS) {
                it == "title = name," || it == "name = name," || it.names("inbox_folder_options", "inbox_folder_options_for")
            },
        )
    }

    @Test fun `the two buttons of a snoozed row name the message`() {
        assertEquals(
            listOf(
                "val subject = item.subject.orEmpty().ifBlank { stringResource(R.string.message_no_subject) }",
                "subject,",
                "Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.snoozed_reschedule_for, subject))",
                "Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.snoozed_cancel_for, subject))",
            ),
            linesOf(SNOOZED) {
                it.startsWith("val subject") || it == "subject," ||
                    it.names("snoozed_reschedule", "snoozed_cancel", "snoozed_reschedule_for", "snoozed_cancel_for")
            },
        )
    }

    @Test fun `the three buttons of an outbox row name the message and keep their visible word`() {
        assertEquals(
            listOf(
                "val subject = item.subject.ifBlank { stringResource(R.string.message_no_subject) }",
                "subject,",
                "val retryName = stringResource(R.string.outbox_retry_for, subject)",
                "TextButton(onClick = { viewModel.retry(item.id) }, modifier = Modifier.semantics { contentDescription = retryName }) {",
                "Text(stringResource(R.string.outbox_retry))",
                "val editName = stringResource(R.string.outbox_edit_for, subject)",
                "TextButton(onClick = { viewModel.edit(item.id) }, modifier = Modifier.semantics { contentDescription = editName }) {",
                "Text(stringResource(R.string.outbox_edit))",
                "val deleteName = stringResource(R.string.outbox_delete_for, subject)",
                "TextButton(onClick = { pendingDelete = item.id }, modifier = Modifier.semantics { contentDescription = deleteName }) {",
                "stringResource(R.string.outbox_delete),",
            ),
            linesOf(OUTBOX) {
                it.startsWith("val subject") || it == "subject," ||
                    "viewModel.retry(" in it || "viewModel.edit(" in it || "pendingDelete = item.id" in it ||
                    it.names(
                        "outbox_retry", "outbox_edit", "outbox_delete",
                        "outbox_retry_for", "outbox_edit_for", "outbox_delete_for",
                    )
            },
        )
    }

    @Test fun `the menu of a sender row names the sender`() {
        assertEquals(
            listOf(
                "val senderName = row.name?.takeIf { it.isNotBlank() } ?: row.email",
                "senderName,",
                "Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.a11y_more_actions_for, senderName))",
            ),
            linesOf(BY_SENDER) { "senderName" in it || it.names("inbox_more", "a11y_more_actions_for") },
        )
    }

    @Test fun `the two buttons of a participant row name the person`() {
        assertEquals(
            listOf(
                "contentDescription = stringResource(R.string.a11y_add_to_contacts_named, addr.display()),",
                "contentDescription = stringResource(R.string.a11y_more_actions_for, addr.display()),",
            ),
            linesOf(MESSAGE) {
                it.names(
                    "participant_add_to_contacts", "a11y_participant_more",
                    "a11y_add_to_contacts_named", "a11y_more_actions_for",
                )
            },
        )
    }

    @Test fun `removing an identity names its address and keeps the visible word`() {
        assertEquals(
            "an identity with no address yet is the row headed settings_identity_new",
            listOf(
                "val removeName = stringResource(R.string.a11y_remove_named, trimmedEmail.ifBlank { stringResource(R.string.settings_identity_new) })",
                "modifier = Modifier.semantics { contentDescription = removeName },",
                "stringResource(R.string.settings_remove),",
            ),
            linesOf(SETTINGS) { "removeName" in it || it.names("settings_remove", "a11y_remove_named") },
        )
    }

    @Test fun `each of the two vacation dates is cleared under its own name`() {
        assertEquals(
            listOf(
                "label = stringResource(R.string.settings_vacation_from_label),",
                "clearLabel = stringResource(R.string.settings_vacation_clear_start),",
                "label = stringResource(R.string.settings_vacation_to_label),",
                "clearLabel = stringResource(R.string.settings_vacation_clear_end),",
                "private fun VacationDateRow(label: String, clearLabel: String, millis: Long?, onPick: (Long?) -> Unit) {",
                "Icon(Icons.Filled.Close, contentDescription = clearLabel)",
            ),
            linesOf(SETTINGS) {
                "clearLabel" in it ||
                    it.names(
                        "settings_vacation_from_label", "settings_vacation_to_label", "settings_vacation_clear_date",
                        "settings_vacation_clear_start", "settings_vacation_clear_end",
                    )
            },
        )
    }

    /**
     * `TranslationParityTest` already holds parity and placeholders against `values/`; it would
     * stay green on a `values/` entry written without its `%1$s`, and on a translation pasted
     * from the English.
     */
    @Test fun `every new name exists once in the eleven languages, with its argument, translated`() {
        val wrong = mutableListOf<String>()
        val english = textsOf("values")
        (listOf("values") + LANGUAGES.map { "values-$it" }).forEach { dir ->
            val texts = textsOf(dir)
            NAMED.forEach { (key, takesArgument) ->
                val found = texts.filter { it.first == key }.map { it.second }
                when {
                    found.size != 1 -> wrong += "$dir/$key: ${found.size} entries"
                    ("%1\$s" in found[0]) != takesArgument -> wrong += "$dir/$key: argument in '${found[0]}'"
                    dir != "values" && found[0] == english.firstOrNull { it.first == key }?.second ->
                        wrong += "$dir/$key: left in English"
                }
            }
            RETIRED.filter { key -> texts.any { it.first == key } }.forEach { wrong += "$dir/$it: no caller left" }
        }
        assertEquals("row action names missing, malformed or orphaned", emptyList<String>(), wrong)
    }

    private fun String.names(vararg keys: String): Boolean =
        keys.any { Regex("""R\.string\.$it\b""").containsMatchIn(this) }

    private fun linesOf(path: String, keep: (String) -> Boolean): List<String> =
        File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            .filter(keep)

    private fun textsOf(dir: String): List<Pair<String, String>> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(root, "app/src/main/res/$dir/strings.xml").readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, COMPOSE).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
    }

    private companion object {
        const val UI = "app/src/main/kotlin/app/sterna/ui"
        const val COMPOSE = "$UI/compose/ComposeScreen.kt"
        const val FOLDERS = "$UI/settings/FolderManagementScreen.kt"
        const val SNOOZED = "$UI/snoozed/SnoozedScreen.kt"
        const val OUTBOX = "$UI/outbox/OutboxScreen.kt"
        const val BY_SENDER = "$UI/sender/MailBySenderScreen.kt"
        const val MESSAGE = "$UI/message/MessageScreen.kt"
        const val SETTINGS = "$UI/settings/SettingsScreen.kt"

        val LANGUAGES = listOf("de", "es", "fr", "hu", "it", "nl", "pl", "pt", "ru", "zh-rCN")

        /** Key to whether it takes the row's text as `%1$s`. */
        val NAMED = mapOf(
            "a11y_remove_named" to true,
            "a11y_more_actions_for" to true,
            "a11y_add_to_contacts_named" to true,
            "inbox_folder_options_for" to true,
            "snoozed_reschedule_for" to true,
            "snoozed_cancel_for" to true,
            "outbox_retry_for" to true,
            "outbox_edit_for" to true,
            "outbox_delete_for" to true,
            "settings_vacation_clear_start" to false,
            "settings_vacation_clear_end" to false,
        )

        val RETIRED = listOf(
            "inbox_folder_options",
            "snoozed_reschedule",
            "snoozed_cancel",
            "participant_add_to_contacts",
            "a11y_participant_more",
        )
    }
}
