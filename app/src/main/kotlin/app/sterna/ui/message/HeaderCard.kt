package app.sterna.ui.message

import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.mail.preferredAction

/** One line of the card under the message header, carrying what that line needs to be drawn. */
internal sealed interface HeaderCardRow {
    data class Crypto(val state: CryptoUiState) : HeaderCardRow

    data object Images : HeaderCardRow

    data class Unsubscribe(val action: UnsubscribeAction) : HeaderCardRow

    data class ReadReceipt(val strip: ReadReceiptStrip) : HeaderCardRow

    data class SenderUnverified(val methods: String) : HeaderCardRow
}

/**
 * The card's lines in the order they are drawn; empty means no card at all. Crypto comes first because
 * it says whether the body can be trusted before it is read; the sender warning comes last so it never
 * pushes an action below the fold. [imagesStrip] is the frozen presence decided once per page, never
 * the live blocking state: a line that leaves at the tap changes the header's height, and that height
 * keys the body document.
 */
internal fun headerCardRows(
    crypto: CryptoUiState,
    imagesStrip: Boolean,
    unsubscribe: UnsubscribeOptions?,
    readReceipt: ReadReceiptStrip?,
    senderUnverified: String?,
): List<HeaderCardRow> = buildList {
    if (crypto != CryptoUiState.None) add(HeaderCardRow.Crypto(crypto))
    if (imagesStrip) add(HeaderCardRow.Images)
    unsubscribe?.preferredAction()?.let { add(HeaderCardRow.Unsubscribe(it)) }
    readReceipt?.let { add(HeaderCardRow.ReadReceipt(it)) }
    senderUnverified?.let { add(HeaderCardRow.SenderUnverified(it)) }
}
