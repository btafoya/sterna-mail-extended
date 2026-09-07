package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `SettingsRepository.kt` as text and proves nothing
 * about what runs. Same instrument and disclaimer as [UnreadTintWiringTest], and the same last
 * resort: `restoreBackup` and `snapshotBackup` are suspending members of a class built from a
 * `Context` and a DataStore, this module has no Robolectric and no `androidTest`, and adding one is
 * a dependency decision. `NotificationContentStoredTest` runs the decision itself — the default, the
 * key, the downgrade fallback, the trip through the codec — but it can only prove what the value
 * WOULD be, never that the import path is the one that posts it.
 *
 * Which leaves two lines held by nothing, and one of them writes user data:
 *
 *  1. `restoreBackup` applying something other than what it read. `setNotificationContent(
 *     NotificationContent.BODY_PREVIEW)` there turns the body preview on for anyone who imports a
 *     settings file — a position they never chose, revealing the opening of every message on their
 *     lock screen — and the whole suite stays green. Reading the wrong field
 *     (`backup.deliveryMode`) or dropping the line altogether are the quieter versions: the export
 *     keeps writing the position, the import stops ever reading it back;
 *  2. the same line for the export.
 *
 * The filter is on `notificationContentOrNull(`, NOT on `startsWith("backup.")` as the older
 * lints of this family use: the field moved into the middle of the line when the lookup was pulled
 * out into a pure function, and a `startsWith("backup.notificationContent")` here would match
 * nothing at all and pass — a rule that pins nothing must fail, so both rules below compare against
 * a one-element list and go red on an empty match.
 *
 * Whole lines, never a `contains`: a substring check is blind to any mutation that LENGTHENS the
 * line, and `?.let { setNotificationContent(NotificationContent.BODY_PREVIEW) }` contains every
 * fragment of the honest line.
 */
class NotificationContentBackupWiringTest {

    @Test fun `the position is exported like every other preference`() {
        assertEquals(
            "snapshotBackup must carry 'notificationContent = notificationContent.first().name,' " +
                "— without it the position is absent from every export, and restoring on a new " +
                "device silently puts a reader who had asked for less back on sender + subject.",
            listOf("notificationContent = notificationContent.first().name,"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("notificationContent = notificationContent") },
        )
    }

    @Test fun `the import applies the position it read, and nothing else`() {
        assertEquals(
            "restoreBackup must apply exactly " +
                "'notificationContentOrNull(backup.notificationContent)?.let { setNotificationContent(it) }'. " +
                "The whole line matters, argument by argument: 'setNotificationContent(" +
                "NotificationContent.BODY_PREVIEW)' there makes every settings import turn the body " +
                "preview on, 'backup.deliveryMode' inside the call restores a delivery mode as a " +
                "notification position, and no line at all means the position is exported forever " +
                "and never read back. An empty list below means this lint matched NOTHING, which " +
                "is itself the failure — the line moved and this rule must be taught the new shape.",
            listOf("notificationContentOrNull(backup.notificationContent)?.let { setNotificationContent(it) }"),
            codeLines(SETTINGS_REPOSITORY).filter { "notificationContentOrNull(" in it && "backup" in it },
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read another module. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
    }
}
