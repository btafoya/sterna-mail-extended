package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument, same disclaimer and the same reason as
 * [RecentMailWidgetWiringLintTest] next door: there is no Robolectric in this repository, so
 * `SettingsViewModel`, `RecentMailWidgetDraw` and `UnreadWidgetDraw` cannot be RUN by this suite.
 *
 * It exists because the lint next door pins `setAppLock` and `setNotificationContent` only. Since
 * the widgets build their colours in Kotlin from the app's own theme settings
 * ([WidgetThemeReader.palette]), the three THEME setters carry the same obligation — and without a
 * rule of their own the three calls each of them makes are trivially deletable, with nothing on
 * screen to say they went: the cell would just be, one day, a theme behind.
 *
 * ⛔ WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line. The price is that reformatting a pinned line reddens this test — loud, and
 * one line to fix.
 */
class WidgetThemeRedrawWiringLintTest {

    /**
     * ⛔ CHANGING THE THEME MUST REACH THE HOME SCREEN, for BOTH cells.
     *
     * The two widgets are redrawn when the system asks (placement, boot, app update), at the tail
     * of a delivery pass, and from the pushed collections — and by nothing else, because
     * `updatePeriodMillis="0"` means there is no timer by design. All three triggers are fed by
     * MAIL. A theme change passes through none of them: the reader switches to dark, or turns pure
     * black on, or turns Material You off, goes back to the home screen, and the cell keeps the old
     * colours until the next message ARRIVES — on a quiet mailbox, days. The state on screen is a
     * theme the app no longer holds.
     *
     * The whole bodies are pinned, not the mere presence of the calls, because the ORDER is half
     * the fix:
     *
     * - the setting is written FIRST and AWAITED. Both refreshes re-read the three theme settings
     *   through `WidgetThemeReader.palette`, with `.first()` on the DataStore flows; a redraw
     *   started before `dataStore.edit` has returned would repaint the cell with the OLD theme, and
     *   nothing would come back to correct it.
     * - `forget()` comes BEFORE `refresh()`. The row factory falls back to the last
     *   `RecentWidgetDrawing` it managed to read when its own read times out, and that carrier
     *   holds the OLD palette. Without dropping it, a slow read right after the change repaints
     *   exactly the colours the reader has just left. Same reason as at the two neighbouring
     *   setters, applied to colour.
     * - BOTH widgets, never one. They have separate presences, separate reads and separate draws;
     *   forgetting one is this pane's likely failure mode and the one nobody would see before the
     *   bench. `UnreadWidgetDraw.refresh` has no `forget()` — it holds no snapshot.
     *
     * ✅ The two refreshes cost ONE binder call each when no cell is placed: both ask `placedIds`
     * first and return on an empty answer.
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

    private companion object {
        val SETTINGS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")

        const val LAUNCH = "viewModelScope.launch {"
        const val FORGET = "RecentMailWidgetDraw.forget()"
        const val REFRESH_RECENT = "RecentMailWidgetDraw.refresh(getApplication())"
        const val REFRESH_UNREAD = "UnreadWidgetDraw.refresh(getApplication())"
    }
}
