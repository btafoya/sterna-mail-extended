package app.sterna.ui.compose

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.util.isValidEmail

/** Why a recipient chip is drawn flagged, and the words a screen reader gets for it. */
internal enum class RecipientChipFault(@StringRes val message: Int) {
    INVALID_ADDRESS(R.string.compose_recipient_invalid),
    NO_KEY(R.string.compose_recipient_no_key),
}

/** An address that is not one has no key to look for: it is invalid, and [missingKey] is not asked. */
internal fun recipientChipFault(chip: String, missingKey: (String) -> Boolean): RecipientChipFault? = when {
    !isValidEmail(chip) -> RecipientChipFault.INVALID_ADDRESS
    missingKey(chip) -> RecipientChipFault.NO_KEY
    else -> null
}
