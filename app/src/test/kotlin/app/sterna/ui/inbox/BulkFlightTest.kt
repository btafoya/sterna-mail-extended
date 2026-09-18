package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.MailRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a bulk action does around its server write, executed. `InboxViewModel` cannot be built on
 * the JVM, so the flight is its own function and this calls it with recording stand-ins; that the
 * view model hands it the mask and the repository is pinned as text by `SwipeMaskWiringTest` and
 * `BulkSelectionWiringTest`.
 *
 * Every stand-in appends to one journal, so the assertions are about order as much as about
 * arguments: the whole point of the flight is that the rows are gone before the write leaves.
 */
class BulkFlightTest {

    private val journal = mutableListOf<String>()

    private fun key(account: String, id: String) = EmailKey(account, id)

    private fun group(account: String, vararg ids: String) =
        BulkGroup(credentials = account, accountId = account, rows = ids.map { BulkRow(key(account, it), it) })

    /** Tickets that hold everything they are asked for, and writes that answer [verdicts]. */
    private suspend fun fly(
        groups: List<BulkGroup<String>>,
        held: (String, List<String>) -> List<String>? = { _, ids -> ids },
        verdicts: suspend (String, List<String>) -> MailRepository.BulkResult =
            { _, ids -> MailRepository.BulkResult(ids.toSet(), emptySet()) },
    ) = flyBulkGroups(
        groups = groups,
        pose = { keys -> journal += "pose ${keys.joinToString(",") { it.text() }}" },
        begun = { key, evicted -> journal += "begun ${key.text()} evicted=$evicted" },
        lift = { keys -> journal += "lift ${keys.joinToString(",") { it.text() }}" },
        begin = { accountId, ids ->
            journal += "begin $accountId ${ids.joinToString(",")}"
            held(accountId, ids)?.let { MailRepository.InFlightBatch(accountId, it) }
        },
        write = { credentials, ids ->
            journal += "write $credentials ${ids.joinToString(",")}"
            verdicts(credentials, ids)
        },
        end = { ticket, restore ->
            // The real one is `endRemovalAll`: it writes to Room, so it suspends, and under a
            // cancelled Job it throws at its first suspension. A stand-in that never suspends
            // cannot tell an uncancellable close from a cancellable one.
            yield()
            journal += "end ${ticket.accountId} restore=${restore.sorted().joinToString(",")}"
        },
    )

    private fun EmailKey.text() = "$accountId/$emailId"

    @Test fun `the rows are masked and their ticket taken before the write leaves`() = runTest {
        fly(listOf(group("a", "m1", "m2")))

        assertEquals(
            "the mask and the eviction must both be done with before the first write: they are " +
                "the only thing that takes the lines off the list, and the write is a round trip " +
                "seconds long on a phone. Journal was:\n" + journal.joinToString("\n"),
            listOf(
                "pose a/m1,a/m2",
                "begin a m1,m2",
                "begun a/m1 evicted=true",
                "begun a/m2 evicted=true",
                "write a m1,m2",
                "end a restore=",
                "lift ",
            ),
            journal,
        )
    }

    @Test fun `only what the server refused comes back, and only it is unmasked`() = runTest {
        val flown = fly(
            listOf(group("a", "m1", "m2", "m3")),
            verdicts = { _, _ -> MailRepository.BulkResult(setOf("m1", "m3"), setOf("m2"), dest = "archive") },
        )

        assertEquals(
            "the ticket must be closed on the refused ids alone. Restoring the whole selection " +
                "puts every archived row back on screen a second after it left. Journal was:\n" +
                journal.joinToString("\n"),
            listOf("end a restore=m2", "lift a/m2"),
            journal.takeLast(2),
        )
        assertEquals(
            "and the verdict is handed back whole, per group: what may be offered as an Undo is " +
                "the caller's decision, and it reads `succeeded` off this.",
            listOf(setOf("m1", "m3") to setOf("m2")),
            flown.map { (_, result) -> result.succeeded to result.failed },
        )
    }

    @Test fun `an offline batch puts every row back, with no second call`() = runTest {
        fly(
            listOf(group("a", "m1", "m2")),
            verdicts = { _, ids -> MailRepository.BulkResult(emptySet(), ids.toSet()) },
        )

        assertEquals(
            "nothing left, so everything comes home, and the put-back is the ticket's own: no " +
                "network at all, which is the whole point offline. Journal was:\n" +
                journal.joinToString("\n"),
            listOf(
                "pose a/m1,a/m2",
                "begin a m1,m2",
                "begun a/m1 evicted=true",
                "begun a/m2 evicted=true",
                "write a m1,m2",
                "end a restore=m1,m2",
                "lift a/m1,a/m2",
            ),
            journal,
        )
    }

