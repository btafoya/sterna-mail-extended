package app.sterna.core.data.mail

import app.sterna.core.data.db.ContactRow
import app.sterna.core.jmap.model.ContactChanges
import app.sterna.core.jmap.model.ContactsSnapshot
import app.sterna.core.jmap.model.ServerContact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [refreshServerContacts] run over [SqlServerContactDao], the shipped statements on a real SQLite.
 * The server's answers are handed in; which of the two reads is asked, and with which state, is
 * recorded. Every expected row is written out.
 */
class ServerContactsRefreshTest {

    private val dao = SqlServerContactDao()

    @After fun close() = dao.db.close()

    private val asked = mutableListOf<String>()

    private fun refresh(
        accountId: String = "accA",
        all: ContactsSnapshot? = null,
        changes: ContactChanges? = null,
    ) = runBlocking {
        refreshServerContacts(
            accountId = accountId,
            dao = dao,
            readAll = { asked += "all"; all },
            readChanges = { since -> asked += "changes since $since"; changes },
        )
    }

    private val firstBook = ContactsSnapshot(
        contacts = listOf(
            ServerContact("c1", "Alex Martin", listOf("alex@example.org", "alex@work.example")),
            ServerContact("c2", "Jordan", listOf("jordan@example.org")),
            ServerContact("c3", "", listOf("noname@example.org")),
        ),
        state = "s1",
    )

    private val firstRows = listOf(
        "accA|c1|Alex Martin|alex@example.org",
        "accA|c1|Alex Martin|alex@work.example",
        "accA|c2|Jordan|jordan@example.org",
        "accA|c3||noname@example.org",
    )

    @Test fun `no stored state reads the whole book and stores it with its state`() {
        refresh(all = firstBook)

        assertEquals(listOf("all"), asked)
        assertEquals(firstRows, dao.rows())
        assertEquals(listOf("accA=s1"), dao.states())
    }

    @Test fun `a stored state asks the delta since it, and applies created, updated and destroyed`() {
        refresh(all = firstBook)
        asked.clear()

        refresh(
            changes = ContactChanges(
                changed = listOf(
                    ServerContact("c1", "Alex M.", listOf("alex@example.org")),
                    ServerContact("c4", "Sam", listOf("sam@example.org")),
                ),
                destroyed = listOf("c2"),
                newState = "s2",
                replacesAll = false,
            ),
        )

        assertEquals(listOf("changes since s1"), asked)
        assertEquals(
            "c1 lost its second address and its old name, c2 is gone, c3 untouched, c4 new",
            listOf(
                "accA|c1|Alex M.|alex@example.org",
                "accA|c3||noname@example.org",
                "accA|c4|Sam|sam@example.org",
            ),
            dao.rows(),
        )
        assertEquals(listOf("accA=s2"), dao.states())
    }

    @Test fun `replacesAll drops every card absent from the answer`() {
        refresh(all = firstBook)

        refresh(
            changes = ContactChanges(
                changed = listOf(ServerContact("c3", "Now named", listOf("noname@example.org"))),
                destroyed = emptyList(),
                newState = "s3",
                replacesAll = true,
            ),
        )

        assertEquals(listOf("accA|c3|Now named|noname@example.org"), dao.rows())
        assertEquals(listOf("accA=s3"), dao.states())
    }

    @Test fun `no contacts capability on a first pass writes nothing at all`() {
        refresh(all = null)

        assertEquals(listOf("all"), asked)
        assertEquals(emptyList<String>(), dao.writes)
        assertEquals(emptyList<String>(), dao.rows())
        assertEquals(emptyList<String>(), dao.states())
    }

    @Test fun `no contacts capability on a delta pass writes nothing and keeps the copy`() {
        refresh(all = firstBook)
        dao.writes.clear()

        refresh(changes = null)

        assertEquals(emptyList<String>(), dao.writes)
        assertEquals(firstRows, dao.rows())
        assertEquals(listOf("accA=s1"), dao.states())
    }

    @Test fun `a read that fails leaves the copy and its state as they were`() {
        refresh(all = firstBook)
        dao.writes.clear()

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                refreshServerContacts("accA", dao, readAll = { error("not asked") }) { throw java.io.IOException("offline") }
            }
        }

        assertEquals(emptyList<String>(), dao.writes)
        assertEquals(firstRows, dao.rows())
        assertEquals(listOf("accA=s1"), dao.states())
    }

    @Test fun `a delta without a new state forgets the old one, so the next pass reads everything`() {
        refresh(all = firstBook)

        refresh(changes = ContactChanges(emptyList(), listOf("c3"), newState = null, replacesAll = false))

        assertEquals(firstRows - "accA|c3||noname@example.org", dao.rows())
        assertEquals(emptyList<String>(), dao.states())
    }

    @Test fun `one account's pass leaves another account's copy alone`() {
        refresh(accountId = "accB", all = ContactsSnapshot(listOf(ServerContact("c1", "Other", listOf("o@example.org"))), "b1"))
        refresh(accountId = "accA", all = firstBook)

        refresh(accountId = "accA", changes = ContactChanges(listOf(ServerContact("c9", "", listOf("n@example.org"))), emptyList(), "s2", replacesAll = true))

        assertEquals(listOf("accA|c9||n@example.org", "accB|c1|Other|o@example.org"), dao.rows())
        assertEquals(listOf("accA=s2", "accB=b1"), dao.states())
    }

    // --- the DAO's read and its purge ---------------------------------------------------------------

    @Test fun `the search answers only the account asked, on name or address, within the limit`() {
        refresh(accountId = "accB", all = ContactsSnapshot(listOf(ServerContact("b", "Alex Other", listOf("alex@other.example"))), "b1"))
        refresh(accountId = "accA", all = firstBook)

        val byName = runBlocking { dao.search("accA", "alex", 10) }
        val byAddress = runBlocking { dao.search("accA", "jordan@", 10) }
        val limited = runBlocking { dao.search("accA", "example", 2) }

        assertEquals(
            listOf(ContactRow("alex@example.org", "Alex Martin"), ContactRow("alex@work.example", "Alex Martin")),
            byName,
        )
        assertEquals(listOf(ContactRow("jordan@example.org", "Jordan")), byAddress)
        assertEquals(2, limited.size)
        assertEquals(listOf(ContactRow("alex@other.example", "Alex Other")), runBlocking { dao.search("accB", "alex", 10) })
    }

    @Test fun `deleteForAccount takes the rows and the state of that account only`() {
        refresh(accountId = "accB", all = ContactsSnapshot(listOf(ServerContact("b", "B", listOf("b@example.org"))), "b1"))
        refresh(accountId = "accA", all = firstBook)

        runBlocking { dao.deleteForAccount("accA") }

        assertEquals(listOf("accB|b|B|b@example.org"), dao.rows())
        assertEquals(listOf("accB=b1"), dao.states())
    }
}
