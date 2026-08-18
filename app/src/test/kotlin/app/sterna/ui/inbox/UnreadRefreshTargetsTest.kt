package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [unreadRefreshTargets] RUN, not read: what a pull-to-refresh in the "unread" view sends to
 * `MailRepository.refreshAccountFolders(extraFolderIds = …)`.
 *
 * The decision this pins is the ARGUMENT, not the call. `InboxViewModel` is an `AndroidViewModel`
 * and cannot be built here, so the one thing a JVM test can do honestly is execute the function that
 * decides the set — which is exactly why the set is decided in a function of its own rather than
 * inline in the refresh. `UnreadRefreshWiringLintTest` is the (source-reading) link that says the
 * refresh hands this function's answer over unaltered.
 */
class UnreadRefreshTargetsTest {

    private val scopes = listOf(
        "a" to "INBOX",
        "a" to "Archive",
        "a" to "Projets",
        // A sibling account on the SAME server, with a folder of the same name AND the same id.
        "b" to "Archive",
        "b" to "INBOX",
    )

    /** The inbox is `includeInbox = true`'s job; the rest of the account's folders are this set's. */
    @Test fun `the account's folders go, its inbox does not`() {
        assertEquals(setOf("Archive", "Projets"), unreadRefreshTargets("a", scopes, "INBOX"))
    }

    /**
     * ⛔ The one that costs data if it is wrong. Only the current account's credentials are in hand:
     * a folder id belonging to account "b" sent to account "a"'s server addresses either nothing or
     * somebody else's homonymous folder on a shared server, and what comes back is written into the
     * cache under "a" (#121/#31). Here "b" holds an `Archive` id identical to "a"'s, so a rule that
     * projected the pairs without filtering the account would LOOK right — the answer would be the
     * same set — until "b" owned a folder "a" does not.
     */
    @Test fun `a sibling account's folder is never sent to this account's server`() {
        val mixed = scopes + ("b" to "Comptabilite")
        assertEquals(setOf("Archive", "Projets"), unreadRefreshTargets("a", mixed, "INBOX"))
        // …and read from the other side: asked for "b", nothing of "a"'s comes back.
        assertEquals(setOf("Archive", "Comptabilite"), unreadRefreshTargets("b", mixed, "INBOX"))
    }

    /** Only THIS account's inbox is removed — "b"'s inbox id was never a candidate to begin with. */
    @Test fun `the inbox removed is the one of the account being refreshed`() {
        val perAccountIds = listOf("a" to "a-inbox", "a" to "a-archive", "b" to "b-inbox")
        assertEquals(setOf("a-archive"), unreadRefreshTargets("a", perAccountIds, "a-inbox"))
        // The same scope list, refreshed as "b": "a"'s inbox id is not this account's inbox, and it
        // is not in the answer either — because it is not "b"'s folder.
        assertEquals(emptySet<String>(), unreadRefreshTargets("b", perAccountIds, "b-inbox"))
    }

    /** An account never synced has no cached inbox id: nothing to remove, and nothing lost. */
    @Test fun `no known inbox removes nothing`() {
        assertEquals(setOf("INBOX", "Archive", "Projets"), unreadRefreshTargets("a", scopes, null))
    }

    /** No account, or a scope not yet filled by the folder flow: an empty set, never a wild sync. */
    @Test fun `no account and no scope both yield nothing`() {
        assertEquals(emptySet<String>(), unreadRefreshTargets(null, scopes, "INBOX"))
        assertEquals(emptySet<String>(), unreadRefreshTargets("a", emptyList(), "INBOX"))
    }

    /** A folder listed twice is fetched once: `refreshAccountFolders` loops over what it is given. */
    @Test fun `a duplicated folder is one target`() {
        val twice = listOf("a" to "Projets", "a" to "Projets")
        assertEquals(setOf("Projets"), unreadRefreshTargets("a", twice, "INBOX"))
    }
}
