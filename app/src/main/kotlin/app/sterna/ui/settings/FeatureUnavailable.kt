package app.sterna.ui.settings

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ManageSieveException

/** Why the responder screen shows a note instead of its form. */
enum class UnavailableCause {
    /** The account was added over IMAP/SMTP; the responder goes through JMAP only. */
    IMAP_ACCOUNT,

    /** A JMAP server that does not advertise the capability. */
    SERVER,
}

/**
 * `loadVacation` answers the same bare `Unsupported` for both causes, and that type stays shared, so
 * the account's protocol is what tells them apart. No round trip: an IMAP account is refused before
 * the server is asked anything.
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

/**
 * No protocol here: an IMAP account reaches its filters over ManageSieve, so `Unsupported` means an
 * account no transport serves (IMAP on OAuth or without a host, JMAP without Sieve), whichever it is.
 */
internal fun filtersUnavailableState(accountLabel: String): FiltersUiState =
    FiltersUiState(
        loading = false,
        supported = false,
        accountLabel = accountLabel,
    )

@StringRes
internal fun vacationUnavailableText(cause: UnavailableCause?): Int = when (cause) {
    UnavailableCause.IMAP_ACCOUNT -> R.string.settings_vacation_imap_account
    UnavailableCause.SERVER, null -> R.string.settings_vacation_unsupported
}

/**
 * [state] after a failed load or save. A refusal shows the server's own words when it gave some:
 * the exception's message also carries the command and the status, which say nothing to the reader.
 */
internal fun filtersFailedState(state: FiltersUiState, kind: FiltersError, failure: Throwable): FiltersUiState {
    val sieve = failure as? ManageSieveException
    return state.copy(
        errorKind = kind,
        errorSieveKind = sieve?.kind,
        errorDetail = sieve?.serverText?.takeIf { it.isNotBlank() }
            ?: failure.message
            ?: failure.javaClass.simpleName,
    )
}

/**
 * The sentence of a failed load or save. An unreachable ManageSieve never says the server lacks
 * filters: a firewall on port 4190 or the network look exactly the same from here.
 */
@StringRes
internal fun filtersErrorText(kind: FiltersError, sieveKind: ManageSieveException.Kind?): Int = when (sieveKind) {
    ManageSieveException.Kind.UNREACHABLE -> R.string.settings_filters_sieve_unreachable
    ManageSieveException.Kind.INSECURE -> R.string.settings_filters_sieve_insecure
    ManageSieveException.Kind.REFUSED, null -> when (kind) {
        FiltersError.LOAD -> R.string.settings_vacation_load_error
        FiltersError.SAVE -> R.string.settings_vacation_save_error
    }
}
