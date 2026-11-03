package app.sterna.core.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The move memory behind the pickers' suggestions, executed over an in-memory file: the bounds, the
 * one-gesture-one-event rule, the Undo's retraction and the purge of one account.
 */
class MoveMemoryTest {

    private class MapFile : MoveMemoryFile {
        val values = linkedMapOf<String, String>()
        override fun read(accountId: String): String? = values[accountId]
        override fun write(accountId: String, value: String?) {
            if (value == null) values.remove(accountId) else values[accountId] = value
        }
        override fun accountIds(): Set<String> = values.keys.toSet()
    }

    private fun memory(file: MoveMemoryFile = MapFile()) = MoveMemory(file, Dispatchers.Unconfined)

    @Test fun `past two hundred events the oldest goes`() = runTest {
        val memory = memory()
        repeat(MOVE_MEMORY_MAX_EVENTS + 5) { memory.record("a", MoveEvent("f$it", at = it.toLong())) }
        val events = memory.history("a").first().events
        assertEquals(MOVE_MEMORY_MAX_EVENTS, events.size)
        assertEquals("the five oldest must have gone", "f5", events.first().mailboxId)
        assertEquals("f${MOVE_MEMORY_MAX_EVENTS + 4}", events.last().mailboxId)
    }

    @Test fun `an event keeps fifty sender addresses, lower-cased, each once`() {
        val senders = listOf("SENDER1@example.org", "  ") + (0 until 80).map { "Sender$it@Example.ORG" }
        val event = moveEventOf("f", 1L, senders)
        assertEquals("duplicates and blanks merged before the cut", MOVE_MEMORY_MAX_SENDERS, event.senders.size)
        assertEquals("sender1@example.org", event.senders.first())
        assertTrue("an address kept its capitals: ${event.senders}", event.senders.all { it == it.lowercase() })
        assertEquals("sender49@example.org", event.senders.last())
    }

    @Test fun `one gesture is one event, however many messages it moved`() = runTest {
        val memory = memory()
        val fifty = (0 until 50).map { "s$it@example.org" }
        memory.record("a", moveEventOf("Receipts", 10L, fifty))
        assertEquals(1, memory.history("a").first().events.size)
    }

    @Test fun `the Undo takes back the event its gesture recorded, and only that one`() = runTest {
        val memory = memory()
        val kept = moveEventOf("Receipts", 10L, listOf("a@example.org"))
        val undone = moveEventOf("Receipts", 20L, listOf("a@example.org"))
        memory.record("a", kept)
        memory.record("a", undone)
        memory.retract("a", undone)
        assertEquals(listOf(kept), memory.history("a").first().events)
        memory.retract("a", undone)
        assertEquals("retracting twice takes nothing else", listOf(kept), memory.history("a").first().events)
    }

    @Test fun `purging one account leaves the other's memory whole`() = runTest {
        val file = MapFile()
        val memory = memory(file)
        memory.record("a", MoveEvent("A1", 1L))
        memory.record("b", MoveEvent("B1", 1L))
        memory.forget(listOf("a"))
        assertEquals(emptyList<MoveEvent>(), memory.history("a").first().events)
        assertEquals(listOf(MoveEvent("B1", 1L)), memory.history("b").first().events)
        assertEquals("the file must lose the account's value, not just the cache", setOf("b"), file.accountIds())
        assertEquals("a memory built over the same file must not see it either", emptyList<MoveEvent>(), memory(file).history("a").first().events)
    }

    @Test fun `a history names the account it was read for`() = runTest {
        val memory = memory()
        memory.record("b", MoveEvent("Inbox", 1L))
        assertEquals("a", memory.history("a").first().accountId)
        assertEquals(MoveHistory.NONE, memory.history(null).first())
    }

    @Test fun `what was written is read back by another instance over the same file`() = runTest {
        val file = MapFile()
        memory(file).record("a", moveEventOf("Receipts", 5L, listOf("x@example.org")))
        assertEquals(listOf(MoveEvent("Receipts", 5L, setOf("x@example.org"))), memory(file).history("a").first().events)
    }

    @Test fun `an unreadable value reads as an empty memory`() = runTest {
        val file = MapFile().apply { values["a"] = "{not json" }
        assertEquals(emptyList<MoveEvent>(), memory(file).history("a").first().events)
    }

    // ── the purge paths, read out of the shipped source ────────────────────────────────────────

    @Test fun `every removal of an account forgets its moves`() {
        val store = code("core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt")
        assertEquals(
            "AccountStore.remove and removeCascading must each forget the removed accounts' moves, " +
                "or a recreated account inherits another's habits.",
            listOf("moveMemory.forget(listOf(id))", "moveMemory.forget(ids)"),
            store.filter { it.startsWith("moveMemory.forget(") },
        )
        val sweep = code("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt")
        assertEquals(
            "the orphan sweep must inventory the move memory and forget an orphan's.",
            listOf(
                "emailBodyDao.downloadAheadSkipAccountIds() + serverContactDao.accountIds() + moveMemory.accountIds()",
                "runCatching { moveMemory.forget(listOf(accountId)) }",
            ),
            sweep.filter { "moveMemory." in it },
        )
    }

    private fun code(relative: String): List<String> {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { file ->
                return file.readLines().map { it.trim() }
                    .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            }
            File(dir, relative.substringAfter("core/data/")).takeIf { it.isFile }?.let { file ->
                return file.readLines().map { it.trim() }
                    .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            }
            dir = dir.parentFile
        }
        error("cannot find $relative")
    }
}
