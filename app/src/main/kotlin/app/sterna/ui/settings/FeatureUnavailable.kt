package app.sterna.ui.settings

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.MailProtocol

/** Why the responder or filters screen shows a note instead of its form. */
enum class UnavailableCause {
    /** The account was added over IMAP/SMTP; both features go through JMAP only. */
    IMAP_ACCOUNT,

    /** A JMAP server that does not advertise the capability. */
    SERVER,
}

/**
 * `loadVacation` and `loadFilterRules` answer the same bare `Unsupported` for both causes, and
 * that type stays shared, so the account's protocol is what tells them apart. No round trip: an
 * IMAP account is refused before the server is asked anything.
 */
internal fun unavailableCauseOf(protocol: MailProtocol): UnavailableCause = when (protocol) {
    MailProtocol.IMAP -> UnavailableCause.IMAP_ACCOUNT
    MailProtocol.JMAP -> UnavailableCause.SERVER
}

internal fun vacationUnavailableState(protocol: MailProtocol, accountLabel: String): VacationUiState =
    VacationUiState(
        loading = false,
        supported = false,
        unavailableCause = unavailableCauseOf(protocol),
        accountLabel = accountLabel,
    )

/** `supported = false` is what keeps Save off the screen: `saveFilterRules` has no protocol guard. */
internal fun filtersUnavailableState(protocol: MailProtocol, accountLabel: String): FiltersUiState =
    FiltersUiState(
        loading = false,
        supported = false,
        unavailableCause = unavailableCauseOf(protocol),
        accountLabel = accountLabel,
    )

@StringRes
internal fun vacationUnavailableText(cause: UnavailableCause?): Int = when (cause) {
    UnavailableCause.IMAP_ACCOUNT -> R.string.settings_vacation_imap_account
    UnavailableCause.SERVER, null -> R.string.settings_vacation_unsupported
}

@StringRes
internal fun filtersUnavailableText(cause: UnavailableCause?): Int = when (cause) {
    UnavailableCause.IMAP_ACCOUNT -> R.string.settings_filters_imap_account
    UnavailableCause.SERVER, null -> R.string.settings_filters_unsupported
}
