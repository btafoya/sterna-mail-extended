package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The address a message was delivered to, offered as this composer's From when it is none of the
 * account's identities (#302). The decision is executed through `receivingOrPassingFromOption`, the
 * one function `preselectReceivingIdentity` calls; the last test reads that function's body as text,
 * since the view model is not instantiable in JVM, to pin that the option only enters `_fromOptions`.
 */
class PassingFromOptionTest {

    private fun identity(address: String, name: String = "", signature: String = "") =
        StoredIdentity(id = address, name = name, email = address, signature = signature)

    private val me = identity("me@example.com", name = "Me", signature = "-- me")
    private val work = identity("work@corp.org", name = "Work", signature = "-- work")

    private fun account(
        protocol: MailProtocol,
        loginId: String? = null,
        defaultIdentityId: String? = null,
        identities: List<StoredIdentity> = listOf(me, work),
    ) = StoredAccount(
        id = "acc",
        server = "s",
        username = "me@example.com",
        loginId = loginId,
        protocol = protocol,
        defaultIdentityId = defaultIdentityId,
        identities = identities,
    )

    private val jmap = account(MailProtocol.JMAP)
    private val imap = account(MailProtocol.IMAP)

    private val options = listOf(FromOption("acc", me), FromOption("acc", work))

    private fun mail(
        to: List<String> = listOf("bob@example.net"),
        cc: List<String> = emptyList(),
        delivered: String? = null,
        listId: String? = null,
    ) = Email(
        id = "m1",
        from = listOf(EmailAddress(email = "alice@example.net")),
        to = to.map { EmailAddress(email = it) },
        cc = cc.map { EmailAddress(email = it) },
        deliveredAddress = delivered,
        listId = listId,
    )

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

    private fun chosen(account: StoredAccount?, o: Email, opts: List<FromOption> = options) =
        receivingOrPassingFromOption(opts, account, o)

    // --- JMAP: a sub-address of an identity, in To then Cc ---

    @Test fun `a sub-address in To opens under itself, wearing its base identity`() {
        assertEquals(passing("me+shop@example.com", me), chosen(jmap, mail(to = listOf("bob@example.net", "me+shop@example.com"))))
    }

    @Test fun `a sub-address in Cc opens under itself`() {
        assertEquals(passing("Me+Shop@Example.com", me), chosen(jmap, mail(cc = listOf("Me+Shop@Example.com"))))
    }

    @Test fun `To is read before Cc`() {
        assertEquals(
            passing("me+a@example.com", me),
            chosen(jmap, mail(to = listOf("me+a@example.com"), cc = listOf("work+b@corp.org"))),
        )
    }

    @Test fun `the tag of an address that is no identity proves nothing`() {
        assertEquals(null, chosen(jmap, mail(to = listOf("bob+shop@example.com"))))
    }

    @Test fun `an empty base proves nothing`() {
        assertEquals(null, chosen(jmap, mail(to = listOf("+shop@example.com"))))
    }

    @Test fun `the address is cut at its first plus`() {
        assertEquals(null, chosen(jmap, mail(to = listOf("me+a+b@example.com")), listOf(FromOption("acc", identity("me+a@example.com")))))
        assertEquals(passing("me+a+b@example.com", me), chosen(jmap, mail(to = listOf("me+a+b@example.com"))))
        assertEquals("a@x.org", subAddressBase("a+b+c@x.org"))
        assertEquals(null, subAddressBase("+tag@x.org"))
    }

    @Test fun `a JMAP account never takes the delivery headers`() {
        assertEquals(null, chosen(jmap, mail(delivered = "sales@example.com")))
    }

    // --- IMAP: the address the server wrote in the delivery headers ---

    @Test fun `an alias the server delivered to opens under itself`() {
        assertEquals(passing("sales@example.com", me), chosen(imap, mail(delivered = "sales@example.com")))
        assertEquals(passing("sales@example.com", me), chosen(imap, mail(delivered = "<sales@example.com>")))
    }

    @Test fun `a delivery header on a domain of no identity proves nothing`() {
        assertEquals(null, chosen(imap, mail(delivered = "sales@elsewhere.net")))
    }

    @Test fun `a list message proves nothing`() {
        assertEquals(null, chosen(imap, mail(delivered = "sales@example.com", listId = "<team.example.com>")))
    }

    @Test fun `a malformed delivery header proves nothing`() {
        assertEquals(null, chosen(imap, mail(delivered = "not an address@example.com")))
    }

    @Test fun `a sub-address in To on an IMAP account without a delivery header proves nothing`() {
        assertEquals(null, chosen(imap, mail(to = listOf("me+shop@example.com"))))
    }

