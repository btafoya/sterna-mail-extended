package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mask that keeps a swiped row off the list between the end of its flight and the list
 * generation that no longer holds it, executed. `InboxViewModel` cannot be built on the JVM; that
 * it is these transitions the view model calls, with these arguments, is pinned as text by
 * `SwipeMaskWiringTest`.
 */
class SwipeMaskTest {

    private val key = EmailKey("acc", "m1")
    private val other = EmailKey("acc", "m2")

    private fun row(k: EmailKey, threadCount: Int = 1) =
        InboxRow(Email(id = k.emailId, accountId = k.accountId), threadCount = threadCount, unread = false)

    private fun SwipeMask.hides(k: EmailKey, threadCount: Int = 1) = hidesSwipedRow(row(k, threadCount), hiddenKeys)

    @Test fun `a posed key hides its one-message row at once`() {
        val mask = SwipeMask().posed(key)

        assertTrue(
            "the mask is posed at the gesture, before any eviction: the row must be off the list " +
                "from the first frame after the flight, or the blank band this exists for is back",
            mask.hides(key),
        )
        assertFalse("and only that row", mask.hides(other))
        assertFalse(
            "the account is part of the key: two accounts of one server share message ids (#92)",
            hidesSwipedRow(row(EmailKey("acc2", "m1")), mask.hiddenKeys),
        )
    }

    @Test fun `a conversation of several messages is never hidden by the mask`() {
        val mask = SwipeMask().posed(key)

        assertFalse(
            "a swiped child whose id is also its conversation's representative leaves the other " +
                "messages in the folder: hiding that row hides mail that did not move",
            mask.hides(key, threadCount = 2),
        )
    }

    @Test fun `no generation lifts the mask before the eviction has returned`() {
        val g1 = Any()
        val g2 = Any()

        val mask = SwipeMask().received(g1).posed(key).received(g2)

        assertTrue(
            "a generation read before the eviction still holds the row: lifting on it puts the " +
                "row back under the finger that just threw it away",
            mask.hides(key),
        )
    }

    @Test fun `once evicted, the mask holds on the generation it saw and lifts on the next one`() {
        val g1 = Any()
        val g2 = Any()
        val armed = SwipeMask().received(g1).posed(key).removalBegun(key, evicted = true)

        val replayed = armed.received(g1)
        assertTrue(
            "a re-subscription replays the SAME generation out of cachedIn, read before the " +
                "eviction: it must not count as the list having caught up",
            replayed.hides(key),
        )

        val caughtUp = replayed.received(g2)
        assertFalse("the first distinct generation read Room after the eviction", caughtUp.hides(key))
        assertEquals("and the key is gone from the state, not merely unfiltered", emptySet<EmailKey>(), caughtUp.hiddenKeys)
    }

    @Test fun `an eviction that found nothing lifts the mask at once`() {
        val mask = SwipeMask().received(Any()).posed(key).removalBegun(key, evicted = false)

        assertEquals(
            "nothing was cached, so no generation is coming to lift it: a key left here hides " +
                "the row for as long as the list shows the same generation",
            emptySet<EmailKey>(),
            mask.hiddenKeys,
        )
    }

    @Test fun `a lift takes its own keys and no others`() {
        val mask = SwipeMask().posed(key).posed(other).lifted(listOf(key))

        assertFalse(mask.hides(key))
        assertTrue("a second row still in flight keeps its mask", mask.hides(other))
    }

    @Test fun `an eviction returning after its key was lifted does not mask it again`() {
        val mask = SwipeMask().received(Any()).posed(key).lifted(listOf(key)).removalBegun(key, evicted = true)

        assertEquals(
            "an Undo pressed while beginRemoval was still out lifted this key: arming it again " +
                "would hide the row the reader just asked back",
            emptySet<EmailKey>(),
            mask.hiddenKeys,
        )
    }

    @Test fun `a transition that changes nothing is the same mask`() {
        val g1 = Any()
        val mask = SwipeMask().received(g1).posed(key).removalBegun(key, evicted = true)

        assertEquals(
            "the list writes back every generation it receives: an unequal copy for no change " +
                "re-emits the state flow into itself without end",
            mask,
            mask.received(g1),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `the list hides the row at the gesture, and the state forgets it on the next generation`() = runTest {
        val g1 = Any()
        val g2 = Any()
        val generations = MutableStateFlow(g1)
        val mask = MutableStateFlow(SwipeMask())
        val seen = mutableListOf<MaskedGeneration<Any>>()
        backgroundScope.launch { maskedGenerations(generations, mask).toList(seen) }
        runCurrent()

        mask.update { it.posed(key) }
        runCurrent()
        assertSame(g1, seen.last().generation)
        assertEquals("posing re-filters the generation on screen", setOf(key), seen.last().hidden)

        mask.update { it.removalBegun(key, evicted = true) }
        runCurrent()
        assertEquals("the generation that saw the eviction return still hides it", setOf(key), seen.last().hidden)

        generations.value = g2
        runCurrent()
        assertSame(g2, seen.last().generation)
        assertEquals("the next generation is handed over unmasked", emptySet<EmailKey>(), seen.last().hidden)
        assertEquals(
            "and the flow wrote the lift back: a key kept in the state is unbounded",
            emptySet<EmailKey>(),
            mask.value.hiddenKeys,
        )
        assertEquals(
            "no generation handed to the list twice with the same mask. Seen: $seen",
            seen.size,
            seen.distinct().size,
        )
    }
}
