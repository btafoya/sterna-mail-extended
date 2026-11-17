package app.sterna.ui.settings

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.mail.VacationState
import app.sterna.core.imap.ManageSieveException

/** Why the responder screen shows a note instead of its form. */
enum class UnavailableCause {
    /** An IMAP account signed in with OAuth, or without an IMAP host: ManageSieve is never tried. */
    IMAP_ACCOUNT,

    /** The server lacks the support: asked over JMAP or ManageSieve, or a host known to have no ManageSieve, not asked. */
    SERVER,
}

/** Null for a loaded responder, which has no cause to show. */
internal fun unavailableCauseOf(state: VacationState): UnavailableCause? = when (state) {
    VacationState.AccountUnsupported -> UnavailableCause.IMAP_ACCOUNT
    VacationState.Unsupported -> UnavailableCause.SERVER
    is VacationState.Loaded -> null
}

internal fun vacationUnavailableState(state: VacationState, accountLabel: String): VacationUiState =
    VacationUiState(
        loading = false,
        supported = false,
        unavailableCause = unavailableCauseOf(state),
        accountLabel = accountLabel,
    )

/**
 * No protocol here: an IMAP account reaches its filters over ManageSieve, so `Unsupported` means an
 * account no transport serves (IMAP on OAuth, without a host or on a host known to have no ManageSieve,
 * JMAP without Sieve), whichever it is.
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
 * The detail of a failed load or save, on either screen. A refusal shows the server's own words when
 * it gave some: the exception's message also carries the command and the status, which say nothing
 * to the reader.
 */
private fun failureDetail(failure: Throwable): String =
    (failure as? ManageSieveException)?.serverText?.takeIf { it.isNotBlank() }
        ?: failure.message
        ?: failure.javaClass.simpleName

internal fun filtersFailedState(state: FiltersUiState, kind: FiltersError, failure: Throwable): FiltersUiState =
    state.copy(
        errorKind = kind,
        errorSieveKind = (failure as? ManageSieveException)?.kind,
        errorDetail = failureDetail(failure),
    )

/** An IMAP account's responder is a Sieve script, so its failures are the filters' failures. */
internal fun vacationFailedState(state: VacationUiState, kind: VacationError, failure: Throwable): VacationUiState =
    state.copy(
        errorKind = kind,
        errorSieveKind = (failure as? ManageSieveException)?.kind,
        errorDetail = failureDetail(failure),
    )

/**
 * The sentence of a failed responder load or save, [fallback] taking the detail. An unreachable
 * ManageSieve never says the server lacks a responder, and never falls back to
 * `settings_vacation_unsupported`: a firewall on port 4190 or the network look exactly the same from here.
 */
@StringRes
private fun sieveErrorText(sieveKind: ManageSieveException.Kind?, @StringRes fallback: Int): Int = when (sieveKind) {
    ManageSieveException.Kind.UNREACHABLE -> R.string.settings_filters_sieve_unreachable
    ManageSieveException.Kind.INSECURE -> R.string.settings_filters_sieve_insecure
    ManageSieveException.Kind.REFUSED, null -> fallback
}

/**
 * A failure of the filters screen as two lines: the sentence, and under it, greyed, what only a
 * technician reads. [detailRes] and [detail] are never both set.
 */
internal data class FiltersErrorLines(
    @StringRes val sentence: Int,
    @StringRes val detailRes: Int? = null,
    val detail: String? = null,
)

/**
 * The lines of a failed filters gesture. An unreachable ManageSieve never says the server lacks
 * filters: a firewall on port 4190 or the network look exactly the same from here.
 */
internal fun filtersErrorLines(kind: FiltersError, sieveKind: ManageSieveException.Kind?, detail: String): FiltersErrorLines {
    // Refused before any ManageSieve dialogue, so a kind left over from an earlier failure never words it.
    if (kind == FiltersError.TEXT_UNCUT) return FiltersErrorLines(R.string.settings_filters_text_uncut)
    if (kind == FiltersError.CHANGED) return FiltersErrorLines(R.string.settings_filters_changed_elsewhere)
    return when (sieveKind) {
        ManageSieveException.Kind.UNREACHABLE -> FiltersErrorLines(
            R.string.settings_filters_unreachable,
            detailRes = R.string.settings_filters_unreachable_detail,
        )
        ManageSieveException.Kind.INSECURE -> FiltersErrorLines(R.string.settings_filters_sieve_insecure)
        ManageSieveException.Kind.REFUSED, null -> FiltersErrorLines(
            when (kind) {
                FiltersError.LOAD -> R.string.settings_filters_load_error
                FiltersError.SAVE -> R.string.settings_filters_save_error
                FiltersError.ACTIVATE -> R.string.settings_filters_activate_error
                FiltersError.TEXT_UNCUT -> R.string.settings_filters_text_uncut
                FiltersError.CHANGED -> R.string.settings_filters_changed_elsewhere
            },
            detail = detail.takeIf { it.isNotBlank() },
        )
    }
}

/** A date check never reached the server, so a kind left over from an earlier failure never words it. */
@StringRes
internal fun vacationErrorText(kind: VacationError, sieveKind: ManageSieveException.Kind?): Int = when (kind) {
    VacationError.LOAD -> sieveErrorText(sieveKind, R.string.settings_vacation_load_error)
    VacationError.SAVE -> sieveErrorText(sieveKind, R.string.settings_vacation_save_error)
    VacationError.INVALID_DATES -> R.string.settings_vacation_invalid_dates
}
