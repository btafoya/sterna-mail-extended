package app.sterna.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

private val material = Typography()

private fun TextStyle.followsContent() = copy(textDirection = TextDirection.Content)

/** Direction follows the text (first strong character, as a `TextView`), not the system locale. */
val SternaTypography = Typography(
    displayLarge = material.displayLarge.followsContent(),
    displayMedium = material.displayMedium.followsContent(),
    displaySmall = material.displaySmall.followsContent(),
    headlineLarge = material.headlineLarge.followsContent(),
    headlineMedium = material.headlineMedium.followsContent(),
    headlineSmall = material.headlineSmall.followsContent(),
    titleLarge = material.titleLarge.followsContent(),
    titleMedium = material.titleMedium.followsContent(),
    titleSmall = material.titleSmall.followsContent(),
    bodyLarge = material.bodyLarge.followsContent(),
    bodyMedium = material.bodyMedium.followsContent(),
    bodySmall = material.bodySmall.followsContent(),
    labelLarge = material.labelLarge.followsContent(),
    labelMedium = material.labelMedium.followsContent(),
    labelSmall = material.labelSmall.followsContent(),
)
