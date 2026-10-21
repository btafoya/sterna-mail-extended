package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [keysToDismiss], which banners a read mark from the list may take down. A banner is the only
 * trace left of a mail whose write was refused, so only a write that returned may cost one.
 */
class ReadMarkDismissalTest {

    private val a = EmailKey("acc", "a")
    private val b = EmailKey("acc", "b")
    private val c = EmailKey("other", "c")
    private val ok: Result<Unit> = Result.success(Unit)
    private val refused: Result<Unit> = Result.failure(IOException("offline"))

    @Test fun aRefusedWriteKeepsItsBanner() {
        assertEquals(emptyList<EmailKey>(), keysToDismiss(true, listOf(a to refused)))
    }

    @Test fun onlyTheWritesThatReturnedLoseTheirBanner() {
        assertEquals(
            listOf(a, c),
            keysToDismiss(true, listOf(a to ok, b to refused, c to ok)),
        )
    }

    @Test fun markingUnreadTakesNoBannerDown() {
        assertEquals(emptyList<EmailKey>(), keysToDismiss(false, listOf(a to ok, b to ok)))
    }

    @Test fun aCancelledWriteKeepsItsBanner() {
        assertEquals(
            emptyList<EmailKey>(),
            keysToDismiss(true, listOf(a to Result.failure(CancellationException("left")))),
        )
    }

    @Test fun everyWriteReturnedEveryBannerGoes() {
        assertEquals(listOf(a, b, c), keysToDismiss(true, listOf(a to ok, b to ok, c to ok)))
    }
}