    @Test fun `each account gets its own ticket, and a refusal goes back to the account that refused it`() = runTest {
        fly(
            listOf(group("a", "m1", "m2"), group("b", "m1", "m9")),
            verdicts = { account, _ ->
                if (account == "a") MailRepository.BulkResult(setOf("m1", "m2"), emptySet())
                else MailRepository.BulkResult(setOf("m9"), setOf("m1"))
            },
        )

        assertEquals(
            "two accounts of one server share message ids (#31): a ticket that spanned both would " +
                "put account b's refused m1 back under account a, or take account a's departed m1 " +
                "back out of the list. Both tickets must also be open before either write leaves. " +
                "Journal was:\n" + journal.joinToString("\n"),
            listOf(
                "pose a/m1,a/m2,b/m1,b/m9",
                "begin a m1,m2",
                "begun a/m1 evicted=true",
                "begun a/m2 evicted=true",
                "begin b m1,m9",
                "begun b/m1 evicted=true",
                "begun b/m9 evicted=true",
                "write a m1,m2",
                "write b m1,m9",
                "end a restore=",
                "lift ",
                "end b restore=m1",
                "lift b/m1",
            ),
            journal,
        )
    }

    @Test fun `a row the ticket never held is unmasked at once`() = runTest {
        fly(
            listOf(group("a", "m1", "m2")),
            held = { _, _ -> listOf("m1") },
        )

        assertEquals(
            "m2 was never in the cache, so no generation is coming that could lift its key, and a " +
                "key left standing hides that message from every page for the life of the process. " +
                "Journal was:\n" + journal.joinToString("\n"),
            listOf("begun a/m1 evicted=true", "begun a/m2 evicted=false"),
            journal.filter { it.startsWith("begun ") },
        )
    }

    @Test fun `a refused ticket is never closed, and its rows were never taken out`() = runTest {
        fly(
            listOf(group("a", "m1"), group("b", "m2")),
            held = { accountId, ids -> ids.takeIf { accountId == "b" } },
        )

        assertEquals(
            "a null ticket evicted nothing, so there is nothing to close: closing one would be a " +
                "put-back of rows that never left, counted twice into their folder. Journal was:\n" +
                journal.joinToString("\n"),
            listOf("end b restore="),
            journal.filter { it.startsWith("end ") },
        )
    }

    @Test fun `a ticket that throws closes what was taken and unmasks what was never tried`() = runTest {
        val thrown = runCatching {
            fly(
                listOf(group("a", "m1"), group("b", "m2")),
                held = { accountId, ids ->
                    if (accountId == "a") ids else error("the marker store refused the batch")
                },
            )
        }.exceptionOrNull()

        assertEquals("the failure must still reach the caller", IllegalStateException::class.java, thrown?.javaClass)
        assertEquals(
            "taking the tickets is a suspending Room write, and it throws on a cancelled scope " +
                "(a back gesture while the markers are being written) as much as on a Room " +
                "failure. Thrown from outside the close, account a's ticket is never closed — its " +
                "keys then hide those messages from every page for the life of the process — and " +
                "account b's rows, which nothing evicted, stay masked with no generation coming " +
                "that could ever unmask them, invisible in the list though they never moved. " +
                "Journal was:\n" + journal.joinToString("\n"),
            listOf("end a restore=m1", "lift a/m1", "lift b/m2"),
            journal.filter { it.startsWith("end ") || it.startsWith("lift ") },
        )
        assertEquals(
            "and nothing is written for a selection whose eviction failed",
            emptyList<String>(),
            journal.filter { it.startsWith("write ") },
        )
    }

    @Test fun `a flight cancelled mid-write still closes every ticket and brings back what it never sent`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val flight = launch {
            fly(
                listOf(group("a", "m1"), group("b", "m2")),
                verdicts = { account, ids ->
                    if (account == "a") {
                        writing.complete(Unit)
                        awaitCancellation()
                    }
                    MailRepository.BulkResult(ids.toSet(), emptySet())
                },
            )
        }
        writing.await()

        flight.cancelAndJoin()

