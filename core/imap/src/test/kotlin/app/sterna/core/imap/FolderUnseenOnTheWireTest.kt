package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The unread count of folders that are not opened, as it is really asked of a scripted server on
 * loopback: in the folder LIST itself when the server advertised `LIST-STATUS`, one `STATUS` per
 * folder otherwise.
 */
class FolderUnseenOnTheWireTest {

    private val accented = "QA-&AMk-t&AOk-" // QA-Été, as a server announces it.

    /** Wire name to unseen count, in listing order. */
    private val mailboxes = listOf("INBOX" to 3, accented to 2, "Archive" to 0, "Corbeille" to 5)

    /**
     * A server advertising [capabilities] in its LOGIN completion and answering BAD to any LIST
     * return option it did not advertise. [refusing] names answer NO to their `STATUS`, [silent]
     * names get an OK that carries no `* STATUS` line (and none in a combined LIST either), and
     * [refusesCombinedList] is the server that advertises `LIST-STATUS` and rejects it anyway.
     */
    private fun server(
        vararg capabilities: String,
        refusing: Set<String> = emptySet(),
        silent: Set<String> = emptySet(),
        refusesCombinedList: Boolean = false,
    ) = FakeImapServer { tag, line ->
        val advertised = capabilities.toList()
        when {
            line.startsWith("LOGIN") ->
                "$tag OK [CAPABILITY IMAP4rev1 ${advertised.joinToString(" ")}] logged in\r\n"
            line.startsWith("LIST") -> when {
                "STATUS" in line && ("LIST-STATUS" !in advertised || refusesCombinedList) ->
                    "$tag BAD unknown LIST return option\r\n"
                "SPECIAL-USE" in line && "SPECIAL-USE" !in advertised ->
                    "$tag BAD unknown LIST return option\r\n"
                else -> listing(tag, withStatus = "STATUS" in line, silent = silent)
            }
            line.startsWith("LSUB") ->
                "* LSUB (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
                    "* LSUB (\\HasNoChildren) \".\" \"$accented\"\r\n" + ok(tag)
            line.startsWith("STATUS") -> {
                val name = line.substringAfter("STATUS \"").substringBefore("\"")
                val unseen = mailboxes.firstOrNull { it.first == name }?.second
                when {
                    name in refusing || unseen == null -> "$tag NO no such mailbox\r\n"
                    name in silent -> ok(tag)
                    else -> "* STATUS \"$name\" (UNSEEN $unseen)\r\n" + ok(tag)
                }
            }
            else -> ok(tag)
        }
    }

    private fun listing(tag: String, withStatus: Boolean, silent: Set<String>) =
        "* LIST (\\Noselect \\HasChildren) \".\" \"Dossiers\"\r\n" +
            mailboxes.joinToString("") { (name, unseen) ->
                "* LIST (\\HasNoChildren) \".\" \"$name\"\r\n" +
                    if (withStatus && name !in silent) "* STATUS \"$name\" (UNSEEN $unseen)\r\n" else ""
            } + ok(tag)

    private fun FakeImapServer.listLines() = issued().filter { it.startsWith("LIST") }
    private fun FakeImapServer.statusLines() = issued().filter { it.startsWith("STATUS") }

    private val allPaths = listOf("INBOX", "QA-Été", "Archive", "Corbeille")

    @Test
    fun `a server advertising LIST-STATUS is asked for the counts in the LIST it already gets`() {
        server("SPECIAL-USE", "LIST-STATUS").use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen { "INBOX" } }

