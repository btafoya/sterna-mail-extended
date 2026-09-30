package app.sterna.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import app.sterna.contacts.normalizeForMatch
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount

/** Where a searchable setting lives. */
internal sealed interface SettingHost {
    /** A destination of the settings graph; [path] is what a result prints under its title. */
    data class Screen(val route: String, @StringRes val path: Int) : SettingHost

    /** The current account's screen. Its route comes from [settingsRouteFor], never spelled out. */
    data object CurrentAccount : SettingHost
}

/** The condition a row is drawn under on its screen. An entry obeys the same one, or a result
 *  would open a screen without the row it named. */
internal enum class SettingCondition { ALWAYS, MATERIAL_YOU, IMAP_ONLY, JMAP_ONLY, PASSWORD, API_TOKEN, IMAP_PASSWORD }

/**
 * One searchable setting. Text is held as resources only and resolved at display time, so the
 * results follow the app language.
 *
 * [anchor] is the title of the row, or of the section header ([anchorKind]), that the opened
 * screen scrolls to and flashes; null for a hub rubric, which opens without either.
 */
internal data class SettingEntry(
    val key: String,
    val host: SettingHost,
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val options: List<Int> = emptyList(),
    @StringRes val anchor: Int? = title,
    val condition: SettingCondition = SettingCondition.ALWAYS,
    val anchorKind: AnchorKind = AnchorKind.ROW,
)

/** Whether [SettingEntry.anchor] titles a setting row or a section header. The two are told apart
 *  because a section and the row under it can share their text ("Quiet hours", "Theme"). */
internal enum class AnchorKind { ROW, SECTION }

internal fun settingEntryFor(key: String, index: List<SettingEntry> = SETTINGS_INDEX): SettingEntry? =
    index.firstOrNull { it.key == key }

/** An entry resolved in the app language, ready to be matched and drawn. */
internal data class ResolvedSetting(
    val entry: SettingEntry,
    val title: String,
    val path: String,
    val texts: List<String>,
)

/** The optional navigation argument carrying [SettingEntry.key] to the opened screen. */
internal const val SETTING_FOCUS_ARG = "focus"

/** [route] as a pattern that also accepts the optional [SETTING_FOCUS_ARG]; the bare route still
 *  matches it, so every other way into the screen is unchanged. */
internal fun focusable(route: String): String = "$route?$SETTING_FOCUS_ARG={$SETTING_FOCUS_ARG}"

/** The title the screen opened for [key] must bring into view, or null for none. */
@StringRes
internal fun settingAnchorFor(key: String, index: List<SettingEntry> = SETTINGS_INDEX): Int? =
    settingEntryFor(key, index)?.anchor

/**
 * The entries whose row exists for this install and the [current] account, null when there is
 * none. A shared current account offers no account entry: its screen has none of the login's rows.
 * The account is taken whole so that its protocol, sign-in and sharing are read here, where a test
 * runs them, rather than picked apart at the call site.
 */
internal fun searchableSettings(
    current: StoredAccount?,
    sdkInt: Int,
    index: List<SettingEntry> = SETTINGS_INDEX,
): List<SettingEntry> {
    val protocol = current?.protocol
    val authType = current?.authType
    val accountEntries = current != null && !current.isShared
    return index.filter { entry ->
        (entry.host != SettingHost.CurrentAccount || accountEntries) && when (entry.condition) {
            SettingCondition.ALWAYS -> true
            SettingCondition.MATERIAL_YOU -> sdkInt >= Build.VERSION_CODES.S
            SettingCondition.IMAP_ONLY -> protocol == MailProtocol.IMAP
            SettingCondition.JMAP_ONLY -> protocol == MailProtocol.JMAP
            SettingCondition.PASSWORD -> authType == AuthType.BASIC
            SettingCondition.API_TOKEN -> authType == AuthType.API_TOKEN
            SettingCondition.IMAP_PASSWORD -> protocol == MailProtocol.IMAP && authType != AuthType.OAUTH
        }
    }
}

/**
 * The route a result opens, or null when it names the current account and there is none. The
 * focus key is added only for an entry with a row to flash.
 */
internal fun searchResultRoute(
    entry: SettingEntry,
    currentId: String?,
    accounts: List<StoredAccount>,
): String? {
    val route = when (val host = entry.host) {
        is SettingHost.Screen -> host.route
        SettingHost.CurrentAccount -> settingsRouteFor(currentId ?: return null, accounts)
    }
    return if (entry.anchor == null) route else "$route?$SETTING_FOCUS_ARG=${entry.key}"
}

private val WHITESPACE = Regex("\\s+")

/**
 * The items every word of [query] is found in, the title or any of [texts]. Those whose title holds
 * every word come first; otherwise the order of [items]. A blank query matches nothing: the hub
 * shows its rubrics then.
 */
internal fun <T> searchSettings(
    query: String,
    items: List<T>,
    title: (T) -> String,
    texts: (T) -> List<String>,
): List<T> {
    val words = normalizeForMatch(query).split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return items.mapNotNull { item ->
        val inTitle = normalizeForMatch(title(item))
        val elsewhere = texts(item).map(::normalizeForMatch)
        val found = words.all { word -> word in inTitle || elsewhere.any { word in it } }
        if (found) item to words.all { it in inTitle } else null
    }
        .sortedByDescending { (_, byTitle) -> byTitle }
        .map { (item, _) -> item }
}
