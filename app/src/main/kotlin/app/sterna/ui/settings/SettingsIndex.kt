package app.sterna.ui.settings

import app.sterna.R
import app.sterna.ui.settings.AnchorKind.SECTION
import app.sterna.ui.settings.SettingCondition.API_TOKEN
import app.sterna.ui.settings.SettingCondition.IMAP_ONLY
import app.sterna.ui.settings.SettingCondition.JMAP_ONLY
import app.sterna.ui.settings.SettingCondition.MATERIAL_YOU
import app.sterna.ui.settings.SettingCondition.PASSWORD

private val APPEARANCE = SettingHost.Screen("appearance", R.string.settings_appearance_title)
private val READING = SettingHost.Screen("reading", R.string.settings_reading_title)
private val NOTIFICATIONS = SettingHost.Screen("notifications", R.string.settings_notifications_title)
private val PRIVACY = SettingHost.Screen("privacy", R.string.settings_privacy_title)
private val ACCOUNT = SettingHost.CurrentAccount

private fun rubric(key: String, route: String, title: Int, summary: Int, path: Int) =
    SettingEntry(key, SettingHost.Screen(route, path), title, summary, anchor = null)

private val SWIPE_OPTIONS = listOf(
    R.string.settings_swipe_nothing,
    R.string.settings_swipe_toggle_read,
    R.string.settings_swipe_delete,
    R.string.settings_swipe_archive,
    R.string.settings_swipe_flag,
)

private val SECURITY_OPTIONS = listOf(
    R.string.settings_security_tls,
    R.string.settings_security_starttls,
    R.string.settings_security_none,
)

/**
 * Every searchable setting, declared once. `SettingsIndexGuardTest` reads the settings screens and
 * fails on a row that is drawn but missing here, and on an entry whose row is gone.
 *
 * Kept out on purpose: the About links (not settings), the Vacation and Filters fields (drawn only
 * after the server answers, reached through their rubric), folder management (outside this graph),
 * and every account but the current one.
 */
