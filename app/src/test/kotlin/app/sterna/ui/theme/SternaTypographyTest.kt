package app.sterna.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the fifteen styles `SternaTypography` sets, named rather than found by reflection. A slot
 * Material adds later keeps the system direction and is not caught here: it needs a line in both files.
 */
class SternaTypographyTest {

    private fun assertFollowsContent(name: String, style: TextStyle) =
        assertEquals("$name.textDirection", TextDirection.Content, style.textDirection)

    @Test fun displayLarge() = assertFollowsContent("displayLarge", SternaTypography.displayLarge)
    @Test fun displayMedium() = assertFollowsContent("displayMedium", SternaTypography.displayMedium)
    @Test fun displaySmall() = assertFollowsContent("displaySmall", SternaTypography.displaySmall)
    @Test fun headlineLarge() = assertFollowsContent("headlineLarge", SternaTypography.headlineLarge)
    @Test fun headlineMedium() = assertFollowsContent("headlineMedium", SternaTypography.headlineMedium)
    @Test fun headlineSmall() = assertFollowsContent("headlineSmall", SternaTypography.headlineSmall)
    @Test fun titleLarge() = assertFollowsContent("titleLarge", SternaTypography.titleLarge)
    @Test fun titleMedium() = assertFollowsContent("titleMedium", SternaTypography.titleMedium)
    @Test fun titleSmall() = assertFollowsContent("titleSmall", SternaTypography.titleSmall)
    @Test fun bodyLarge() = assertFollowsContent("bodyLarge", SternaTypography.bodyLarge)
    @Test fun bodyMedium() = assertFollowsContent("bodyMedium", SternaTypography.bodyMedium)
    @Test fun bodySmall() = assertFollowsContent("bodySmall", SternaTypography.bodySmall)
    @Test fun labelLarge() = assertFollowsContent("labelLarge", SternaTypography.labelLarge)
    @Test fun labelMedium() = assertFollowsContent("labelMedium", SternaTypography.labelMedium)
    @Test fun labelSmall() = assertFollowsContent("labelSmall", SternaTypography.labelSmall)
}
