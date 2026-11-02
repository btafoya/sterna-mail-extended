package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes. [AddressBookNameTest] runs the
 * substitution; this pins that the list row and the open message's header draw the sender it
 * returns, that the monogram's colour still comes from the raw address, and that the activity
 * provides the book the container reads (#280).
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`sender`, `shownSender` and `senderNames` are only
 * names), and 3 (nothing proves which branch runs for a given row).
 */
class SenderNameWiringTest {

    private val row by lazy { DeclarationSource.functionBody(DeclarationSource.file(LIST), "fun EmailListItem(") }
    private val header by lazy { DeclarationSource.functionBody(DeclarationSource.file(MESSAGE), "private fun MessageHeader(") }
    private val activity by lazy { DeclarationSource.kotlinLines(DeclarationSource.file(ACTIVITY)) }

    @Test fun `the list row names, places the address and labels the monogram from the book's sender`() {
        assertEquals(
            "the row's sender must be the `From:` passed through the book once, and the name, the grey " +
                "address and the monogram's label all read that one value",
            listOf(
                "val sender = withAddressBookName(email.from.firstOrNull(), LocalSenderNames.current)",
                "val senderName = sender?.display() ?: stringResource(R.string.message_unknown_sender)",
                "val senderAddress = listAddressBesideName(sender, rowShowsRecipients = showRecipients)",
            ),
            row.filter { Regex("""^val sender\w* =""").containsMatchIn(it) },
        )
    }

    @Test fun `the open message's header labels and writes the book's sender, and seeds the colour from the address`() {
        assertEquals(
            "the header's sender must be passed through the book once; the monogram's seed stays the " +
                "address, its label and the name line read `shownSender`",
            listOf(
                "val sender = msg.header.from.firstOrNull()",
                "val shownSender = withAddressBookName(sender, LocalSenderNames.current)",
                "seed = (recipient ?: sender)?.email ?: \"?\",",
                "label = (recipient ?: shownSender)?.display() ?: \"?\",",
                "shownSender?.display() ?: stringResource(R.string.message_unknown_sender)",
            ),
            header.filter { Regex("""\b(shownSender|sender)\b""").containsMatchIn(it) },
        )
    }

    @Test fun `the activity provides the container's book`() {
        assertEquals(
            listOf(
                "val senderNames by application.container.senderNames.names.collectAsState()",
                "LocalSenderNames provides senderNames,",
            ),
            activity.filter { "senderNames" in it },
        )
    }

    private companion object {
        const val LIST = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
        const val MESSAGE = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val ACTIVITY = "app/src/main/kotlin/app/sterna/MainActivity.kt"
    }
}
