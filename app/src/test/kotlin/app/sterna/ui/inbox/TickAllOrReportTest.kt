package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** "Select all" over a read that fails, executed on [tickAllOrReport]. */
class TickAllOrReportTest {

    private val before = setOf(EmailKey("acct-A", "f1"))
    private var ticks: Set<EmailKey> = before
    private val reported = mutableListOf<Throwable>()

    private suspend fun selectAll(read: suspend () -> Set<EmailKey>) = tickAllOrReport(
        read = read,
        tick = { ticks = it },
        report = { reported += it },
    )

    @Test fun `a read that throws is reported, and the ticks stay as they were`() = runBlocking {
        val tooManyVariables = RuntimeException("too many SQL variables (code 1)")
        selectAll { throw tooManyVariables }
        assertEquals("the failure must reach reportActionFailed", listOf<Throwable>(tooManyVariables), reported)
        assertEquals("a failed read must not touch the ticks", before, ticks)
    }

    @Test fun `a read that answers ticks what it read, and reports nothing`() = runBlocking {
        val all = setOf(EmailKey("acct-A", "f1"), EmailKey("acct-B", "x9"))
        selectAll { all }
        assertEquals(all, ticks)
        assertEquals(emptyList<Throwable>(), reported)
    }

    @Test fun `a cancellation is rethrown, neither reported nor ticked`() = runBlocking {
        val cancelled = CancellationException("scope ended")
        try {
            selectAll { throw cancelled }
            fail("a cancellation must reach the scope that is ending")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
        assertEquals(emptyList<Throwable>(), reported)
        assertEquals(before, ticks)
    }
}
