package app.sterna.push

import app.sterna.core.jmap.JmapException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class NotificationActionFailureLabelTest {

    @Test fun `a JMAP refusal is named by a literal and its protocol error type`() {
        assertEquals(
            "JmapException(forbidden)",
            notificationActionFailureLabel(JmapException("Server rejected the move (forbidden)", errorType = "forbidden")),
        )
    }

    @Test fun `a JMAP failure with no error type is the literal alone, never its message`() {
        assertEquals(
            "JmapException",
            notificationActionFailureLabel(JmapException("alex@example.test is over quota")),
        )
    }

    @Test fun `an error type that is not shaped like one is left out`() {
        for (free in listOf("alex@example.test", "over quota", "x".repeat(41), "")) {
            assertEquals(
                "the server chooses this string: <$free> could be anything it holds",
                "JmapException",
                notificationActionFailureLabel(JmapException("refused", errorType = free)),
            )
        }
    }

    @Test fun `any other failure is named by its class, without its message`() {
        assertEquals("IOException", notificationActionFailureLabel(IOException("alex@example.test unreachable")))
    }
}
