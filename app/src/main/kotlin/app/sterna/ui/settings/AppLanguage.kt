package app.sterna.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * In-app UI language. [system] follows the device language; the others force a specific translation.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    FRENCH("fr"),
    SPANISH("es"),
    GERMAN("de"),
    ITALIAN("it"),
    PORTUGUESE("pt"),
    DUTCH("nl"),
    RUSSIAN("ru"),
    POLISH("pl"),
    CHINESE("zh-CN"),
}

fun currentAppLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return AppLanguage.SYSTEM
    return appLanguageForTag(locales[0]?.toLanguageTag())
}

fun appLanguageForTag(tag: String?): AppLanguage {
    if (tag.isNullOrEmpty()) return AppLanguage.SYSTEM
    AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag.equals(tag, ignoreCase = true) }
        ?.let { return it }
    val locale = Locale.forLanguageTag(tag)
    // Android surely serves values-zh-rCN to Hans, or to no script in these regions; any other zh tag reads as SYSTEM.
    val simplified = locale.script == "Hans" || (locale.script.isEmpty() && locale.country in SIMPLIFIED_CHINESE_REGIONS)
    if (locale.language == "zh" && !simplified) return AppLanguage.SYSTEM
    return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag.substringBefore('-') == locale.language }
        ?: AppLanguage.SYSTEM
}

private val SIMPLIFIED_CHINESE_REGIONS = setOf("", "CN", "SG", "MY")

/** Apply [language] app-wide. Triggers an activity recreate so the new strings load. */
fun applyAppLanguage(language: AppLanguage) {
    val locales = if (language == AppLanguage.SYSTEM) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language.tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
