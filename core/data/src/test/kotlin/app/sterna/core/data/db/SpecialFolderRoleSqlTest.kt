package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import app.sterna.core.data.mail.specialFolderRoleDelta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The three statements a designated special folder runs, read out of the shipped DAO and executed
 * against the `mailboxes` table (#227). A recording stand-in cannot see any of this: it is handed
 * the arguments and never the WHERE they land in.
 *
 * Two accounts of one IMAP server are set up throughout, because there a folder id is its path and
 * `INBOX.Trash` is the same string on both. A write that forgot its account would re-role the
 * namesake: the other account's real bin marked out of search, or an ordinary folder of hers made
 * the bin and handed every delete.
 */
class SpecialFolderRoleSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE mailboxes(
                    accountId TEXT, id TEXT, name TEXT, role TEXT, parentId TEXT,
                    sortOrder INTEGER, totalEmails INTEGER, unreadEmails INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun folder(accountId: String, id: String, role: String?, name: String = id, parentId: String? = null) =
        db.prepareStatement(
            "INSERT INTO mailboxes(accountId, id, name, role, parentId, sortOrder, totalEmails, " +
                "unreadEmails) VALUES(?, ?, ?, ?, ?, 0, 0, 0)",
        ).use { ps ->
            ps.setString(1, accountId); ps.setString(2, id); ps.setString(3, name)
            ps.setString(4, role); ps.setString(5, parentId)
            ps.executeUpdate()
        }

    /** The shipped `setRole` statement, bound by NAME and run. Bound by name, never by position,
     *  so a statement that dropped a clause still runs here and shows what it does to the table. */
    private fun setRole(accountId: String, id: String, role: String?) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("MailboxDao", "setRole"))
        val args = mapOf("role" to role, "accountId" to accountId, "id" to id)
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                // containsKey, not a null-coalescing read: `role` is legitimately null here.
                check(args.containsKey(name)) { "nothing to bind for :$name" }
                ps.setString(i + 1, args[name])
            }
            ps.executeUpdate()
        }
    }

    /** The shipped `rolesForAccount` statement, run and projected as the caller receives it. */
    private fun rolesForAccount(accountId: String): List<MailboxIdRole> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("MailboxDao", "rolesForAccount"))
        assertEquals(listOf("accountId"), order)
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MailboxIdRole(rs.getString("id"), rs.getString("role"))) }
            }
        }
    }

    /** The shipped `idsForAnyName` statement, run over [names]. */
    private fun idsForAnyName(accountId: String, names: List<String>): List<String> {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("MailboxDao", "idsForAnyName"),
            mapOf("names" to names.size),
        )
        assertEquals(listOf("accountId") + List(names.size) { "names" }, order)
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, accountId)
            names.forEachIndexed { i, name -> ps.setString(i + 2, name) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /**
     * The mutation this test exists for: drop `accountId = :accountId` from the update and the
     * whole suite stays green while designating a folder on one account re-roles the folder of the
     * same path on every other.
     */
    @Test fun `a role written on one account leaves the namesake of the other alone`() {
        folder("acc-a", "INBOX.Trash", "trash")
        folder("acc-b", "INBOX.Trash", "trash")

        setRole("acc-a", "INBOX.Trash", "~trash")

        assertEquals(listOf(MailboxIdRole("INBOX.Trash", "~trash")), rolesForAccount("acc-a"))
        assertEquals(listOf(MailboxIdRole("INBOX.Trash", "trash")), rolesForAccount("acc-b"))
    }

    /** Writing no role at all is a value the statement must carry: the folder ends up with none,
     *  which is what an undesignation leaves behind. */
    @Test fun `a null role is written as no role`() {
        folder("acc-a", "Projets", "trash")

        setRole("acc-a", "Projets", null)

        assertEquals(listOf(MailboxIdRole("Projets", null)), rolesForAccount("acc-a"))
    }

    /** The reading half: this account's folders only, the mark kept as stored (the delta needs it
     *  to hand a role back), ordered by id so two runs write in the same order. */
    @Test fun `the rows read back are this account's, marks included`() {
        folder("acc-a", "INBOX.Trash", "~trash")
        folder("acc-a", "Corbeille", null)
        folder("acc-a", "INBOX", "inbox")
        folder("acc-b", "INBOX.Trash", "trash")

        assertEquals(
            listOf(
                MailboxIdRole("Corbeille", null),
                MailboxIdRole("INBOX", "inbox"),
                MailboxIdRole("INBOX.Trash", "~trash"),
            ),
            rolesForAccount("acc-a"),
        )
    }

    /**
     * The whole gesture, executed end to end: read the rows, decide, write each one back. The bin
     * the user designates ends up the only holder of the role, and the folder the server elected
     * carries the mark.
     */
    @Test fun `designating a bin leaves exactly one holder of the role in the table`() {
        folder("acc-a", "INBOX", "inbox")
        folder("acc-a", "INBOX.Trash", "trash")
        folder("acc-a", "Corbeille", null)
        folder("acc-b", "INBOX.Trash", "trash")

        specialFolderRoleDelta(rolesForAccount("acc-a"), "trash", null, "Corbeille", idsArePaths = true)
            .forEach { setRole("acc-a", it.id, it.role) }

        assertEquals(
            listOf(
                MailboxIdRole("Corbeille", "trash"),
                MailboxIdRole("INBOX", "inbox"),
                MailboxIdRole("INBOX.Trash", "~trash"),
            ),
            rolesForAccount("acc-a"),
        )
        assertEquals(listOf(MailboxIdRole("INBOX.Trash", "trash")), rolesForAccount("acc-b"))
    }

    /**
     * Every folder the names recognise, top-level before nested, which is the order the caller
     * relies on: it takes the first that is not designated for another role, and a `LIMIT 1` would
     * hand it the designated one and nothing else.
     */
    @Test fun `the bins by name come back root first, all of them`() {
        folder("acc-a", "INBOX", "inbox", name = "INBOX")
        folder("acc-a", "INBOX.Vieux/Trash", null, name = "Trash", parentId = "INBOX.Vieux")
        folder("acc-a", "Corbeille", null, name = "Corbeille")
        folder("acc-b", "Bin", null, name = "Bin")

        assertEquals(
            listOf("Corbeille", "INBOX.Vieux/Trash"),
            idsForAnyName("acc-a", listOf("trash", "corbeille", "bin")),
        )
    }
}
