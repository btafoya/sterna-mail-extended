package app.sterna.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: the hub is not composed on the JVM. Which result leaves the
 * settings graph is decided by [opensOnAppGraph], run in [SettingsSearchTest]; this pins only that
 * the app graph navigates what the hub hands it (the hub's side is pinned by
 * `SharedAccountScreenTest`), and the search field's icon. It does not see a call moved
 * behind a condition, nor what is drawn.
 */
class SettingsSearchWiringTest {

    private fun source(path: String): String =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/kotlin/app/sterna/ui/$path") }
            .first { it.isFile }
            .readText()

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    @Test fun `the app graph navigates the route the settings hand it`() {
        val app = flat(source("SternaApp.kt"))
        assertTrue("onOpenAppRoute = { route -> entry.navigateOnce { nav.navigate(route) } }" in app)
    }

    @Test fun `the search field leads with a greyed search icon`() {
        val ui = source("settings/SettingsSearchUi.kt")
        val field = flat(ui.substring(ui.indexOf("private fun SettingsSearchField(")).substringBefore("\n}\n"))
        assertTrue(
            field,
            "leadingIcon = { Icon( Icons.Filled.Search, contentDescription = null, " +
                "tint = MaterialTheme.colorScheme.onSurfaceVariant, ) }" in field,
        )
    }
}
