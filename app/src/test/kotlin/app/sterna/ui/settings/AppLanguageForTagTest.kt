package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Settings shows, and what an export writes, for a locale Android's own per-app language page
 * or `cmd locale set-app-locales` may have set: those carry a region or a script Sterna never writes.
 */
class AppLanguageForTagTest {

    private fun assertReads(expected: AppLanguage, vararg tags: String?) {
        val wrong = tags.filter { appLanguageForTag(it) != expected }
            .map { "'$it' -> ${appLanguageForTag(it)}" }
        assertEquals("tags that should read as $expected: $wrong", emptyList<String>(), wrong)
    }

    @Test fun `the tags Sterna writes itself read back as their own entry`() {
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { assertReads(it, it.tag) }
    }

    @Test fun `a region set by the system still reads as the language`() {
        assertReads(AppLanguage.FRENCH, "fr", "fr-FR", "fr-CA", "FR-fr")
        assertReads(AppLanguage.ENGLISH, "en-US", "en-GB")
        assertReads(AppLanguage.SPANISH, "es-ES", "es-419")
        assertReads(AppLanguage.GERMAN, "de-DE", "de-AT")
        assertReads(AppLanguage.ITALIAN, "it-IT")
        assertReads(AppLanguage.PORTUGUESE, "pt-BR", "pt-PT")
        assertReads(AppLanguage.DUTCH, "nl-NL", "nl-BE")
        assertReads(AppLanguage.RUSSIAN, "ru-RU")
        assertReads(AppLanguage.POLISH, "pl-PL")
    }

    @Test fun `simplified Chinese reads as Chinese whatever the system spelled`() {
        assertReads(AppLanguage.CHINESE, "zh-CN", "zh-cn", "zh-Hans-CN", "zh-Hans", "zh", "zh-SG", "zh-MY", "zh-CN-u-nu-latn")
    }

    @Test fun `Chinese in a region or script Android does not serve the Simplified translation to reads as the system`() {
        assertReads(AppLanguage.SYSTEM, "zh-US", "zh-AU", "zh-Latn", "zh-Latn-CN")
    }

    @Test fun `a Traditional Chinese region does not change a language that is not Chinese`() {
        assertReads(AppLanguage.ENGLISH, "en-HK", "en-TW")
        assertReads(AppLanguage.PORTUGUESE, "pt-MO")
    }

    @Test fun `an explicit Simplified script wins over a Traditional region`() {
        assertReads(AppLanguage.CHINESE, "zh-Hans-HK", "zh-Hans-TW", "zh-Hans-MO")
    }

    @Test fun `traditional Chinese is not served by the Simplified translation`() {
        assertReads(AppLanguage.SYSTEM, "zh-TW", "zh-Hant-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-HK")
    }

    @Test fun `no locale, or one Sterna has no translation for, reads as the system`() {
        assertReads(AppLanguage.SYSTEM, null, "", "und", "ja", "ja-JP", "sv-SE")
    }
}
