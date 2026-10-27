package app.sterna

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads `MainActivity.kt` as text: an activity cannot be
 * created on the JVM here, and the choice it makes (#268) is a single platform call whose effect
 * only shows at the next cold start.
 *
 * The whole guarded block is pinned line for line, so hole 2 of CONTRIBUTING.md is closed and
 * hole 1 is closed for `enabled`. Hole 1 applies to `settings`, bound above the block.
 * Hole 3 applies: nothing here says the block sits in `onCreate`, nor that it runs at all.
 * Hole 4 applies too: nothing holds the value `dynamicColor` actually emits.
 */
class SplashThemeWiringLintTest {

    @Test fun `the splash theme follows the Material You setting, on Android 12 and later only`() {
        assertEquals(
            "MainActivity.kt must call `setSplashScreenTheme` exactly once",
            1,
            codeLines().count { "setSplashScreenTheme" in it },
        )
        assertEquals(
            "MainActivity.kt must choose the splash theme in this exact block: the guard keeps " +
                "`splashScreen` off Android 11 and older, the dynamic style when the setting is " +
                "on, and `Resources.ID_NULL` (the manifest's theme) when it is off. JUnit prints " +
                "the pinned block as `expected` and what the file holds as `was`.",
            PINNED,
            guardedBlock(),
        )
    }

    private fun guardedBlock(): List<String> {
        val lines = codeLines()
        val call = lines.indexOfFirst { "setSplashScreenTheme" in it }
        if (call < 0) return emptyList()
        val start = (call downTo 0).firstOrNull { lines[it] == GUARD } ?: return lines.subList(call, call + 1)
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            block += lines[i]
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth == 0) break
        }
        return block
    }

    private fun codeLines(): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, MAIN_ACTIVITY).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, MAIN_ACTIVITY).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val MAIN_ACTIVITY = "app/src/main/kotlin/app/sterna/MainActivity.kt"
        const val GUARD = "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {"
        val PINNED = listOf(
            GUARD,
            "lifecycleScope.launch {",
            "settings.dynamicColor.distinctUntilChanged().collect { enabled ->",
            "splashScreen.setSplashScreenTheme(",
            "if (enabled) R.style.Theme_Sterna_Splash_Dynamic else Resources.ID_NULL,",
            ")",
            "}",
            "}",
            "}",
        )
    }
}
