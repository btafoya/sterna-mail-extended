package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: `currentAppLanguage` goes through `AppCompatDelegate`, which
 * the JVM cannot run, so only its handover to `appLanguageForTag` is pinned here, as text.
 * `AppLanguageForTagTest` executes the decision itself.
 */
class AppLanguageReadBackWiringTest {

    @Test fun `the read-back hands the whole language tag to the pure decision`() {
        assertEquals(
            "AppLanguage.kt must read the locale back as '$EXPECTED', the whole line, and only " +
                "once. Passing '.language' instead drops the script and the region, and zh-Hant-TW " +
                "would then show and export as Simplified Chinese.",
            listOf(EXPECTED),
            codeLines().filter { "appLanguageForTag(" in it && !it.startsWith("fun ") },
        )
    }

    @Test fun `the export writes the entry's own tag and the import matches it strictly`() {
        val viewModel = File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
            .readLines().map { it.trim() }
        assertEquals(
            "SettingsViewModel.kt must export 'language = currentAppLanguage().tag,' and import by " +
                "strict equality on the entry's tag: a backup carries only the tags Sterna defines.",
            listOf(
                "language = currentAppLanguage().tag,",
                "val target = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM",
            ),
            viewModel.filter {
                it.startsWith("language = currentAppLanguage()") || it.startsWith("val target = AppLanguage.")
            },
        )
    }

    private fun codeLines(): List<String> = File(root, APP_LANGUAGE_PATH).readLines().map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private companion object {
        const val EXPECTED = "return appLanguageForTag(locales[0]?.toLanguageTag())"
        const val APP_LANGUAGE_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AppLanguage.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, APP_LANGUAGE_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
