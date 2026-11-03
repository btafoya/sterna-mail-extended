package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, same instrument, same disclaimer and the same reason as
 * [RecentMailWidgetWiringLintTest] next door: there is no Robolectric in this repository, so
 * `SettingsViewModel`, `RecentMailWidgetDraw` and `UnreadWidgetDraw` cannot be run by this suite.
 */
class WidgetThemeRedrawWiringLintTest {

    /**
     * Changing the theme must reach the home screen, for both cells.
     */
    @Test fun `changing the theme redraws both cells at once, so neither keeps colours the app dropped`() {
        assertEquals(
            "SettingsViewModel.setThemeMode, setPureBlack and setDynamicColor must each write the " +
                "setting, await it, drop the held widget snapshot and redraw BOTH home-screen " +
                "widgets, in that order. Without it the reader changes theme, goes back to the " +
                "home screen, and the cell keeps the old one until the next message arrives — on " +
                "a quiet mailbox, days. Redrawing before the write has returned repaints the old " +
                "theme; refreshing before forget() lets a slow read repaint the colours just " +
                "left; and refreshing one widget leaves the other behind.",
            listOf(
                listOf(
                    "fun setThemeMode(mode: ThemeMode) {",
                    LAUNCH,
                    "settings.setThemeMode(mode)",
                    FORGET,
                    REFRESH_RECENT,
                    REFRESH_UNREAD,
                    "}",
                ),
                listOf(
                    "fun setPureBlack(enabled: Boolean) {",
                    LAUNCH,
                    "settings.setPureBlack(enabled)",
                    FORGET,
                    REFRESH_RECENT,
                    REFRESH_UNREAD,
                    "}",
                ),
                listOf(
                    "fun setDynamicColor(enabled: Boolean) {",
                    LAUNCH,
                    "settings.setDynamicColor(enabled)",
                    FORGET,
                    REFRESH_RECENT,
                    REFRESH_UNREAD,
                    "}",
                ),
            ),
            listOf(
                functionBody(SETTINGS_VIEW_MODEL, "fun setThemeMode(mode: ThemeMode)"),
                functionBody(SETTINGS_VIEW_MODEL, "fun setPureBlack(enabled: Boolean)"),
                functionBody(SETTINGS_VIEW_MODEL, "fun setDynamicColor(enabled: Boolean)"),
            ),
        )
    }

    @Test fun `the counter's refresh without a kind redraws every kind`() {
        assertEquals(
            "UnreadWidgetDraw.refresh(context), what setThemeMode, setPureBlack, setDynamicColor " +
                "and setAppLock call, must refresh EVERY UnreadWidgetKind. Refreshing one, the " +
                "other counter keeps the old theme, or an account's name under the lock, until " +
                "mail arrives.",
            listOf(
                "suspend fun refresh(context: Context) {",
                "UnreadWidgetKind.entries.forEach { refresh(context, it) }",
            ),
            functionBody(UNREAD_DRAW, "suspend fun refresh(context: Context)"),
        )
        assertEquals(
            "and the four sites call that one, never a single kind's.",
            List(4) { REFRESH_UNREAD },
            DeclarationSource.kotlinLines(SETTINGS_VIEW_MODEL).filter { it.startsWith("UnreadWidgetDraw.") },
        )
    }

    private companion object {
        val UNREAD_DRAW = file("app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")

        val SETTINGS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")

        const val LAUNCH = "viewModelScope.launch {"
        const val FORGET = "RecentMailWidgetDraw.forget()"
        const val REFRESH_RECENT = "RecentMailWidgetDraw.refresh(getApplication())"
        const val REFRESH_UNREAD = "UnreadWidgetDraw.refresh(getApplication())"
    }
}