    @Test fun `a sub-address the IMAP server wrote in Delivered-To wears its base identity`() {
        val first = identity("first@example.com", name = "First")
        val sameDomain = listOf(FromOption("acc", first), FromOption("acc", me))
        val firstByDefault = account(MailProtocol.IMAP, defaultIdentityId = first.id, identities = listOf(first, me))
        assertEquals(passing("me+shop@example.com", me), chosen(firstByDefault, mail(delivered = "me+shop@example.com"), sameDomain))
    }

    @Test fun `an identity named in To wins over the alias the message was delivered to`() {
        assertEquals(
            FromOption("acc", work),
            chosen(imap, mail(to = listOf("work@corp.org"), delivered = "sales@example.com")),
        )
        assertEquals(
            FromOption("acc", me),
            chosen(jmap, mail(to = listOf("me+shop@example.com", "me@example.com"))),
        )
    }

    @Test fun `an identity in the delivery header is the identity, not a passing copy`() {
        assertEquals(null, chosen(imap, mail(delivered = "ME@example.com")))
    }

    // --- Whose name and signature the alias wears ---

    @Test fun `with two identities on the domain, the default one, else the first`() {
        val first = identity("first@example.com", name = "First", signature = "-- first")
        val second = identity("second@example.com", name = "Second", signature = "-- second")
        val opts = listOf(FromOption("acc", work), FromOption("acc", first), FromOption("acc", second))
        val o = mail(delivered = "sales@example.com")
        val ids = listOf(work, first, second)
        assertEquals(passing("sales@example.com", second), chosen(account(MailProtocol.IMAP, defaultIdentityId = second.id, identities = ids), o, opts))
        assertEquals(passing("sales@example.com", first), chosen(account(MailProtocol.IMAP, defaultIdentityId = work.id, identities = ids), o, opts))
    }

    // --- Which accounts get one ---

    @Test fun `a linked account gets none`() {
        val linked = account(MailProtocol.JMAP, loginId = "login")
        assertEquals(null, chosen(linked, mail(to = listOf("me+shop@example.com"))))
        assertEquals(null, chosen(account(MailProtocol.IMAP, loginId = "login"), mail(delivered = "sales@example.com")))
    }

    @Test fun `only the replying account's identities are templates`() {
        val opts = listOf(FromOption("other", identity("me@example.com")), FromOption("acc", work))
        assertEquals(null, chosen(jmap, mail(to = listOf("me+shop@example.com")), opts))
        assertEquals(null, chosen(imap, mail(delivered = "sales@example.com"), opts))
    }

    @Test fun `an unknown account gets none`() {
        assertEquals(null, chosen(null, mail(to = listOf("me+shop@example.com"))))
    }

    @Test fun `an option already adopted is not taken for an identity`() {
        val adopted = options + passing("sales@example.com", me)
        assertEquals(passing("sales@example.com", me), chosen(imap, mail(delivered = "sales@example.com"), adopted))
    }

    // --- The composer's own list, and reply-all ---

    @Test fun `the option enters the composer's list once`() {
        val option = passing("sales@example.com", me)
        assertEquals(options + option, withFromOption(options, option))
        assertEquals(options + option, withFromOption(options + option, option))
    }

    @Test fun `reply-all leaves out the passing address it answers from`() {
        val o = mail(to = listOf("sales@example.com", "bob@example.net"), delivered = "sales@example.com")
        val option = chosen(imap, o)!!
        val selves = replyingSelves("me@example.com", listOf(me, work), withFromOption(options, option), "acc")
        assertEquals(ReplyAllRecipients(to = listOf("alice@example.net", "bob@example.net"), cc = emptyList()), replyAllRecipients(o, selves))
        assertEquals(
            setOf("me@example.com", "work@corp.org"),
            replyingSelves("me@example.com", listOf(me, work), listOf(FromOption("other", identity("sales@example.com"))), "acc"),
        )
    }

    // --- Reply-all opened from the cache, settled by the fetched original ---

    private val sales = passing("sales@example.com", me)

