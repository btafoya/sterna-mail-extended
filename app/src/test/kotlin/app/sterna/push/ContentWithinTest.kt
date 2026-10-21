package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The banner's own Mark as read and Delete re-post the group summary under `goAsync`, whose budget
 * the system enforces by killing the process. The setting read there must give up on time and fall
 * back to the most private content rather than hold the broadcast past it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentWithinTest {
    @Test fun `a read that never answers gives up at the budget, on the most private content`() = runTest {
        val hanging = flow<NotificationContent> { awaitCancellation() }
        val content = readContentWithin(hanging, NotificationActionReceiver.CONTENT_BUDGET_MS)
        assertEquals(NotificationContent.NONE, content)
        assertEquals(NotificationActionReceiver.CONTENT_BUDGET_MS, currentTime)
    }

    @Test fun `a read that answers is what the summary gets`() = runTest {
        val content = readContentWithin(
            flowOf(NotificationContent.SENDER_AND_SUBJECT), NotificationActionReceiver.CONTENT_BUDGET_MS,
        )
        assertEquals(NotificationContent.SENDER_AND_SUBJECT, content)
    }

    @Test fun `a read that fails falls back to the most private content`() = runTest {
        val failing = flow<NotificationContent> { throw java.io.IOException("datastore") }
        val content = readContentWithin(failing, NotificationActionReceiver.CONTENT_BUDGET_MS)
        assertEquals(NotificationContent.NONE, content)
    }
}
