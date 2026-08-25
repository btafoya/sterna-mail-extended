package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "show only subscribed folders" setting (#174), EXECUTED: the two decisions `AccountStore`
 * delegates to, run on real arguments. Nothing here re-derives a rule from the source it is
 * checking, so inverting either one turns this file red.
 *
 * Why they are functions at all rather than the two one-liners the neighbouring settings use:
 * nothing in this module can build an `AccountStore` (it wants a `Context` and the Keystore, and
 * there is no Robolectric here), so a write folded into the store is reachable by a source lint and
 * by nothing else. The guard that decides WHICH account the switch writes to is exactly the part a
 * lint cannot run: `if (it.id == id)` dropped, and ticking the box on one account ticks it on all
 * of them — every other test still green. The wiring of these two into the store is pinned next
 * door by [ShowOnlySubscribedFoldersStoreWiringTest].
 */
class ShowOnlySubscribedFoldersTest {

    private fun account(id: String, on: Boolean = false) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        showOnlySubscribedFolders = on,
    )

    // ── the write ───────────────────────────────────────────────────────────────────────────────

    @Test fun `the choice lands on the named account and on NO other`() {
        val after = withShowOnlySubscribedFolders(
            listOf(account("a"), account("b"), account("c")),
            id = "b",
            enabled = true,
        )
        assertEquals(
            "ticking the box on one account changed another account's folder list too. Written " +
                "without the `if (it.id == id)` guard, one tick hides the unsubscribed folders of " +
                "every account in the app, and nothing on the other accounts' screens says so.",
            listOf(false, true, false),
            after.map { it.showOnlySubscribedFolders },
        )
        assertEquals("the account list was reordered or lost a record", listOf("a", "b", "c"), after.map { it.id })
    }

    @Test fun `switching it back off writes false on that account alone`() {
        val after = withShowOnlySubscribedFolders(
            listOf(account("a", on = true), account("b", on = true)),
            id = "a",
            enabled = false,
        )
        assertEquals(
            "unticking the box did not write false where it was ticked, or spread to the other account.",
            listOf(false, true),
            after.map { it.showOnlySubscribedFolders },
        )
    }

    @Test fun `an unknown id changes nothing`() {
        val before = listOf(account("a", on = true), account("b"))
        val after = withShowOnlySubscribedFolders(before, id = "ghost", enabled = true)
        assertEquals("a write for an id no account carries touched a record", before, after)
    }

    @Test fun `nothing but this one field moves`() {
        val settled = account("a").copy(
            accountName = "Ana perso",
            syncWindow = SyncWindow.YEAR_1,
            notificationsEnabled = false,
            uploadSentCopy = false,
            watchedFolders = setOf("mbx-2"),
            color = 0x33445566,
        )
        val after = withShowOnlySubscribedFolders(listOf(settled), id = "a", enabled = true).single()
        assertEquals(
            "writing this switch rebuilt the record instead of copying it: another setting moved.",
            settled.copy(showOnlySubscribedFolders = true),
            after,
        )
    }

    // ── the read ────────────────────────────────────────────────────────────────────────────────

    @Test fun `an account nobody holds reads as off`() {
        assertFalse(
            "the fallback for an unknown account id must be false. Answering true hides folders " +
                "for an account whose record could not even be found — the one answer that can " +
                "make mail disappear from the folder list without anybody having asked.",
            showOnlySubscribedFoldersOf(null),
        )
    }

    @Test fun `a stored account reads back exactly what it stores`() {
        assertTrue("an account that stores the setting on reads as off", showOnlySubscribedFoldersOf(account("a", on = true)))
        assertFalse("an account that stores the setting off reads as on", showOnlySubscribedFoldersOf(account("a", on = false)))
    }

    // ── what a record already on disk decodes to ────────────────────────────────────────────────

    /**
     * There is no migration here and there must not be one: the account list is one `@Serializable`
     * blob and every record on every phone was written before this field. The whole compatibility
     * story is the default, and the only way to prove it is to DECODE a record that does not carry
     * the key — `StoredAccount()` in Kotlin applies the same default without going through the
     * parser, and would pass just as well if the field were `@Required`.
     */
    private val json = Json { ignoreUnknownKeys = true }

    private val preBranch =
        """[{"id":"a1","server":"imap.example.test","username":"demo@example.test",
           |"accountName":"Demo","protocol":"IMAP","imapHost":"imap.example.test",
           |"smtpHost":"smtp.example.test"}]""".trimMargin().replace("\n", "")

    @Test fun `a record written before this field still shows every folder`() {
        val decoded = json.decodeFromString<List<StoredAccount>>(preBranch).single()
        assertFalse(
            "an account stored before 'showOnlySubscribedFolders' existed must decode to false. " +
                "Any other default makes folders vanish from the list on the update, on every " +
                "install, without the user having asked for anything.",
            decoded.showOnlySubscribedFolders,
        )
    }

    @Test fun `a record that stored the setting on decodes as on`() {
        val stored = preBranch.replace("\"id\":\"a1\"", "\"id\":\"a1\",\"showOnlySubscribedFolders\":true")
        val decoded = json.decodeFromString<List<StoredAccount>>(stored).single()
        assertTrue(
            "the stored value must be read back. If this is false the field is write-only — the " +
                "switch would answer 'off' again on the next cold start, whatever the user chose.",
            decoded.showOnlySubscribedFolders,
        )
    }
}