    @Test fun `a reply-all from the cache drops the passing address the original settles on`() {
        assertEquals("sales@example.com", recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, true, FromOption("acc", me), sales))
        assertEquals("sales@example.com", recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, true, null, sales))
    }

    @Test fun `a reply or a forward drops nothing`() {
        for (opening in listOf(ComposeOpening.REPLY, ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT)) {
            assertEquals("$opening dropped a recipient", null, recipientDroppedOnceSettled(opening, true, FromOption("acc", me), sales))
        }
    }

    @Test fun `nothing is dropped when the cache already knew the From, or nothing came from the cache`() {
        assertEquals(null, recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, true, sales, sales))
        assertEquals(null, recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, false, FromOption("acc", me), sales))
        assertEquals(null, recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, true, FromOption("acc", me), FromOption("acc", work)))
        assertEquals(null, recipientDroppedOnceSettled(ComposeOpening.REPLY_ALL, true, FromOption("acc", me), null))
    }

    @Test fun `the dropped address leaves the chips whatever its case or display name`() {
        assertEquals("bob@example.net, ", recipientsWithout("sales@example.com, bob@example.net, ", "sales@example.com"))
        assertEquals("bob@example.net, ", recipientsWithout("Sales <SALES@Example.com>, bob@example.net, ", "sales@example.com"))
        assertEquals("bob@example.net, ", recipientsWithout("bob@example.net, Sales@Example.COM, ", " sales@example.com "))
        assertEquals("", recipientsWithout("sales@example.com, ", "sales@example.com"))
        assertEquals(
            "alice@example.net, bob@example.net, carol@example.net, ",
            recipientsWithout("alice@example.net, sales@example.com, bob@example.net, Sales <sales@example.com>, carol@example.net, ", "sales@example.com"),
        )
    }

    @Test fun `a field without the address comes back untouched, and the typed token stays`() {
        assertEquals("bob@example.net;alice@example.net, ", recipientsWithout("bob@example.net;alice@example.net, ", "sales@example.com"))
        assertEquals("", recipientsWithout("", "sales@example.com"))
        assertEquals("bob@example.net, sales@exa", recipientsWithout("sales@example.com, bob@example.net, sales@exa", "sales@example.com"))
        assertEquals("sales@example.com", recipientsWithout("sales@example.com", "sales@example.com"))
        assertEquals("sales@example.com.au, ", recipientsWithout("sales@example.com.au, ", "sales@example.com"))
    }

    @Test fun `the view model asks for the drop right after the original settles the From`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val settle = code.indices.single { code[it] == "preselectReceivingIdentity(credentials.id, original)" }
        assertEquals(
            "_droppedRecipient.value = recipientDroppedOnceSettled(opening, cached != null, fromAtCache, _selectedFrom.value)",
            code[settle + 1],
        )
        val atCache = code.indices.single { code[it] == "val fromAtCache = _selectedFrom.value" }
        val cachePrefill = code.indices.single { code[it] == "_prefill.value = buildPrefill(cached, mode, selves(credentials), quoteBody = false)" }
        assertEquals("the From at cache time is read after the cached prefill and before the fetch", listOf(cachePrefill, atCache, settle), listOf(cachePrefill, atCache, settle).sorted())
        assertEquals(
            listOf(
                "private val _droppedRecipient = MutableStateFlow<String?>(null)",
                "val droppedRecipient: StateFlow<String?> = _droppedRecipient.asStateFlow()",
                "_droppedRecipient.value = null",
                "_droppedRecipient.value = recipientDroppedOnceSettled(opening, cached != null, fromAtCache, _selectedFrom.value)",
            ),
            code.filter { "_droppedRecipient" in it },
        )
    }

    @Test fun `the screen drops the address from the fields and from their baselines alike`() {
        val code = File(root, SCREEN).readLines().map { it.trim() }
        val start = code.indices.single { code[it] == "LaunchedEffect(droppedRecipient, applied) {" }
        assertEquals(
            listOf(
                "val address = droppedRecipient ?: return@LaunchedEffect",
                "if (!applied) return@LaunchedEffect",
                "to = recipientsWithout(to, address)",
                "cc = recipientsWithout(cc, address)",
                "initialTo = recipientsWithout(initialTo, address)",
                "initialCc = recipientsWithout(initialCc, address)",
                "viewModel.consumeDroppedRecipient()",
                "}",
            ),
            code.subList(start + 1, start + 9),
        )
    }

    @Test fun `the view model adds the option to its own list and to nothing else`() {
        val code = File(root, VIEW_MODEL).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val start = code.indices.single { code[it] == "private fun preselectReceivingIdentity(accountId: String, original: Email) {" }
        assertEquals(
            "the store is never written: the option lives in this composer's From list only",
            listOf(
                "receivingOrPassingFromOption(_fromOptions.value, store.account(accountId), original)?.let { option ->",
                "_fromOptions.value = withFromOption(_fromOptions.value, option)",
                "if (option != _selectedFrom.value) {",
                "_selectedFrom.value = option",
                "refreshPgp()",
                "}",
                "}",
                "}",
            ),
            code.subList(start + 1, start + 9),
        )
        assertEquals(
            "reply-all's selves read the composer's From list, where the passing option is",
            listOf("replyingSelves(credentials.username, store.identities(credentials.id), _fromOptions.value, credentials.id)"),
            code.filter { it.startsWith("replyingSelves(") },
        )
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error("Cannot find $VIEW_MODEL from ${File("").absolutePath}")
        }
    }
}
