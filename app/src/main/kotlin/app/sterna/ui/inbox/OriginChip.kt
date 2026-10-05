package app.sterna.ui.inbox

/**
 * The text of a list row's single origin chip. A known [arrival] address takes the place of the
 * account's name, which it tells apart at least as well, and follows the folder where the row names
 * one. Unknown, the chip reads the account, else the folder, else nothing at all.
 */
internal fun originChipLabel(accountLabel: String?, folderLabel: String?, arrival: String?): String? = when {
    arrival == null -> accountLabel ?: folderLabel
    accountLabel != null || folderLabel == null -> arrival
    else -> folderLabel + ORIGIN_SEPARATOR + arrival
}

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