            // The whole conversation, LOGIN and LOGOUT aside: one command, no CAPABILITY round trip.
            assertEquals(
                listOf("""LIST "" "*" RETURN (SPECIAL-USE STATUS (UNSEEN))"""),
                server.issued().filterNot { it.startsWith("LOGOUT") },
            )
            assertEquals(allPaths, listing.folders.map { it.path })
            assertEquals(mapOf("QA-Été" to 2, "Archive" to 0, "Corbeille" to 5), listing.unseen)
        }
    }

    @Test
    fun `LIST-STATUS without SPECIAL-USE asks for the counts alone`() {
        server("LIST-STATUS").use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen() }

            assertEquals(listOf("""LIST "" "*" RETURN (STATUS (UNSEEN))"""), server.listLines())
            assertEquals(emptyList<String>(), server.statusLines())
            assertEquals(
                mapOf("INBOX" to 3, "QA-Été" to 2, "Archive" to 0, "Corbeille" to 5),
                listing.unseen,
            )
        }
    }

    /**
     * The guard: this server answers BAD to a `STATUS` return option it never advertised, so the
     * folders coming back at all is the witness that the option stayed off the wire.
     */
    @Test
    fun `a server that never advertised LIST-STATUS gets one STATUS per folder, the excluded one aside`() {
        server("SPECIAL-USE").use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen { "INBOX" } }

            assertEquals(listOf("""LIST "" "*" RETURN (SPECIAL-USE)"""), server.listLines())
            assertEquals(
                listOf(
                    """STATUS "$accented" (UNSEEN)""",
                    """STATUS "Archive" (UNSEEN)""",
                    """STATUS "Corbeille" (UNSEEN)""",
                ),
                server.statusLines(),
            )
            assertEquals(allPaths, listing.folders.map { it.path })
            assertEquals(mapOf("QA-Été" to 2, "Archive" to 0, "Corbeille" to 5), listing.unseen)
        }
    }

    @Test
    fun `with no extension at all the LIST is bare and every listed folder is asked`() {
        server().use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen() }

            assertEquals(listOf("""LIST "" "*""""), server.listLines())
            // "Dossiers" is `\Noselect`: it is not listed, so it is not asked either.
            assertEquals(
                listOf("INBOX", accented, "Archive", "Corbeille").map { """STATUS "$it" (UNSEEN)""" },
                server.statusLines(),
            )
            assertEquals(
                mapOf("INBOX" to 3, "QA-Été" to 2, "Archive" to 0, "Corbeille" to 5),
                listing.unseen,
            )
        }
    }

    /** The folder to leave out is only known once the listing is: it is chosen from it. */
    @Test
    fun `the excluded folder is chosen from the folders just listed`() {
        server().use { server ->
            val listing = server.session().use { session ->
                session.listFoldersWithUnseen { folders -> folders.single { it.name == "Corbeille" }.path }
            }

            assertEquals(
                listOf("INBOX", accented, "Archive").map { """STATUS "$it" (UNSEEN)""" },
                server.statusLines(),
            )
            assertEquals(mapOf("INBOX" to 3, "QA-Été" to 2, "Archive" to 0), listing.unseen)
        }
    }

    @Test
    fun `a refused STATUS leaves that folder unknown and the others counted`() {
        server(refusing = setOf("Archive"), silent = setOf("INBOX")).use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen() }

            // Asked after the refusal: the NO did not end the reading.
            assertEquals("""STATUS "Corbeille" (UNSEEN)""", server.statusLines().last())
            assertFalse("a refused folder is unknown, not zero", listing.unseen.containsKey("Archive"))
            assertFalse("an unanswered folder is unknown, not zero", listing.unseen.containsKey("INBOX"))
            assertEquals(mapOf("QA-Été" to 2, "Corbeille" to 5), listing.unseen)
            assertEquals(allPaths, listing.folders.map { it.path })
        }
    }

    @Test
    fun `a folder the combined LIST says nothing about is unknown`() {
        server("LIST-STATUS", silent = setOf("Archive")).use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen() }

            assertEquals(mapOf("INBOX" to 3, "QA-Été" to 2, "Corbeille" to 5), listing.unseen)
            assertEquals(emptyList<String>(), server.statusLines())
        }
    }

    @Test
    fun `a refused combined LIST still brings the folders back, with no count`() {
        server("SPECIAL-USE", "LIST-STATUS", refusesCombinedList = true).use { server ->
            val listing = server.session().use { it.listFoldersWithUnseen() }

            assertEquals(
                listOf(
                    """LIST "" "*" RETURN (SPECIAL-USE STATUS (UNSEEN))""",
                    """LIST "" "*" RETURN (SPECIAL-USE)""",
                ),
                server.listLines(),
            )
            assertEquals(allPaths, listing.folders.map { it.path })
            assertEquals(emptyMap<String, Int>(), listing.unseen)
        }
    }

    @Test
    fun `subscriptions are still read and marked in both modes`() {
        for (capabilities in listOf(arrayOf("LIST-STATUS"), emptyArray())) {
            server(*capabilities).use { server ->
                val listing = server.session().use { it.listFoldersWithUnseen(onlySubscribed = true) }

                assertEquals(listOf("""LSUB "" "*""""), server.issued().filter { it.startsWith("LSUB") })
                assertEquals(
                    listOf("INBOX", "QA-Été"),
                    listing.folders.filter { it.isSubscribed }.map { it.path },
                )
                assertEquals(4, listing.unseen.size)
            }
        }
    }

    // ---- the parser alone ------------------------------------------------------------------

    private fun status(name: Any?, vararg items: Any?): List<Any?> = listOf("*", "STATUS", name, items.toList())

    @Test
    fun `a STATUS line is indexed by its decoded name`() {
        assertEquals(
            mapOf("QA-Été" to 2, "INBOX" to 0),
            parseStatusUnseen(listOf(status(accented, "UNSEEN", "2"), status("INBOX", "UNSEEN", "0"))),
        )
    }

    @Test
    fun `UNSEEN is read by name, whatever else the line carries`() {
        assertEquals(
            mapOf("A" to 7, "B" to 1),
            parseStatusUnseen(
                listOf(
                    status("A", "MESSAGES", "40", "UNSEEN", "7"),
                    status("B", "unseen", "1", "MESSAGES", "9"),
                ),
            ),
        )
    }

    @Test
    fun `a line that states no usable UNSEEN states nothing`() {
        assertEquals(
            emptyMap<String, Int>(),
            parseStatusUnseen(
                listOf(
                    status("A", "MESSAGES", "40"),
                    status("B", "UNSEEN"),
                    status("C", "UNSEEN", "many"),
                    status("D", "UNSEEN", "-1"),
                    status(null, "UNSEEN", "3"),
                    listOf("*", "STATUS", "E"),
                    listOf("*", "LIST", listOf("\\HasNoChildren"), ".", "F"),
                    listOf("*", "3", "EXISTS"),
                ),
            ),
        )
    }
}
