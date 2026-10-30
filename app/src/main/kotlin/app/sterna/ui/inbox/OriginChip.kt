package app.sterna.ui.inbox

/**
 * The text of a list row's single origin chip. A known [arrival] address takes the place of the
 * account's name, which it tells apart at least as well, and follows the folder where the row names
 * one. Unknown, the chip reads the account, else the folder, else nothing at all. An arrival the
 * top bar already writes ([banner], compared trimmed and case-blind) counts as unknown.
 */
internal fun originChipLabel(accountLabel: String?, folderLabel: String?, arrival: String?, banner: String?): String? {
    val shown = arrival?.takeUnless { it.trim().equals(banner?.trim(), ignoreCase = true) }
    return when {
        shown == null -> accountLabel ?: folderLabel
        accountLabel != null || folderLabel == null -> shown
        else -> folderLabel + ORIGIN_SEPARATOR + shown
    }
}

/**
 * The account name the top bar's subtitle writes in [ui], the one an arrival chip would only repeat.
 * It mirrors the subtitle's own conditions; the unread view of every account writes the translated
 * "All inboxes" there, and search swaps the bar for its field, so neither names an address.
 * Compared against what is written, never against a label recomputed from the account.
 * Selection mode is left out on purpose: its bar hides the subtitle only while rows are picked,
 * and chips that came back with each long-press would make every row jump.
 */
internal fun bannerAddress(ui: MailUi): String? =
    ui.accountName.takeIf { !ui.searching && !ui.unified && !ui.unreadAllAccounts && it.isNotBlank() }

/**
 * What stands for "a message of one's own" when the chip looks for an arrival address. The row's
 * own [showRecipients] everywhere but in search, where it is forced off because a hit's folder is
 * not trusted: there authorship alone decides, or a hit from Sent would name an arrival the reader
 * never shows.
 */
internal fun chipOwnMessage(fromSearch: Boolean, selfAuthored: Boolean, showRecipients: Boolean): Boolean =
    if (fromSearch) selfAuthored else showRecipients

// The separator of `notif_account_folder`, which a lint holds identical in every language: a
// literal here cannot drift from a translation, and keeps the composition a plain function.
private const val ORIGIN_SEPARATOR = " · "
