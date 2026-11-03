package app.sterna.core.data.settings

import app.sterna.core.data.account.StoredAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of the app's preferences, everything in the settings
 */
@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val themeMode: String? = null,
    val dynamicColor: Boolean? = null,
    val listDensity: String? = null,
    val readingPaneMode: String? = null,
    val previewLines: String? = null,
    val swipeRight: String? = null,
    val swipeLeft: String? = null,
    val sortOrder: String? = null,
    val contactSuggestions: Boolean? = null,
    val stripTracking: Boolean? = null,
    val confirmLinks: Boolean? = null,
    /**
     * Whether an opened message may ask the reader to answer a sender's read-receipt request
     * (#148). Never "send receipts": this backs up permission to be asked.
     */
    val askReadReceipt: Boolean? = null,
    val imageAllowlist: List<String>? = null,
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null,
    val pushAllAccounts: Boolean? = null,
    /** App-locale language tag ("" = follow system). */
    val language: String? = null,
    val conversationView: Boolean? = null,
    /** [MessageTextSize] name. */
    val messageTextSize: String? = null,
    val markReadOnDelete: Boolean? = null,
    val markReadOnArchive: Boolean? = null,
    val markReadOnMove: Boolean? = null,
    val unarchiveOnReply: Boolean? = null,
    val signatureOnReplies: Boolean? = null,
    val signatureBelowQuote: Boolean? = null,
    /** Whether the composer adds the standard "-- " delimiter line above the signature (#90). */
    val signatureDelimiter: Boolean? = null,
    /** Whether the reader shows the Reply/Forward bar along the bottom of a message (#63). */
    val replyBar: Boolean? = null,
    /** Whether a message opens as text rather than as its own HTML (#149). */
    val plainText: Boolean? = null,
    /** Whether a reply's quote line and a forward's Date header are written in UTC (#120). */
    val quotedDatesUtc: Boolean? = null,
    /** Whether unread rows in the message list carry a background of their own (#141). */
    val unreadTint: Boolean? = null,
    /** Whether the dark theme sits on a black background, for OLED panels (#117). */
    val pureBlack: Boolean? = null,
    /** Whether each message-list row starts with the sender's coloured initials (#144). */
    val listMonogram: Boolean? = null,
    val boldUnreadFolders: Boolean? = null,
    val starredInDrawer: Boolean? = null,
    val searchHistory: Boolean? = null,
    val volumeTextSize: Boolean? = null,
    /** [DeliveryMode] name (Instant / Battery saver). */
    val deliveryMode: String? = null,
    val notificationContent: String? = null,
    val downloadAhead: Boolean? = null,
    val downloadAheadPeriod: String? = null,
    val downloadAheadAttachments: Boolean? = null,
    /**
     * Account configuration without any secret. The password/refresh-token slot is never exported;
     * [StoredAccount.oauthAccessToken] and device/sync state are cleared before export.
     */
    val accounts: List<StoredAccount>? = null,
) {
    /** True when the decoded file carries at least one setting, rather than being some other JSON. */
    // Every field but `version` defaults to null, so any difference from a fresh record is a setting,
    // one added later included; `version` is neutralised, a file stating only that restores nothing.
    fun isPlausible(): Boolean = copy(version = SettingsBackup().version) != SettingsBackup()
}

/** JSON (de)serialization for [SettingsBackup] export/import files. */
object SettingsBackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: SettingsBackup): String =
        json.encodeToString(SettingsBackup.serializer(), backup)

    /** Parses a backup file; returns null if the text is not a valid backup. */
    fun decode(text: String): SettingsBackup? =
        runCatching { json.decodeFromString(SettingsBackup.serializer(), text) }.getOrNull()
}