internal val SETTINGS_INDEX: List<SettingEntry> = listOf(
    rubric("accounts", "accounts", R.string.settings_accounts_title, R.string.settings_accounts_summary, R.string.settings_hub_title),
    rubric("appearance", "appearance", R.string.settings_appearance_title, R.string.settings_appearance_summary, R.string.settings_group_app),
    rubric("reading", "reading", R.string.settings_reading_title, R.string.settings_reading_summary, R.string.settings_group_app),
    rubric("notifications", "notifications", R.string.settings_notifications_title, R.string.settings_notifications_summary, R.string.settings_group_app),
    rubric("privacy", "privacy", R.string.settings_privacy_title, R.string.settings_privacy_summary, R.string.settings_group_app),
    rubric("storage", "storage", R.string.settings_storage_title, R.string.settings_storage_summary, R.string.settings_group_app),
    rubric("backup", "backup", R.string.settings_backup_title, R.string.settings_backup_summary, R.string.settings_group_app),
    rubric("vacation", "vacation", R.string.settings_vacation_title, R.string.settings_vacation_summary, R.string.settings_group_current_account_generic),
    rubric("filters", "filters", R.string.settings_filters_title, R.string.settings_filters_summary, R.string.settings_group_current_account_generic),

    SettingEntry(
        "language", APPEARANCE, R.string.settings_language_title,
        options = listOf(
            R.string.settings_language_system,
            R.string.settings_language_english,
            R.string.settings_language_french,
            R.string.settings_language_spanish,
            R.string.settings_language_german,
            R.string.settings_language_italian,
            R.string.settings_language_portuguese,
            R.string.settings_language_dutch,
            R.string.settings_language_russian,
            R.string.settings_language_polish,
            R.string.settings_language_chinese,
        ),
    ),
    SettingEntry(
        "theme", APPEARANCE, R.string.settings_theme_title,
        options = listOf(R.string.settings_theme_auto, R.string.settings_theme_light, R.string.settings_theme_dark),
    ),
    SettingEntry(
        "dynamic_color", APPEARANCE, R.string.settings_dynamic_color_title, R.string.settings_dynamic_color_subtitle,
        condition = MATERIAL_YOU,
    ),
    SettingEntry("pure_black", APPEARANCE, R.string.settings_pure_black_title, R.string.settings_pure_black_subtitle),
    SettingEntry(
        "open_on", APPEARANCE, R.string.settings_open_on_title,
        options = listOf(R.string.folder_inbox, R.string.inbox_all_inboxes, R.string.settings_open_on_last),
    ),
    SettingEntry(
        "density", APPEARANCE, R.string.settings_density_title,
        options = listOf(R.string.settings_density_compact, R.string.settings_density_normal, R.string.settings_density_spaced),
    ),
    SettingEntry(
        "reading_pane", APPEARANCE, R.string.settings_reading_pane_title,
        options = listOf(
            R.string.settings_reading_pane_always,
            R.string.settings_reading_pane_landscape,
            R.string.settings_reading_pane_never,
        ),
    ),
    SettingEntry(
        "preview", APPEARANCE, R.string.settings_preview_title,
        options = listOf(
            R.string.settings_preview_subject_only,
            R.string.settings_preview_one_line,
            R.string.settings_preview_three_lines,
            R.string.settings_preview_five_lines,
        ),
    ),
    SettingEntry("unread_tint", APPEARANCE, R.string.settings_unread_tint_title, R.string.settings_unread_tint_subtitle),
    SettingEntry("list_monogram", APPEARANCE, R.string.settings_list_monogram_title, R.string.settings_list_monogram_subtitle),

    SettingEntry("conversation", READING, R.string.settings_conversation_title, R.string.settings_conversation_subtitle),
    SettingEntry(
        "unarchive_on_reply", READING, R.string.settings_unarchive_on_reply_title,
        R.string.settings_unarchive_on_reply_subtitle,
    ),
    SettingEntry(
        "text_size", READING, R.string.settings_text_size_title,
        options = listOf(
            R.string.settings_text_size_small,
            R.string.settings_text_size_normal,
            R.string.settings_text_size_large,
            R.string.settings_text_size_huge,
        ),
    ),
    SettingEntry("plain_text", READING, R.string.settings_plain_text_title, R.string.settings_plain_text_subtitle),
    SettingEntry(
        "mark_read", READING, R.string.settings_mark_read_title,
        options = listOf(
            R.string.settings_mark_read_moving,
            R.string.settings_mark_read_deleting,
            R.string.settings_mark_read_archiving,
            R.string.settings_mark_read_never,
        ),
    ),
    SettingEntry("reply_bar", READING, R.string.settings_reply_bar_title, R.string.settings_reply_bar_subtitle),
    SettingEntry("swipe_right", READING, R.string.settings_swipe_right_title, options = SWIPE_OPTIONS),
    SettingEntry("swipe_left", READING, R.string.settings_swipe_left_title, options = SWIPE_OPTIONS),
    SettingEntry(
        "signature_on_replies", READING, R.string.settings_signature_on_replies_title,
        R.string.settings_signature_on_replies_subtitle,
    ),
    SettingEntry(
        "signature_below_quote", READING, R.string.settings_signature_below_quote_title,
        R.string.settings_signature_below_quote_subtitle,
    ),
    SettingEntry(
        "signature_delimiter", READING, R.string.settings_signature_delimiter_title,
        R.string.settings_signature_delimiter_subtitle,
    ),

    SettingEntry("push_all", NOTIFICATIONS, R.string.settings_push_all_title, R.string.settings_push_all_subtitle),
    SettingEntry(
        "notif_content_preview", NOTIFICATIONS, R.string.settings_notif_content_preview,
        R.string.settings_notif_content_preview_desc,
    ),
    SettingEntry(
        "notif_content_full", NOTIFICATIONS, R.string.settings_notif_content_full,
        R.string.settings_notif_content_full_desc,
    ),
    SettingEntry(
        "notif_content_sender", NOTIFICATIONS, R.string.settings_notif_content_sender,
        R.string.settings_notif_content_sender_desc,
    ),
    SettingEntry(
        "notif_content_none", NOTIFICATIONS, R.string.settings_notif_content_none,
        R.string.settings_notif_content_none_desc,
    ),
    SettingEntry("quiet_hours", NOTIFICATIONS, R.string.settings_quiet_hours_title, R.string.settings_quiet_hours_subtitle),
    // A row drawn only under a condition (quiet hours on, a relay address published, two OpenPGP
    // providers, OpenPGP on, an identity unfolded) opens on the row or header above it, which is
    // always drawn: a result never leads to a screen without its target.
    SettingEntry(
        "quiet_hours_start", NOTIFICATIONS, R.string.settings_quiet_hours_start,
        anchor = R.string.settings_quiet_hours_title,
    ),
    SettingEntry(
        "quiet_hours_end", NOTIFICATIONS, R.string.settings_quiet_hours_end,
        anchor = R.string.settings_quiet_hours_title,
    ),

    SettingEntry("app_lock", PRIVACY, R.string.settings_app_lock_title, R.string.settings_app_lock_subtitle),
    SettingEntry("strip_tracking", PRIVACY, R.string.settings_strip_tracking_title, R.string.settings_strip_tracking_subtitle),
    SettingEntry("confirm_links", PRIVACY, R.string.settings_confirm_links_title, R.string.settings_confirm_links_subtitle),
    SettingEntry("read_receipt", PRIVACY, R.string.settings_read_receipt_title, R.string.settings_read_receipt_subtitle),
    SettingEntry(
        "quoted_dates_utc", PRIVACY, R.string.settings_quoted_dates_utc_title,
        R.string.settings_quoted_dates_utc_subtitle,
    ),
    SettingEntry(
        "suggest_contacts", PRIVACY, R.string.settings_suggest_contacts_title,
        R.string.settings_suggest_contacts_subtitle,
    ),
    SettingEntry(
        "image_allowlist", PRIVACY, R.string.settings_image_allowlist_section,
        R.string.settings_image_allowlist_empty,
        anchorKind = SECTION,
    ),

    SettingEntry("display_name", ACCOUNT, R.string.settings_display_name_label),
    SettingEntry("account_colour", ACCOUNT, R.string.settings_account_colour_section, anchorKind = SECTION),
    SettingEntry(
        "account_notifications", ACCOUNT, R.string.settings_account_notifications_title,
        R.string.settings_account_notifications_subtitle,
    ),
    SettingEntry("delivery_instant", ACCOUNT, R.string.settings_delivery_instant, R.string.settings_delivery_instant_desc),
    SettingEntry("delivery_saver", ACCOUNT, R.string.settings_delivery_saver, R.string.settings_delivery_saver_desc),
    SettingEntry(
        "relay_address", ACCOUNT, R.string.settings_push_relay_title,
        anchor = R.string.settings_account_notifications_title,
        condition = IMAP_ONLY,
    ),
    SettingEntry("imap_server", ACCOUNT, R.string.settings_imap_server_label, condition = IMAP_ONLY),
    SettingEntry("imap_port", ACCOUNT, R.string.settings_imap_port_label, condition = IMAP_ONLY),
    SettingEntry(
        "imap_security", ACCOUNT, R.string.settings_imap_security_title,
        options = SECURITY_OPTIONS, condition = IMAP_ONLY,
    ),
    SettingEntry("smtp_server", ACCOUNT, R.string.settings_smtp_server_label, condition = IMAP_ONLY),
    SettingEntry("smtp_port", ACCOUNT, R.string.settings_smtp_port_label, condition = IMAP_ONLY),
    SettingEntry(
        "smtp_security", ACCOUNT, R.string.settings_smtp_security_title,
        options = SECURITY_OPTIONS, condition = IMAP_ONLY,
    ),
    SettingEntry(
        "upload_sent", ACCOUNT, R.string.settings_upload_sent_title, R.string.settings_upload_sent_subtitle,
        condition = IMAP_ONLY,
    ),
    SettingEntry("server_url", ACCOUNT, R.string.settings_server_url_label, condition = JMAP_ONLY),
    SettingEntry("username", ACCOUNT, R.string.settings_username_label),
    SettingEntry("password", ACCOUNT, R.string.settings_password_label, condition = PASSWORD),
    SettingEntry("api_token", ACCOUNT, R.string.settings_api_token_label, condition = API_TOKEN),
    SettingEntry(
        "identities", ACCOUNT, R.string.settings_identities_section, R.string.settings_identities_help,
        anchorKind = SECTION,
    ),
    SettingEntry(
        "signature", ACCOUNT, R.string.settings_signature_label,
        anchor = R.string.settings_identities_section,
        anchorKind = SECTION,
    ),
    SettingEntry(
        "messages_to_sync", ACCOUNT, R.string.settings_messages_to_sync_title,
        options = listOf(
            R.string.settings_sync_last_30_days,
            R.string.settings_sync_last_90_days,
            R.string.settings_sync_last_year,
            R.string.settings_sync_100_messages,
            R.string.settings_sync_1000_messages,
            R.string.settings_sync_10000_messages,
        ),
    ),
    SettingEntry(
        "subscribed_folders", ACCOUNT, R.string.settings_subscribed_folders_title,
        R.string.settings_subscribed_folders_subtitle,
    ),
    // Its subtitle takes the provider's name as an argument: left out rather than searched with a
    // placeholder in it.
    SettingEntry("pgp", ACCOUNT, R.string.settings_pgp_enable_title),
    SettingEntry("pgp_provider", ACCOUNT, R.string.settings_pgp_provider_title, anchor = R.string.settings_pgp_enable_title),
    SettingEntry("pgp_key", ACCOUNT, R.string.settings_pgp_key_title, anchor = R.string.settings_pgp_enable_title),
    SettingEntry(
        "pgp_encrypt_default", ACCOUNT, R.string.settings_pgp_encrypt_default_title,
        R.string.settings_pgp_encrypt_default_subtitle,
        anchor = R.string.settings_pgp_enable_title,
    ),
)
