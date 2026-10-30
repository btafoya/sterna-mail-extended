package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** The decisions behind the reader's label sheet, run: [labelIdFor], [showLabelsEntry], [sheetLabels], [withLabel], [writeLabelOrRevert]. */
class LabelSheetTest {

    @Test fun `a typed name gives the id the server is sent`() {
        val cases = listOf(
            "Read later" to LabelName.Id("read-later"),
            "  a  b " to LabelName.Id("a-b"),
            "a\tb" to LabelName.Id("a-b"),
            "work/clients" to LabelName.Id("work/clients"),
            "Work" to LabelName.Id("work"),
            "a~b" to LabelName.Id("a~b"),
            "x".repeat(200) to LabelName.Id("x".repeat(200)),
            "" to LabelName.Empty,
            "   " to LabelName.Empty,
            "a\"b" to LabelName.Refused,
            "x(y" to LabelName.Refused,
            "x)y" to LabelName.Refused,
            "x{y" to LabelName.Refused,
            "x]y" to LabelName.Refused,
            "100%" to LabelName.Refused,
            "a*" to LabelName.Refused,
            "a\\b" to LabelName.Refused,
            "é" to LabelName.Refused,
            "café" to LabelName.Refused,
            "a b" to LabelName.Refused,
            "x".repeat(201) to LabelName.Refused,
        )
        assertEquals(cases.map { it.second }, cases.map { labelIdFor(it.first) })
    }

    @Test fun `the length cap is read on the id, after the spaces are folded`() {
        assertEquals(LabelName.Id("a-" + "x".repeat(198)), labelIdFor("a     " + "x".repeat(198)))
    }

    @Test fun `the entry is offered on a JMAP account that already has a label`() {
        assertTrue(showLabelsEntry(isJmap = true, accountLabels = listOf("work")))
        assertFalse(showLabelsEntry(isJmap = true, accountLabels = emptyList()))
        assertFalse(showLabelsEntry(isJmap = false, accountLabels = listOf("work")))
        assertFalse(showLabelsEntry(isJmap = false, accountLabels = emptyList()))
    }

    @Test fun `the sheet lists the account's labels and the message's own, once, sorted`() {
        assertEquals(
            listOf("archive", "new", "Work"),
            sheetLabels(listed = emptyList(), accountLabels = listOf("archive", "Work"), messageLabels = listOf("Work", "new")),
        )
    }

    @Test fun `a label unchecked on the only message carrying it keeps its row while the sheet is open`() {
        val opened = sheetLabels(listed = emptyList(), accountLabels = listOf("solo", "work"), messageLabels = listOf("solo"))
        assertEquals(listOf("solo", "work"), opened)

        assertEquals(
            "the account's labels no longer hold `solo` once it is unchecked, and the row must stay to be checked back",
            listOf("solo", "work"),
            sheetLabels(listed = opened, accountLabels = listOf("work"), messageLabels = emptyList()),
        )
    }

    @Test fun `a label added while the sheet is open keeps its row after it is unchecked`() {
        val added = sheetLabels(listed = listOf("work"), accountLabels = listOf("work"), messageLabels = listOf("new"))
        assertEquals(listOf("new", "work"), sheetLabels(listed = added, accountLabels = listOf("work"), messageLabels = emptyList()))
    }

    @Test fun `a label is held as a true keyword and dropped as no keyword at all`() {
        val email = Email(id = "1", keywords = mapOf("\$seen" to true))

        val held = email.withLabel("work/clients", true)
        assertEquals(mapOf("\$seen" to true, "\$label:work/clients" to true), held.keywords)
        assertEquals(mapOf("\$seen" to true), held.withLabel("work/clients", false).keywords)
    }

    @Test fun `a refused write shows what the cached row holds, and is reported`() {
        val refusal = IllegalStateException("refused")
        val cases = listOf(
            Triple(true, false, false),
            Triple(false, true, true),
            Triple(true, true, true),
            Triple(false, false, false),
        )
        for ((wanted, row, expected) in cases) {
            val shown = mutableListOf<Boolean>()
            val reported = mutableListOf<Throwable>()

            runBlocking {
                writeLabelOrRevert(wanted, write = { throw refusal }, confirmed = { row }, show = { shown += it }, report = { reported += it })
            }

            assertEquals("wanted $wanted, row holds $row", listOf(expected), shown)
            assertEquals(listOf<Throwable>(refusal), reported)
        }
    }

    @Test fun `a refused write on a message with no cached row falls back to the reverse of the tap`() {
        val shown = mutableListOf<Boolean>()

        runBlocking { writeLabelOrRevert(false, write = { error("refused") }, confirmed = { null }, show = { shown += it }, report = {}) }

        assertEquals(listOf(true), shown)
    }

    @Test fun `an uncheck then a recheck queued behind a slow write, then refused, leave the label the server holds shown`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = LatestWriteWins<String, Boolean>()
        val slow = CompletableDeferred<Unit>()
        val row = true
        var shown = true
        val writeX: suspend (Boolean) -> Unit = { wanted ->
            writeLabelOrRevert(wanted, write = { error("offline") }, confirmed = { row }, show = { shown = it }, report = {})
        }

        gate.submit(scope, "y", true) { slow.await() }
        dispatcher.drain()
        shown = false
        gate.submit(scope, "x", false, writeX)
        shown = true
        gate.submit(scope, "x", true, writeX)
        dispatcher.drain()
        slow.complete(Unit)
        dispatcher.drain()

        assertEquals("the server and the row hold the label: its box must stay checked", true, shown)
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test fun `a write the server takes sends the wanted value and changes nothing on screen`() {
        val sent = mutableListOf<Boolean>()
        val shown = mutableListOf<Boolean>()
        val reported = mutableListOf<Throwable>()

        runBlocking { writeLabelOrRevert(true, write = { sent += it }, confirmed = { false }, show = { shown += it }, report = { reported += it }) }

        assertEquals(listOf(true), sent)
        assertEquals(emptyList<Boolean>(), shown)
        assertEquals(emptyList<Throwable>(), reported)
    }
}