        assertEquals(
            "the scope dies with the activity, and the close has to outlive it: `endRemovalAll` " +
                "suspends at its first Room write, so a close left cancellable throws there and " +
                "not one ticket is shut. Ten messages then sit in the folder on the server and " +
                "nowhere in the app, their folder counted down, until the process dies. A write " +
                "cut off mid-flight moved nothing, so both accounts' rows come home, b's included: " +
                "it was never even written. Journal was:\n" + journal.joinToString("\n"),
            listOf("end a restore=m1", "lift a/m1", "end b restore=m2", "lift b/m2"),
            journal.filter { it.startsWith("end ") || it.startsWith("lift ") },
        )
    }

    @Test fun `an empty selection touches neither the mask nor the repository`() = runTest {
        val flown = fly(emptyList())

        assertEquals(emptyList<String>(), journal)
        assertEquals(emptyList<Pair<BulkGroup<String>, MailRepository.BulkResult>>(), flown)
    }

    // -- who may be flown under which account ---------------------------------------------------

    @Test fun `a row whose key names another account is flown under nobody`() {
        val mine = BulkRow(key("a", "m1"), "m1")
        val unnamed = BulkRow(EmailKey(null, "m2"), "m2")
        val foreign = BulkRow(key("b", "m1"), "m1")

        val grouped = bulkGroupUnder(credentials = "creds-a", accountId = "a", rows = listOf(mine, unnamed, foreign))

        assertEquals(
            "a key naming account b reaches account a's credentials only because b no longer " +
                "resolves — signed out, its rows still drawn in the unified view. Its ticket taken " +
                "here evicts a's message of the SAME id and counts a's folder down (#31): the " +
                "reader archives one account's line and another account's line vanishes.",
            listOf(mine, unnamed),
            grouped.group?.rows,
        )
        assertEquals(listOf(key("b", "m1")), grouped.refused)
        assertEquals("credentials-a", "creds-a", grouped.group?.credentials)
    }

    @Test fun `an account left with nothing to fly is no group at all`() {
        val grouped = bulkGroupUnder(credentials = "creds-a", accountId = "a", rows = listOf(BulkRow(key("b", "m1"), "m1")))

        assertNull("an empty batch would still take a ticket and drop the sync cursors", grouped.group)
        assertEquals(listOf(key("b", "m1")), grouped.refused)
    }

    // -- a group written one message at a time --------------------------------------------------

    @Test fun `a per-message mover answers as one verdict`() = runTest {
        val refusals = mutableListOf<Throwable>()

        val result = writeOneByOne(listOf("m1", "m2", "m3"), refusal = { refusals += it }) { id ->
            journal += "write $id"
            when (id) {
                "m2" -> throw IllegalStateException("no network")
                else -> "archive-1"
            }
        }

        assertEquals("every id is written, the refused one included", listOf("write m1", "write m2", "write m3"), journal)
        assertEquals(setOf("m1", "m3"), result.succeeded)
        assertEquals(
            "a refused id is `failed`, which is what puts its row back: read as a success, the " +
                "line stays off a list the message is still in until the next full sync",
            setOf("m2"),
            result.failed,
        )
        assertEquals("archive-1", result.dest)
        assertEquals("and the throwable is handed on, once", 1, refusals.size)
    }

    @Test fun `a write that moved nothing puts its row back and raises nothing`() = runTest {
        val refusals = mutableListOf<Throwable>()

        val result = writeOneByOne(listOf("m1", "m2"), refusal = { refusals += it }) { id ->
            if (id == "m1") null else "archive-1"
        }

        assertEquals(
            "a mover answering null moved nothing: the message is in the folder the reader is " +
                "looking at, so its row has to come back",
            setOf("m1"),
            result.failed,
        )
        assertEquals(setOf("m2"), result.succeeded)
        assertEquals(
            "…and nothing was refused: a conversation already in the folder it was asked to go " +
                "to would announce a failure that did not happen",
            emptyList<Throwable>(),
            refusals,
        )
        assertEquals(
            "the destination is the one a write really named, never the null of a no-op",
            "archive-1",
            result.dest,
        )
    }

    @Test fun `a no-op after a landing keeps the folder the landing named`() = runTest {
        val result = writeOneByOne(listOf("m1", "m2"), refusal = { }) { id -> if (id == "m1") "archive-1" else null }

        assertEquals(setOf("m1"), result.succeeded)
        assertEquals("the message already in the archive never moved, so its row comes back", setOf("m2"), result.failed)
        assertEquals(
            "the group's destination is the first folder a write really named, and it must " +
                "survive the writes that name none: overwritten by the last answer, a conversation " +
                "whose last member is already in the target lands `null`, which an UndoEntry reads " +
                "as destroyed — the count reversal has no folder to credit and the restore no " +
                "target",
            "archive-1",
            result.dest,
        )
    }

    @Test fun `a group where nothing moved lands nowhere`() = runTest {
        val result = writeOneByOne(listOf("m1"), refusal = { }) { null }

        assertEquals(setOf("m1"), result.failed)
        assertEquals(emptySet<String>(), result.succeeded)
        assertNull("no folder was named, and an Undo reverses a move by the folder it landed in", result.dest)
    }

    // -- reading the verdicts back --------------------------------------------------------------

    private val twoAccounts = listOf(
        group("a", "m1") to MailRepository.BulkResult(setOf("m1"), emptySet(), dest = "archiveA"),
        group("b", "m1", "m2") to MailRepository.BulkResult(setOf("m2"), setOf("m1"), dest = "archiveB"),
    )

    @Test fun `the keys put back are the refused ones, matched inside their own account`() {
        assertEquals(
            "`failed`, never `succeeded`: read the wrong side and ten archived messages are " +
                "announced as failures and put back into an open search, while the list, which " +
                "reads the flight itself, stays right — so only the sentence lies and the defect " +
                "lives on. And matched per group: a's m1 left, b's m1 did not.",
            listOf(key("b", "m1")),
            refusedKeys(twoAccounts),
        )
    }

    @Test fun `the keys that moved carry the folder their own batch landed in`() {
        assertEquals(
            "each group's destination, not the last one seen: an Undo reverses the drawer count " +
                "with the folder the message actually went to.",
            listOf(key("a", "m1") to "archiveA", key("b", "m2") to "archiveB"),
            movedKeys(twoAccounts),
        )
    }
}
