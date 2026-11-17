package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A local draft or a queued row stores its From as a bare address (#302): reopened, an address that
 * is none of the identities becomes a passing From again when its form alone passes the rule, since
 * the original that proved it is not at hand. Executed through `reopenedLocalDraftFrom` and
 * `reopenedQueuedRowFrom`; the last test reads the view model's call sites as text, the view model
 * not being instantiable in JVM.
 */
class ReopenedPassingFromTest {

    private fun identity(address: String, name: String = "", signature: String = "") =
        StoredIdentity(id = address, name = name, email = address, signature = signature)

    private val me = identity("me@example.com", name = "Me", signature = "-- me")
    private val work = identity("work@corp.org", name = "Work", signature = "-- work")
    private val other = identity("other@elsewhere.net", name = "Other")

    private fun account(protocol: MailProtocol, loginId: String? = null) = StoredAccount(
        id = "acc",
        server = "s",
        username = "me@example.com",
        loginId = loginId,
        protocol = protocol,
        identities = listOf(me, work),
    )

    private val jmap = account(MailProtocol.JMAP)
    private val imap = account(MailProtocol.IMAP)

    private val options = listOf(FromOption("acc", me), FromOption("acc", work), FromOption("acc2", other))

    private fun passing(address: String, from: StoredIdentity) = FromOption(
        "acc",
        StoredIdentity(
            id = "passing:" + address.lowercase(),
            name = from.name,
            email = address,
            signature = from.signature,
            signatureHtml = from.signatureHtml,
        ),
    )

    private fun draft(account: StoredAccount, written: String) =
        reopenedLocalDraftFrom(options, "acc", account, written)

    private fun queued(account: StoredAccount, written: String) =
        reopenedQueuedRowFrom(options, "acc", account, written)

    // --- local draft ---

    @Test fun `a local draft written as a sub-address reopens under it on JMAP`() {
        assertEquals(passing("me+shop@example.com", me), draft(jmap, "me+shop@example.com"))
    }

    @Test fun `a local draft written as an alias of an identity's domain reopens under it on IMAP`() {
        assertEquals(passing("sales@corp.org", work), draft(imap, "sales@corp.org"))
    }

    @Test fun `a local draft written as an identity reopens under the identity`() {
        assertEquals(FromOption("acc", work), draft(jmap, "WORK@corp.org"))
    }

    @Test fun `a local draft the rule refuses keeps the default prepare chose`() {
        assertNull("IMAP, a domain of no identity", draft(imap, "me@unknown.org"))
        assertNull("JMAP, the tag of no identity", draft(jmap, "nobody+shop@example.com"))
        assertNull("JMAP, an alias on an identity's domain", draft(jmap, "sales@example.com"))
        assertNull("a linked account", draft(account(MailProtocol.JMAP, loginId = "login"), "me+shop@example.com"))
        assertNull("a linked IMAP account", draft(account(MailProtocol.IMAP, loginId = "login"), "sales@corp.org"))
    }

    // --- queued row ---

    @Test fun `a queued row written as a sub-address reopens under it on JMAP`() {
        assertEquals(passing("work+news@corp.org", work), queued(jmap, "work+news@corp.org"))
    }

    @Test fun `a queued row written as an alias of an identity's domain reopens under it on IMAP`() {
        assertEquals(passing("hello@example.com", me), queued(imap, "hello@example.com"))
    }

    @Test fun `a queued row written as an identity reopens under the identity`() {
        assertEquals(FromOption("acc", work), queued(imap, "work@corp.org"))
    }

    @Test fun `a queued row the rule refuses falls back on the account's first option`() {
        val first = FromOption("acc", me)
        assertEquals("IMAP, a domain of no identity", first, queued(imap, "work@unknown.org"))
        assertEquals("JMAP, the tag of no identity", first, queued(jmap, "nobody+news@corp.org"))
        assertEquals("a linked account", first, queued(account(MailProtocol.JMAP, loginId = "login"), "work+news@corp.org"))
    }

    @Test fun `a queued row of another account takes no passing From from this one`() {
        assertEquals(FromOption("acc2", other), reopenedQueuedRowFrom(options, "acc2", jmap, "me+shop@example.com"))
    }

    @Test fun `a passing From already on the list is found again, not copied`() {
        val adopted = passing("me+shop@example.com", me)
        assertEquals(adopted, reopenedQueuedRowFrom(options + adopted, "acc", jmap, "me+shop@example.com"))
    }

    // --- one address, identity of two accounts (a linked account inherits its login's identities) ---

    private val linkedFirst = listOf(FromOption("linked", me), FromOption("acc", me), FromOption("acc", work))

    @Test fun `a local draft reopens under its own account's identity, not another account's same address`() {
        assertEquals(FromOption("acc", me), reopenedLocalDraftFrom(linkedFirst, "acc", jmap, "me@example.com"))
    }

    @Test fun `a queued row reopens under its own account's identity, not another account's same address`() {
        assertEquals(FromOption("acc", me), reopenedQueuedRowFrom(linkedFirst, "acc", jmap, "me@example.com"))
    }

    @Test fun `a queued row the rule refuses falls back on its own account, not on the first account listed`() {
        assertEquals(FromOption("acc", me), reopenedQueuedRowFrom(linkedFirst, "acc", imap, "me@unknown.org"))
    }

    /**
     * Source lint, not a behaviour test: it reads `ComposeViewModel.kt` as text, the view model not
     * being instantiable on the JVM. Only that file is read.
     *
     * Hole 1 of CONTRIBUTING.md applies: `options`, `d.fromAccountId` and `credentials.id` are
     * pinned as names, not as what they are bound to. Hole 2 is closed for the queued row, whose
     * following lines are pinned whole, and applies to the local draft's call, pinned as one line
     * here (`LocalDraftReopenWiringTest` pins the lines around it). Hole 3 applies: nothing here
     * says the calls sit in `prepareLocalDraft` and `bindQueuedRow`, nor that they run. Hole 4 does
     * not apply: no composable is involved.
     */
    @Test fun `the view model selects the reopened From and adds it to its own list`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val queuedAt = code.indices.single {
            code[it] == "val match = reopenedQueuedRowFrom(options, d.fromAccountId, d.fromAccountId?.let { store.account(it) }, d.fromIdentityEmail)"
        }
        assertEquals(
            "a queued row reopens under the From its stored address designates, passing ones included",
            listOf(
                "if (match != null) {",
                "_fromOptions.value = withFromOption(_fromOptions.value, match)",
                "_selectedFrom.value = match",
                "}",
            ),
            code.subList(queuedAt + 1, queuedAt + 5),
        )
        assertEquals(
            "a local draft is judged on its own account, by its stored address",
            listOf("reopenedLocalDraftFrom(_fromOptions.value, credentials.id, store.account(credentials.id), written)"),
            code.filter { it.startsWith("reopenedLocalDraftFrom(") },
        )
        assertEquals(
            "each reopen helper is named on exactly one line of ComposeViewModel.kt",
            listOf(1, 1),
            listOf(
                code.count { "reopenedQueuedRowFrom(" in it },
                code.count { "reopenedLocalDraftFrom(" in it },
            ),
        )
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error("Cannot find $VIEW_MODEL from ${File("").absolutePath}")
        }
    }
}
