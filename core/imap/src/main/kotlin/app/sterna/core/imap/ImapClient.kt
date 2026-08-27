package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A protocol-level IMAP failure. [responseCode] is the server's bracketed response code
 * (RFC 5530) when the failure is a tagged NO/BAD that carried one — `AUTHENTICATIONFAILED`,
 * `UNAVAILABLE`, … — and `null` otherwise (no code on the line, or a local failure such as a
 * closed connection). One field, not a type per code: it is the TYPED half of the failure, the
 * one a caller may branch on, while [message] stays the loggable text and never drives a branch.
 */
class ImapException(message: String, val responseCode: String? = null) : Exception(message)

/**
 * The selected mailbox has been renumbered: its UIDVALIDITY is no longer the one the caller's
 * UIDs were read under, so those UIDs mean nothing (RFC 3501 §2.3.1.1).
 *
 * Deliberately NOT an [ImapException]: it is not a protocol error and not a transport failure,
 * and the existing `catch (e: ImapException)` / reconnect-and-retry paths must not absorb it.
 * Retrying cannot help — the folder really was renumbered — and treating it as a failure to be
 * swallowed is how a stale UID gets acted on. See [ImapSession.select].
 */
class ImapUidValidityChanged(
    val mailbox: String,
    val expected: Long,
    val observed: Long,
) : Exception("UIDVALIDITY of $mailbox changed from $expected to $observed")

/**
 * Turn on RFC 2818 endpoint identification so the TLS handshake verifies the peer
 * certificate's CN/SAN against [host]. A bare [SSLSocket] only validates the chain,
 * not the hostname, so without this an attacker with any CA-valid certificate can
 * MITM the connection and capture credentials. Must be called before the handshake.
 */
internal fun SSLSocket.verifyingHostname(): SSLSocket = apply {
    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
}

/** Opens authenticated IMAP sessions. The session object carries the live connection. */
class ImapClient(
    /**
     * What this build calls itself on the wire — the `version` field of the RFC 2971 `ID` command
     * (see [ImapSession.identify]). `null`, the default, sends the name alone.
     *
     * A parameter and not a constant because `core/imap` is plain Kotlin/JVM: it has no
     * `BuildConfig` and must not gain an Android dependency to read one. `:app` hands its
     * `BuildConfig.VERSION_NAME` down through `DataFactory.create`.
     */
    private val clientVersion: String? = null,
) {
    suspend fun connect(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession =
        withContext(Dispatchers.IO) { openSession(config, connectTimeoutMs) }

    /**
     * Blocking connect + login. Use when already on a dedicated IO thread (e.g. IDLE).
     *
     * [connectTimeoutMs] bounds the TCP connect AND the greeting/STARTTLS/login reads that
     * follow, after which the socket goes back to its blocking default so no later operation
     * inherits a timeout. `0` IS that default — a connect the OS gives up on after minutes —
     * and is what every caller that passes nothing has always had. Name resolution is not
     * covered by it (no socket option is), exactly as before.
     */
    fun openSession(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession {
        val timeout = connectTimeoutMs.coerceAtLeast(0)
        val plain = Socket()
        plain.connect(InetSocketAddress(config.host, config.port), timeout)
        val socket = when (config.security) {
            MailSecurity.TLS ->
                (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket).verifyingHostname()
            else -> plain
        }
        val session = ImapSession(socket)
        session.withReadTimeout(timeout) {
            session.readGreeting()
            if (config.security == MailSecurity.STARTTLS) {
                session.command("STARTTLS")
                session.upgradeTls(config.host, config.port)
            }
            if (config.accessToken != null) {
                session.authenticateXoauth2(config.username, config.accessToken)
            } else {
                session.login(config.username, config.password)
            }
            // Last thing before the session is handed out: post-authentication (so the capability
            // list is the real one, RFC 3501 §7.1) and ahead of any SELECT the caller makes, which is the window
            // NetEase's servers judge (Codeberg #173). Bounded by the connect timeout like every
            // other read here. Both openers go through this one place — the pooled sessions of
            // ImapMailService and the IDLE connection — so an account with push sends two IDs,
            // one per connection, which is what RFC 2971 describes.
            session.identify(clientVersion)
        }
        return session
    }

    private companion object {
        val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}

/**
 * A connected, logged-in IMAP session. Stateful (SELECT then FETCH), so callers
 * keep it for a unit of work and [close] it after. Not thread-safe.
 */
class ImapSession(private var socket: Socket) : Closeable {
    private var input = ImapParser(BufferedInputStream(socket.inputStream))
    private var output: OutputStream = socket.outputStream
    private var tagN = 0

    /**
     * What the server says it supports, learned once and kept for the life of the session,
     * `null` until then. See [capabilities].
     */
    private var advertisedCapabilities: Set<String>? = null

    internal fun readGreeting() {
        // Untagged "* OK [CAPABILITY …] ready". Its capability list is deliberately NOT kept:
        // it is what the server offers to an anonymous, possibly not-yet-encrypted client, and
        // RFC 3501 §7.1 says it may differ from what the same server offers once STARTTLS and
        // authentication have happened — which is the only state this session ever acts in.
        // [capabilities] learns the post-authentication list instead.
        input.readResponse()
    }

    internal fun upgradeTls(host: String, port: Int) {
        val tls = ((SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, true) as SSLSocket).verifyingHostname()
        tls.startHandshake()
        socket = tls
        input = ImapParser(BufferedInputStream(tls.inputStream))
        output = tls.outputStream
    }

    internal fun login(username: String, password: String) {
        rememberCapabilities(command("LOGIN ${quote(username)} ${quote(password)}").tagged)
    }

    /**
     * What this server supports, as capability names, upper-cased (RFC 3501 §6.1.1 — names are
     * case-insensitive). Post-authentication and cached: asked at most once per session, and
     * usually not asked at all, because most servers volunteer the list in the `[CAPABILITY …]`
     * response code of their LOGIN/AUTHENTICATE completion.
     *
     * ⚠ "At most once" is true OF THIS FUNCTION, not of the session: [identify] puts a `CAPABILITY`
     * of its own at connect time on a server that volunteered nothing, and deliberately throws the
     * answer away rather than fill this cache — see its KDoc for the mail-losing reason. Such a
     * session therefore sends two, and `IdOnTheWireTest.asking for the ID capability leaves the
     * session cache cold` pins exactly that.
     *
     * Deliberately a set of names and not a boolean per feature: the first caller needs UIDPLUS
     * (whether `UID EXPUNGE` exists, see [delete]), and the next one will need something else
     * (`UTF8=ACCEPT`, Codeberg #101). A server answering the query with no list at all is taken
     * at its word — an empty set, i.e. every optional extension absent, which is the reading that
     * makes each caller take its conservative branch.
     */
    fun capabilities(): Set<String> =
        advertisedCapabilities ?: parseCapabilities(command("CAPABILITY").untagged)
            .also { advertisedCapabilities = it }

    /** Whether the server advertises [name], e.g. `UIDPLUS`. Case-insensitive. */
    fun hasCapability(name: String): Boolean = name.uppercase() in capabilities()

    /**
     * Take the `[CAPABILITY …]` response code of an authentication completion as the session's
     * capability list. Free — it saves the round trip [capabilities] would otherwise make — and
     * authoritative: unlike the greeting's, this list is the post-authentication one.
     */
    private fun rememberCapabilities(taggedLine: List<Any?>) {
        val advertised = capabilitiesInResponseCode(taggedLine) ?: return
        if (advertised.isNotEmpty()) advertisedCapabilities = advertised
    }

    /**
     * Authenticate with SASL XOAUTH2 (OAuth bearer token) instead of a password.
     * On a bad token the server sends a "+" base64 error challenge and then waits for an
     * empty client response before issuing the tagged NO — we acknowledge it so the
     * failure surfaces as an auth error instead of hanging.
     */
    internal fun authenticateXoauth2(username: String, accessToken: String) {
        val tag = "a${++tagN}"
        output.write("$tag AUTHENTICATE XOAUTH2 ${xoauth2Payload(username, accessToken)}\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    if (status != "OK") {
                        throw ImapException(
                            "AUTHENTICATE … failed: ${resp.drop(1).joinToString(" ")}",
                            responseCodeIn(resp),
                        )
                    }
                    rememberCapabilities(resp)
                    return
                }
                "+" -> {
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                // else: an untagged "*" status line — ignore and read on for the tag.
            }
        }
    }

    /**
     * Tell the server who this client is: `ID ("name" "Sterna Mail" "version" "…")`, RFC 2971.
     *
     * Not politeness. NetEase (`imap.163.com`, `126.com`, `188.com`, `yeah.net`) authenticates an
     * anonymous client and then refuses every SELECT with `NO SELECT Unsafe Login`, so the account
     * logs in and syncs nothing (Codeberg #173). The reporter's own transcript shows a bare `ID
     * NIL` accepted and the SELECT still refused: what is demanded is a client that NAMES itself.
     *
     * The question is PUT, never presumed: RFC 2971 §3 says a client must not send ID to a server
     * that has not advertised it. A server that published its list in the authentication
     * completion (Dovecot, Stalwart, Gmail) is read from that list and costs nothing; a server
     * that published nothing is asked, and pays one `CAPABILITY` per connection. That round trip
     * is the accepted price of the rule above.
     *
     * ⚠ Which of those two branches Coremail (163/126/188/yeah) takes, and whether its list even
     * carries `ID`, is NOT known here: the reporter's transcript goes LOGIN → SELECT → ID and
     * never puts a `CAPABILITY`. So this guard is where the fix can quietly do nothing, and the
     * answer costs one question to the reporter rather than a guess in this comment.
     *
     * ⛔ **The question is put BY HAND, and its answer is thrown away** — `parseCapabilities` on a
     * `CAPABILITY` of our own, never [capabilities] or [hasCapability]. Those two CACHE what they
     * learn (`:143-145`), and this session's cache must still be COLD when the session is handed
     * out. [move] decides its destructive copy fallback on
     * `runCatching { hasCapability(CAP_MOVE) }.getOrDefault(true)`: a probe that cannot be answered
     * means "the socket is gone, do not copy". Fill the cache here and that question can no longer
     * fail, so a `UID MOVE` cut mid-command walks straight into `UID COPY` + `\Deleted` + purge.
     * Measured, with `hasCapability` in this spot:
     *
     * `the mail was copied: [CAPABILITY, SELECT "INBOX", UID MOVE 1:3 "Archive", UID COPY 1:3
     * "Archive", LOGOUT]`
     *
     * — i.e. `ExpungeTest.a move cut mid-command does not enter the fallback on a probe it cannot
     * answer` failing, next to `IdOnTheWireTest.asking for the ID capability leaves the session
     * cache cold`, which pins the cold cache directly. ⛔ So no `.also { advertisedCapabilities =
     * it }` on the line below, ever. A later reader asking the same question again is waste, and
     * waste is what was chosen over a mailbox.
     *
     * ⛔ **Best effort, all of it inside one `runCatching`** — the question included, since that
     * `CAPABILITY` can throw too. A fatal command here would be fatal twice over:
     * [ImapClient.openSession] has no `try/finally` and `ImapMailService.runWithRetry` connects
     * OUTSIDE its `try`, so a throw leaks the socket, and it would break every account whose server
     * answers this command with a NO.
     *
     * Every value is [quote]d, keys included — a CR/LF in the version would otherwise inject a
     * second, authenticated command. `quote` refuses those, the refusal lands in the `runCatching`,
     * and the session goes on without having said its name.
     *
     * Nothing is read back: the server's own `* ID (…)` lands in the untagged lines and is dropped.
     */
    internal fun identify(clientVersion: String?) {
        runCatching {
            val published = advertisedCapabilities
            // ⛔ No `.also { advertisedCapabilities = it }` here — see above: the cache stays cold.
            val advertised = published ?: parseCapabilities(command("CAPABILITY").untagged)
            if (CAP_ID !in advertised) return@runCatching
            val fields = buildList {
                add(quote("name"))
                add(quote(CLIENT_NAME))
                if (clientVersion != null) {
                    add(quote("version"))
                    add(quote(clientVersion))
                }
            }
            command("ID (${fields.joinToString(" ")})")
        }
    }

    /** Strip credential-bearing arguments before a command appears in an error/log. */
    private fun redactCommand(line: String): String {
        val verb = line.substringBefore(' ').uppercase()
        return if (verb == "LOGIN" || verb == "AUTHENTICATE") "$verb …" else line
    }

    /**
     * Bound every socket read made inside [block] to [millis], then restore the socket's
     * previous setting — the shape [idle] already uses, so one operation can be given a
     * deadline without any other inheriting it. `0` means "block forever", the JVM default.
     *
     * A per-READ bound, not a total: a server dribbling one byte at a time stays under it
     * indefinitely. It is what a socket offers, and it is what turns "hangs until the OS gives
     * up, minutes later" into "gives up on a silent peer in [millis]".
     */
    fun <T> withReadTimeout(millis: Int, block: () -> T): T {
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * [withReadTimeout] for a block that SUSPENDS — the same wrapper, and the two must stay the
     * same wrapper. It cannot simply call the other one: a plain function cannot take a suspending
     * lambda, and making the other one suspend would drag `connect` — which runs before any
     * coroutine exists — with it.
     *
     * ⛔ So what they share is not a comment but [armReadTimeout]/[disarmReadTimeout]: the thing
     * that would be silently missing from a copy is the SETTING of the timeout, and there is now
     * one of those, exercised by the budget tests through the other wrapper. `ImapBudgetSharedTest`
     * additionally reads both bodies and refuses to let them differ.
     *
     * It exists for [walkFolder], whose block writes each page to the database between requests.
     * The socket setting therefore stands across those suspensions, which is what it is for: the
     * bound belongs to the reads of this operation, and the reads of this operation are exactly
     * what happens in between.
     */
    suspend fun <T> withReadTimeoutSuspending(millis: Int, block: suspend () -> T): T {
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * Bound this socket's reads to [millis] and answer with what the bound was — the load-bearing
     * half of both `withReadTimeout` wrappers, in ONE place.
     *
     * ⛔ It is one place on purpose. Written out in each wrapper, deleting the assignment from the
     * copy the tests do not reach left every budget test green while `ENUMERATE_BUDGET_MS` — the
     * bound that stops an "Empty trash" enumeration hanging on a mute server — stopped applying to
     * everything the pooled session does.
     */
    private fun armReadTimeout(millis: Int): Int {
        val previous = socket.soTimeout
        socket.soTimeout = millis.coerceAtLeast(0)
        return previous
    }

    /** Put back what [armReadTimeout] answered. The field can have been swapped by a STARTTLS
     *  upgrade inside the block, so this restores on whatever socket is current — and never throws
     *  on the way out of a block that is already failing. */
    private fun disarmReadTimeout(previous: Int) {
        runCatching { socket.soTimeout = previous }
    }

    /**
     * Send a tagged command and collect untagged responses up to the tagged result.
     *
     * [maxTokensKept] caps what the WHOLE response retains (see [ImapParser.readResponse]), not
     * what each line retains: a server free to split its answer over K lines would otherwise be
     * allowed K times the cap — precisely the case a cap exists for. Exactly: the cap, plus at
     * most [STATUS_LINE_TOKENS] per line once it is spent, because a status line must stay
     * readable. The default keeps everything, and only a caller that already knows it will
     * discard the surplus lowers it.
     */
    internal fun command(line: String, maxTokensKept: Int = Int.MAX_VALUE): ImapResult {
        val tag = "a${++tagN}"
        output.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val untagged = mutableListOf<List<Any?>>()
        var budget = maxTokensKept
        while (true) {
            // A floor per line, whatever is left of the budget: the tagged status line must stay
            // recognisable (its tag, its OK/NO) even once the untagged data has spent the lot,
            // or this loop would no longer recognise its own answer and would read forever.
            val resp = input.readResponse(budget.coerceAtLeast(STATUS_LINE_TOKENS))
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    // Redact the echoed command: LOGIN/AUTHENTICATE carry the password,
                    // and this message surfaces to the UI and logs.
                    if (status != "OK") {
                        throw ImapException(
                            "${redactCommand(line)} failed: ${resp.drop(1).joinToString(" ")}",
                            responseCodeIn(resp),
                        )
                    }
                    return ImapResult(status, untagged, resp)
                }
                else -> {
                    untagged.add(resp) // "*" untagged or "+" continuation
                    budget -= resp.size
                }
            }
        }
    }

    /**
     * Run one IMAP IDLE cycle on the selected mailbox: wait up to [timeoutMs] for the
     * server to report a change. Returns true if new mail arrived (an untagged EXISTS or
     * RECENT), false on the keep-alive timeout. Always ends IDLE (DONE) and consumes the
     * tagged result before returning, so the caller can immediately IDLE again.
     */
    fun idle(timeoutMs: Int): Boolean {
        val tag = "a${++tagN}"
        output.write("$tag IDLE\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val previousTimeout = socket.soTimeout
        socket.soTimeout = timeoutMs
        var changed = false
        try {
            while (true) {
                val resp = try {
                    input.readResponse()
                } catch (_: java.net.SocketTimeoutException) {
                    break // keep-alive window elapsed — refresh IDLE
                }
                if (resp.isEmpty()) {
                    if (socket.isClosed) throw ImapException("Connection closed during IDLE")
                    continue
                }
                // Untagged "* <n> EXISTS" / "* <n> RECENT" announce new mail.
                val kind = resp.getOrNull(2)
                if (kind == "EXISTS" || kind == "RECENT") {
                    changed = true
                    break
                }
                // "+ idling" continuation and other status updates: keep waiting.
            }
        } finally {
            socket.soTimeout = previousTimeout
            // End IDLE and drain to its tagged completion so the stream is clean.
            runCatching {
                output.write("DONE\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                while (true) {
                    val resp = input.readResponse()
                    if (resp.isEmpty()) {
                        if (socket.isClosed) break else continue
                    }
                    if (resp[0] == tag) break
                }
            }
        }
        return changed
    }

    /**
     * A mailbox name as it must appear in a command: Unicode in, modified UTF-7 out
     * ([encodeModifiedUtf7]), then quoted.
     *
     * EVERY command that names a mailbox goes through this — SELECT, CREATE, RENAME (both
     * arguments), DELETE, the destination of UID MOVE and of its UID COPY fallback, APPEND —
     * and it lives here rather than at the callers precisely so that adding a command cannot
     * quietly skip it. Above this class a mailbox path is Unicode and nothing else (Codeberg
     * #101): decoding LIST without encoding these seven would make a Cyrillic folder readable
     * and unopenable.
     *
     * Encode BEFORE quote, never after: the encoded form is pure ASCII, so [quote]'s refusal of
     * CR/LF — the guard against a folder name injecting a second authenticated command — still
     * holds, and a newline can no longer even reach it.
     */
    private fun mailboxArg(path: String): String = quote(encodeModifiedUtf7(path))

    /**
     * Create a mailbox (folder) and SUBSCRIBE to it. Succeeds quietly if it already exists.
     *
     * The subscription is not decoration: `CREATE` does not touch the subscription list
     * (RFC 3501 §6.3.3 says nothing of it, and a server measured on 2026-08-25 leaves the new
     * mailbox unsubscribed), so with "subscribed folders only" on, the folder the user has just
     * created would vanish from the drawer the instant it appeared (#174).
     *
     * ⛔ This is the ONLY subscription Sterna ever writes. It does not drive subscriptions:
     * no `UNSUBSCRIBE` anywhere, no `SUBSCRIBE` on any other path, and none on screen. The one
     * mailbox it subscribes to is the one it created itself.
     *
     * Two decisions, written down so they are not undone as tidying:
     *
     * 1. **The SUBSCRIBE is sent even when the CREATE failed** — and the two `runCatching` are
     *    separate for exactly that. The failure this method is DOCUMENTED to swallow is "the
     *    mailbox already exists", which is precisely the case of a mailbox that is there and
     *    unsubscribed; skipping the SUBSCRIBE would leave the user's folder hidden with no way
     *    back inside the app. Telling that failure from a real one would mean reading the
     *    response code (`ALREADYEXISTS`) and trusting servers to send it — more surface for
     *    nothing, because a SUBSCRIBE aimed at a mailbox that genuinely does not exist is
     *    harmless either way: refused (swallowed below), or accepted and inert, since
     *    [listFolders] intersects LSUB with LIST and a subscription no LIST names adds no folder.
     * 2. **A refused SUBSCRIBE never fails the creation.** The folder EXISTS by then; returning
     *    an error on it would tell the user their folder was not created, and could send them
     *    creating it again. The cost of losing it is one folder hidden until they subscribe
     *    server-side — never a lost creation.
     *
     * Both commands carry the SAME [mailboxArg] value, computed once: a subscription under any
     * other spelling of the name is a subscription to another mailbox, or to none — and that is
     * every non-ASCII folder (Codeberg #101), which travels in modified UTF-7.
     */
    fun createFolder(path: String) {
        val mailbox = mailboxArg(path)
        runCatching { command("CREATE $mailbox") }
        runCatching { command("SUBSCRIBE $mailbox") }
    }

    /** Rename a mailbox from [oldPath] to [newPath]. */
    fun renameFolder(oldPath: String, newPath: String) {
        command("RENAME ${mailboxArg(oldPath)} ${mailboxArg(newPath)}")
    }

    /** Delete a mailbox. */
    fun deleteFolder(path: String) {
        command("DELETE ${mailboxArg(path)}")
    }

    /**
     * Move a message to another mailbox; the answer carries its new UID in the destination
     * (`moved.uids[uid]`) and that destination's numbering, both as `COPYUID` reported them.
     *
     * Written over the plural below and answering exactly what it answers: the caller of a single
     * move needs the destination's numbering just as much, because it is the one an Undo of THIS
     * message will have to oppose — "delete" on an account with a Trash is this call.
     */
    fun move(uid: Long, destination: String): ImapMoved = move(listOf(uid), destination)

    /**
     * Move many messages to [destination] with one `UID MOVE <set> <dest>` per chunk
     * (falling back to `UID COPY` + `+FLAGS (\Deleted)` + a purge), instead of one
     * command per message — the bulk-action fix (Codeberg #29). The caller must have
     * SELECTed the source mailbox. Returns the source-UID → destination-UID mapping parsed
     * from COPYUID (RFC 4315 / RFC 6851), empty when the server reports none — the move
     * still happened; only per-message Undo positioning is lost — AND the destination folder's
     * numbering, which the same COPYUID states and this client used to throw away (see
     * [ImapMoved]). Chunked to stay well under
     * command-length limits. The fallback is reserved for a server that does not advertise MOVE
     * (RFC 6851); any other refusal of the UID MOVE travels up untouched.
     *
     * ⚠ More than [UID_SET_CHUNK] UIDs is several commands, so several COPYUIDs — all naming one
     * destination folder, so all stating one numbering, and that one number is kept. When they
     * DISAGREE the destination was renumbered in the middle of the move and no single number is
     * true of the whole mapping: [ImapMoved.destinationUidValidity] is null then, which is safe
     * because null is the REFUSING value everywhere it is later opposed
     * (`UidValidity.mayDestroyUnderStatedNumbering` answers false for it). An Undo that cannot say
     * which numbering its UIDs belong to must not move anything under either of them.
     *
     * The copy fallback leaves the originals to be erased, and how that is done depends on
     * UIDPLUS exactly as it does in [delete] — same question, same single answer: see
     * [purgeWithoutUidPlus]. The capability is consulted BEFORE the first COPY, so a session
     * that cannot even answer that question fails without having duplicated anything.
     */
    fun move(uids: List<Long>, destination: String): ImapMoved {
        val mapping = LinkedHashMap<Long, Long>()
        // What each chunk's COPYUID said the DESTINATION's numbering is. A set, because the only
        // question asked of it is whether every chunk said the same thing.
        val statedNumbering = LinkedHashSet<Long>()
        // The no-UIDPLUS purge is folder-wide, so it is decided once for the whole move — over
        // the union of the chunks that actually took the copy fallback, and only those: a chunk
        // that went out as a UID MOVE flagged nothing.
        val flaggedByFallback = mutableSetOf<Long>()
        for (chunk in uids.distinct().chunked(UID_SET_CHUNK)) {
            val set = compressUidSet(chunk)
            if (set.isEmpty()) continue
            val result = runCatching { command("UID MOVE $set ${mailboxArg(destination)}") }.getOrElse {
                // The fallback answers ONE question — "this server has no MOVE extension" — and it
                // answers it destructively: copy, flag, purge the SOURCE folder. Everything else
                // that can fail a UID MOVE (a NO [OVERQUOTA] or [TRYCREATE], a dead socket, a read
                // that timed out) is a real error, and taking the copy path on those duplicates the
                // mail and then erases the originals from what, for "delete", is the inbox. So when
                // the server ADVERTISES MOVE, the failure goes up as it came.
                // Deliberately not a precondition on the UID MOVE itself: servers implement it
                // without advertising it, and gating the attempt would send MORE accounts down the
                // destructive path. And when the capability probe itself fails (the socket is gone,
                // so `capabilities()` cannot ask), we rethrow too. So the failure goes up whenever
                // the server advertises MOVE, and whenever the question cannot be put at all; the
                // fallback stays the answer to a capability list that does not carry MOVE —
                // including an EMPTY one, which is how [capabilities] reads a server that answers
                // the query with no list, and is not the same as a server proved to lack MOVE.
                val announcesMove = runCatching { hasCapability(CAP_MOVE) }.getOrDefault(true)
                if (announcesMove) throw it
                val byUid = hasCapability(CAP_UIDPLUS)
                val copy = command("UID COPY $set ${mailboxArg(destination)}")
                command("UID STORE $set +FLAGS (\\Deleted)")
                // Best effort, unlike in [delete]: the copy has already landed, so failing here
                // would have the caller retry a move that would copy a second time. Leaving the
                // original flagged is the cheaper error. (`command` is blocking, not suspending —
                // this catch cannot swallow a coroutine cancellation.)
                if (byUid) {
                    runCatching { command("UID EXPUNGE $set") }
                } else {
                    flaggedByFallback += chunk
                }
                copy
            }
            val landed = parseCopyUidReport(result)
            mapping.putAll(landed.uids)
            landed.destinationUidValidity?.let { statedNumbering += it }
        }
        finishPurgeWithoutUidPlus(flaggedByFallback)
        return ImapMoved(mapping, statedNumbering.singleOrNull())
    }

    /** Fetch a single message by UID (envelope + flags), or null if not found — or hidden. */
    fun fetchByUid(uid: Long): ImapMessage? {
        val result = command("UID FETCH $uid (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.messages().firstOrNull()
    }

    /**
     * The messages of a FETCH response — and the ONE place a message flagged `\Deleted` is
     * dropped, so that it cannot appear in a folder list, a search result, a notification or a
     * restored batch (Codeberg #99).
     *
     * Every fetch in this class funnels through here ([fetchPage], [fetchUids], [fetchByUid],
     * and the search scan by way of [fetchUids]), which is why the rule is stated once instead of
     * at each caller. `\Deleted` means the server has been told the message is to go and only an
     * EXPUNGE will finish the job; until then it is still returned by FETCH, by any client. A
     * purge that legitimately stops short of the EXPUNGE — no UIDPLUS and somebody else's message
     * flagged in the folder, see [delete] — would otherwise have its messages walk straight back
     * into the list on the next sync, which reads as "Empty trash did nothing".
     *
     * HIDING IS NOT DELETING, and nothing here makes those messages unreachable: the Empty-trash
     * snapshot enumerates with `UID SEARCH ALL` ([allUids]) and the purge asks `UID SEARCH
     * DELETED`, neither of which is a FETCH. The destroy path still sees, and still destroys,
     * exactly what it did before. Clear the flag from another client and the message comes back
     * on the next fetch, since nothing about it was cached.
     */
    private fun ImapResult.messages(): List<ImapMessage> =
        untagged.mapNotNull { parseFetch(it) }.filterNot { it.deleted }

    private fun parseCopyUid(result: ImapResult): Long? {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        // [COPYUID <uidvalidity> <sourceUid> <destUid>]
        val m = Regex("COPYUID\\s+\\d+\\s+[\\d,:]+\\s+(\\d+)").find(text) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /**
     * Parse one `COPYUID <uidvalidity> <source-set> <dest-set>` response (RFC 4315): the ordered
     * source-UID → destination-UID mapping, and the numbering the server states for the folder
     * those destination UIDs were just assigned in. Both sets correspond positionally, so they're
     * expanded preserving order and zipped. [ImapMoved.NONE] if there is no COPYUID; an empty
     * mapping if the two sets don't line up — the numbering still stands in that case, being a
     * property of the FOLDER and not of the sets.
     *
     * ⭐ The `<uidvalidity>` is the token this parser used to skip with a non-capturing `\d+`,
     * which is the whole of Codeberg #99's Undo half: the server had already said what the
     * destination's numbering was, and the client dropped it on the floor. A value at or below 0
     * reads as "nothing stated", the convention `UidValidity` applies everywhere else.
     */
    private fun parseCopyUidReport(result: ImapResult): ImapMoved {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        val m = Regex("COPYUID\\s+(\\d+)\\s+([\\d,:]+)\\s+([\\d,:]+)").find(text) ?: return ImapMoved.NONE
        return ImapMoved(
            uids = copyUidMapping(m.groupValues[2], m.groupValues[3]),
            destinationUidValidity = m.groupValues[1].toLongOrNull()?.takeIf { it > 0L },
        )
    }

    /**
     * LIST every mailbox, inferring a role from special-use attributes or the name.
     *
     * The attributes are asked for BY NAME (`RETURN (SPECIAL-USE)`, RFC 6154 §5): a server is
     * entitled to withhold `\Trash`/`\Junk`/`\Sent`/`\Drafts`/`\Archive` from a bare LIST and to
     * hand them over only when they were requested. A trash that arrives attribute-less falls
     * through to [roleFromName], which knows the folder names of the nine languages the app
     * speaks and no others, so on a Turkish or Japanese server it gets no role at all — and a
     * delete with no trash to move to DESTROYS the message.
     *
     * Only from a server that said it knows the extension, though. Ask one that never advertised
     * it for a return option and it answers BAD: the LIST throws, the folder drawer comes back
     * empty and the account is unusable — a worse failure than the one being avoided. Servers
     * that never announced SPECIAL-USE keep exactly the command they have always been sent.
     *
     * [onlySubscribed] adds a SECOND round trip, `LSUB "" "*"`, and marks each listed folder by
     * whether the answer named its path ([withSubscriptions]) — what K-9 does, and the only thing
     * this panel does: nothing is filtered here, the flag is carried and `:core:data` decides.
     * Off by default, so every caller that has no use for a subscription (the rename delimiter
     * lookup, the connection test, the push-wake folder refresh) keeps its single LIST.
     *
     * ⛔ NOT `LIST … RETURN (SUBSCRIBED)`, and above all NOT under a capability guard like the one
     * above: Stalwart — our own server — advertises neither LIST-EXTENDED nor SPECIAL-USE, so a
     * guard would switch the feature off exactly where it is used. `LSUB` is IMAP4rev1 core
     * (RFC 3501 §6.3.9) and every server answers it.
     *
     * ⛔ And a LSUB that FAILS means "unknown", never "subscribed to nothing": the round trip is
     * wrapped, `getOrNull()` hands `null` to [withSubscriptions], and every folder stays
     * subscribed. A refusal, a dropped connection or a garbled answer must never empty the drawer.
     */
    fun listFolders(onlySubscribed: Boolean = false): List<ImapFolder> {
        val list = if (hasCapability("SPECIAL-USE")) "LIST \"\" \"*\" RETURN (SPECIAL-USE)" else "LIST \"\" \"*\""
        val folders = parseListFolders(command(list).untagged)
        if (!onlySubscribed) return folders
        val subscribed = runCatching { parseLsubPaths(command("LSUB \"\" \"*\"").untagged) }.getOrNull()
        return withSubscriptions(folders, subscribed)
    }

    /**
     * SELECT a mailbox and read back its status.
     *
     * [expectedUidValidity] is the UIDVALIDITY this caller's UIDs were read under. When the
     * server now reports a different one, every UID in that folder has been reassigned (RFC 3501
     * §2.3.1.1) — a UID that used to mean one message now means another, or nothing — and the
     * call is refused with [ImapUidValidityChanged] BEFORE a single command that names a UID
     * goes out. That is the whole IMAP side of Codeberg #99: a held-back "Empty trash" that
     * finds a renumbered folder must destroy nothing, not destroy "whatever holds those numbers
     * now".
     *
     * `null`, the default, asks nothing and is what every discovery path passes: the first
     * SELECT of a folder has nothing to compare against. A server that reports no UIDVALIDITY at
     * all (0) cannot be checked either way, and is not turned into a refusal — that would break
     * the folder outright on a non-conforming server, which is the wrong direction for a guard
     * whose job is to be conservative about DESTROYING.
     *
     * THE ONE RETRY: a mailbox whose wire name is not a valid encoding of itself — a bare `&`,
     * which some client (including Sterna up to 1.4.3, which sent folder names raw) may have
     * created — is kept verbatim by [decodeMailboxPath], and encoding it again would turn `R&D`
     * into `R&-D` and address a mailbox that does not exist. Such a name cannot be told apart
     * from the legitimate decoding of `R&-D`: the two are the same string, and one encoder cannot
     * map it to both. So the standard form is tried first, and only when the server refuses it is
     * the verbatim form tried — at which point the alternative is a folder the user cannot open
     * at all. SELECT alone gets this: it is read-only and idempotent, and it is the command that
     * blocks. Retrying a CREATE, a MOVE or an APPEND under a second name could file mail into the
     * wrong place. Same shape as the `BADCHARSET` retry in [searchUids], and free when the first
     * form works, which it does for every conformant name.
     */
    fun select(path: String, expectedUidValidity: Long? = null): ImapMailboxStatus {
        val encoded = mailboxArg(path)
        val result = try {
            command("SELECT $encoded")
        } catch (refused: ImapException) {
            val verbatim = quote(path)
            // Only where the ambiguity can exist: the path is its own plausible wire form, i.e.
            // pure ASCII that the encoder would nonetheless have changed. A non-ASCII path has
            // exactly one wire form, so a refusal there means what it says and putting raw UTF-8
            // on the wire would only be noise.
            if (verbatim == encoded || path.any { it.code !in 0x20..0x7E }) throw refused
            command("SELECT $verbatim")
        }
        var exists = 0
        // ⛔ Zero is also what this holds when the server said NOTHING about the folder's size, and
        // the two must not read alike: a walk built on an unstated zero fetches no page, brings back
        // no UID, and the reconcile above takes that for an empty folder and DELETES the cache. So
        // the count carries whether it was STATED — an `* n EXISTS` line whose n parsed.
        var existsObserved = false
        var uidValidity = 0L
        var uidNext = 0L
        for (resp in result.untagged) {
            // * <n> EXISTS  |  * OK [UIDVALIDITY n] ...  |  * OK [UIDNEXT n] ...
            if (resp.getOrNull(2) == "EXISTS") {
                // Same reading as [folderMoved]'s, and deliberately the same strictness: an n that
                // will not parse is not a count. There it means "assume movement", here "assume
                // nothing" — both refuse rather than invent a number.
                (resp.getOrNull(1) as? String)?.toIntOrNull()?.let { exists = it; existsObserved = true }
            }
            val flat = resp.joinToString(" ") { it?.toString() ?: "NIL" }
            Regex("UIDVALIDITY (\\d+)").find(flat)?.let { uidValidity = it.groupValues[1].toLong() }
            Regex("UIDNEXT (\\d+)").find(flat)?.let { uidNext = it.groupValues[1].toLong() }
        }
        if (expectedUidValidity != null && expectedUidValidity > 0L &&
            uidValidity > 0L && uidValidity != expectedUidValidity
        ) {
            throw ImapUidValidityChanged(path, expectedUidValidity, uidValidity)
        }
        return ImapMailboxStatus(exists, uidValidity, uidNext, existsObserved)
    }

    /**
     * Fetch a page of messages by sequence number, newest first. [offset] skips the
     * newest N messages; up to [limit] are returned. Uses the [exists] count from a
     * prior SELECT to map "newest" to high sequence numbers.
     */
    fun fetchPage(exists: Int, offset: Int, limit: Int): List<ImapMessage> {
        val highest = exists - offset
        if (highest < 1) return emptyList()
        val lowest = (highest - limit + 1).coerceAtLeast(1)
        val result = command("FETCH $lowest:$highest (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.messages().sortedByDescending { it.uid }
    }

    /**
     * Read the newest [limit] messages of the SELECTed folder in requests of [pageSize] sequence
     * positions, handing each page to [onPage] AS IT LANDS and keeping only UIDs.
     *
     * ⛔ [status] whole, and not just its count: the walk answers with [ImapFolderWalk.folderStatedEmpty],
     * which is the difference between a folder the server said holds nothing and a folder whose size
     * the server never stated ([ImapMailboxStatus.existsObserved]) — and the caller DELETES on that
     * difference. Taking an `Int` here let the call site pair a count with a provenance of its own.
     *
     * ⛔ Why pages at all: [fetchPage] puts the whole window in one `FETCH`, and everything that
     * command brings back is parsed and held before a single message is read out of it. At the
     * window sizes this app offers that is a folder's worth of envelopes on one heap. Paginating is
     * the only honest fix — capping what the parser keeps ([command]'s `maxTokensKept`) would
     * TRUNCATE the answer silently, and the messages it dropped would then be missing from the
     * walk's UIDs and deleted from the cache by the caller's reconcile.
     *
     * ⛔ What the caller owes back: [ImapFolderWalk.moved]. A folder that changed while this read it
     * must not be reconciled against — [folderMoved] states exactly which messages are at risk, and
     * it is NOT "one that fell between two pages": the sequence shift is downwards and this walk
     * descends, so what a renumbering costs is the BOTTOM of the window, plus anything another path
     * cached meanwhile.
     *
     * Movement is asked about in two places, and both are load-bearing:
     * - a `NOOP` before every request but the first, because that is the only way a server may tell
     *   us: RFC 3501 §7.4.1 forbids sending `EXPUNGE` while answering a `FETCH`, so it holds the
     *   news until a command that may carry it — and until it has sent it, it may not renumber
     *   anything either;
     * - the untagged lines of every `FETCH` response, which is not belt-and-braces: a walk of ONE
     *   page (a 50-message window, the unified refresh, a small folder) sends no `NOOP` at all, so
     *   this is then the only detector there is.
     *
     * The walk does NOT stop when it notices movement: the remaining pages are still mail the user
     * wants cached, writing them deletes nothing, and only the reconcile is unsafe. It carries the
     * verdict to the end instead.
     *
     * [onPage] receives only messages the walk has not already handed over, so an overlapping page
     * costs one wasted request and nothing else. ⚠ It runs inside the caller's session — that is
     * what "write each page as it lands" means — so the account's ONE connection is held for the
     * length of the walk INCLUDING the caller's writes, and every other IMAP operation on that
     * account queues behind it. The single `FETCH` this replaces held the connection too, but not
     * across a database write; on today's windows the difference is noise, on an unbounded one it
     * would not be.
     */
    suspend fun walkFolder(
        status: ImapMailboxStatus,
        limit: Int,
        pageSize: Int,
        onPage: suspend (List<ImapMessage>) -> Unit,
    ): ImapFolderWalk {
        val exists = status.exists
        val lowest = folderWindowLowest(exists, limit)
        // De-duplicated BY UID: the walk's own pages can overlap after a renumbering, and a
        // sequence number is not a name. Insertion-ordered so the result stays newest-first.
        val seen = LinkedHashSet<Long>()
        var moved = false
        var previous: IntRange? = null
        while (true) {
            val page = nextFolderPage(lowest, exists, previous, pageSize) ?: break
            // Every request but the first: give the server its chance to say the folder moved,
            // BEFORE the numbering of the range below is used. `folderMoved(...) || moved` and not
            // the other way round, so the NOOP goes out whatever the verdict so far.
            if (previous != null) moved = folderMoved(command("NOOP").untagged, exists) || moved
            val result = command("FETCH ${page.first}:${page.last} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
            // Not a second opinion: on a one-page walk no NOOP is ever sent, so this line is the
            // only thing that can notice the folder moving.
            moved = folderMoved(result.untagged, exists) || moved
            // Through messages(), like every other fetch here: it is the one place a `\Deleted`
            // message is dropped, and a walk that read the untagged lines itself would walk them
            // straight back into the list (Codeberg #99).
            val fresh = result.messages().sortedByDescending { it.uid }.filter { seen.add(it.uid) }
            if (fresh.isNotEmpty()) onPage(fresh)
            previous = page
        }
        // ⛔ "Nothing came back" is not "there is nothing": an empty [seen] is what a folder of zero
        // messages gives, and equally what a SELECT that never said how big the folder is gives, and
        // what a page whose every FETCH was unreadable gives. Only the first may clear a cache, and
        // only the SELECT can tell them apart — hence a count the server STATED, and zero.
        return ImapFolderWalk(
            uids = seen.toList(),
            moved = moved,
            folderStatedEmpty = status.existsObserved && exists == 0,
        )
    }

    /**
     * Number of unseen messages in the selected mailbox, `\Deleted` ones excluded.
     *
     * `UNDELETED` is not a detail: this count is what the IMAP account badge shows, while the
     * list it labels drops those same messages ([messages]). Counting them here would put an
     * unread badge over a folder with nothing in it to open.
     */
    fun unseenCount(): Int {
        val result = command("SEARCH UNSEEN UNDELETED")
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return 0
        return line.drop(2).count { it is String }
    }

    /**
     * UIDs in the SELECTed mailbox matching a built [ImapSearchCommand].
     *
     * `CHARSET UTF-8` is declared only when a search value actually needs it; RFC 3501 lets a
     * server support US-ASCII alone and answer `NO [BADCHARSET]`, so that case retries once
     * without the declaration rather than turning an accented search into a failed one.
     */
    fun searchUids(search: ImapSearchCommand): List<Long> {
        val result = try {
            command("UID SEARCH ${search.arguments()}")
        } catch (e: ImapException) {
            if (!search.needsUtf8) throw e
            command("UID SEARCH ${search.arguments(declareUtf8 = false)}")
        }
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return emptyList()
        return line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() }
    }

    /**
     * At most [cap] UIDs from the SELECTed mailbox, via `UID SEARCH ALL`.
     *
     * The whole folder, not the synced window, and without fetching a single envelope: one
     * command, one line of numbers. `ALL` is the RFC 3501 search key every server implements,
     * so this is the cheap way to enumerate a folder whose messages were never scrolled to
     * (used to freeze an "Empty trash", Codeberg #99).
     *
     * [cap] is enforced DURING the parse, not after: without ESEARCH the server cannot be asked
     * for fewer, so a 200 000-message folder is read to the end (the stream must stay in sync)
     * while only [cap] ids are ever held. The cap spans the whole response, not each line — a
     * server splitting its answer must not be handed K times the cap — give or take the few
     * tokens each further line is always allowed so its status stays readable.
     *
     * The ids kept are therefore the ones the server listed FIRST, i.e. ascending UID, the
     * OLDEST. Note that the JMAP snapshot caps at the other end (its query sorts newest first),
     * so the two protocols keep opposite slices of an over-cap folder; only "the surplus
     * survives and emptying again clears the next slice" is common to both. Keeping the newest
     * here would mean building all 200 000 ids to compare them — a running top-K holds only
     * [cap] of them, but it must still parse every one, which is the work this avoids. Worth
     * revisiting the day a real Trash exceeds the cap.
     *
     * Unlike [searchUids] this reads EVERY untagged `SEARCH` line, not just the first: a server
     * is free to split a long result across several, and here a dropped tail would silently
     * shorten the list of what to destroy. No `CHARSET` question either — `ALL` is pure ASCII.
     */
    fun allUids(cap: Int): List<Long> = uidSearchCapped("ALL", cap)

    /**
     * The HIGHEST [cap] UIDs of the SELECTed mailbox, ascending, via `UID SEARCH ALL`.
     *
     * The other end of the folder from [allUids], and it is why this exists rather than a `cap`
     * argument on that one: the caller here is the draft de-duplication of #95, whose copy — if the
     * server kept it — is the LAST thing `APPEND`ed to Drafts. Keeping the head, as [allUids] does,
     * would look precisely where the copy is not, on any folder holding more than [cap] drafts.
     *
     * `ALL` because it is the one RFC 3501 search key every server implements. `HEADER
     * "Message-ID"` is not: Stalwart answers an empty `* SEARCH` to every shape of it while
     * answering `HEADER "Subject"` perfectly, and an empty answer is indistinguishable from "your
     * draft is not there" — which is how a second copy of the same text got appended.
     *
     * Like [allUids] it reads EVERY untagged `SEARCH` line, not just the first ([searchUids] reads
     * only the first): a server is free to split a long answer, and here a dropped tail is exactly
     * the newest ids, i.e. the answer. No `CHARSET` question — `ALL` is pure ASCII.
     *
     * ⚠ **No cap during the parse, unlike [allUids], and no way round it**: the ids wanted are at
     * the END, so they cannot be known before the response is read whole; and the sort is what
     * makes "the highest" true rather than "the last listed", the RFC promising no order at all.
     * The cost is a list of the folder's ids held for the length of one call — accepted because
     * this reads a DRAFTS folder, once per deferred retry, and never a Trash of 200 000.
     *
     * ⛔ An empty answer is an empty list, and a refused search still THROWS — the caller's only
     * safe reading of "I could not look" is "append it", never "it is already there".
     */
    fun newestUids(cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        return command("UID SEARCH ALL").untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .sorted()
            .takeLast(cap)
    }

    /**
     * At most [cap] UIDs matching one plain search [key] (`ALL`, `DELETED`, …), with the cap
     * enforced during the parse — the shape [allUids] documents at length, shared with the
     * "is anything else flagged?" question of [finishPurgeWithoutUidPlus]. Both read EVERY
     * untagged `SEARCH` line, not just the first: a dropped tail would shorten a list that is
     * about to decide what gets destroyed. Keys used here are pure ASCII, so no `CHARSET`.
     */
    private fun uidSearchCapped(key: String, cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        // +2 leaves room for the "*" and "SEARCH" that open the first line.
        val keep = if (cap > Int.MAX_VALUE - 2) Int.MAX_VALUE else cap + 2
        return command("UID SEARCH $key", maxTokensKept = keep).untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .take(cap)
    }

    /** Fetch several messages by UID (envelope + flags); `\Deleted` ones are not returned. */
    fun fetchUids(uids: List<Long>): List<ImapMessage> {
        if (uids.isEmpty()) return emptyList()
        val result = command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.messages()
    }

    /** Raw content of one MIME section (e.g. an attachment), still transfer-encoded. */
    fun fetchSection(uid: Long, section: String): String =
        command("UID FETCH $uid (BODY.PEEK[$section])").bodyItem()

    /**
     * The first [maxBytes] octets of one MIME section, still transfer-encoded — a partial fetch
     * (RFC 3501 §6.4.5), for a caller that wants an opening and not a message.
     *
     * ⛔ `BODY.PEEK`, like every body fetch in this class, and here it is the load-bearing word: the
     * caller is a NOTIFICATION, so a bare `BODY[…]` would set `\Seen` on the server the moment the
     * mail is announced. That is irreversible from here — the message leaves the unread list on
     * every device the account is open on, and nothing in the app ever asked for it to be read.
     *
     * ⛔ And `<0.[maxBytes]>`, which is the other half: without it the server sends the WHOLE part,
     * which for a mail carrying a 20 MB inline body is 20 MB pulled through a foreground service on
     * mobile data, to show two lines of it.
     *
     * A short answer is not an error: a part smaller than [maxBytes] comes back whole, and a server
     * that answers with no body item at all yields "" — see [bodyItem].
     *
     * ⭐ The `take` is what makes "[maxBytes] at most" TRUE rather than merely REQUESTED. `<0.n>` is
     * a request, and nothing above enforces it: a literal is read up to 32 MiB, and [bodyItem] takes
     * whatever item begins with `BODY` — so a server that ignores the partial, or answers `BODY[]`,
     * would have the whole message decoded before anyone could throw it away. Deliberately NOT a
     * conformance check: the partial fetch is normative and a refusal would be surface for a case
     * that does not happen; truncating costs one call and makes the property hold client-side.
     */
    fun fetchSectionPartial(uid: Long, section: String, maxBytes: Int): String =
        command("UID FETCH $uid (BODY.PEEK[$section]<0.$maxBytes>)").bodyItem().take(maxBytes)

    /** Raw RFC822 source of a message, for parsing the body/attachments. */
    fun fetchSource(uid: Long): String =
        command("UID FETCH $uid (BODY.PEEK[])").bodyItem()

    /**
     * The body item of a FETCH answer, still transfer-encoded — the one reading shared by the three
     * body fetches above, so that a fourth cannot arrive with a reading of its own.
     *
     * ⛔ The empty string when the answer carries NO body item. That is not pedantry: the item list
     * opens with `UID <n>`, so an `indexOfFirst` that answered -1 and was used as-is handed back the
     * atom at index 0 — the string "UID" — as though it were the message. On the attachment path
     * that was 3 bytes written to a file; on the preview path it would be the word UID on a lock
     * screen, presented as somebody's mail.
     *
     * The item is matched by prefix because a partial fetch is answered with the origin octet
     * attached (`BODY[1]<0>`, RFC 3501 §7.4.2) — one atom, not two, since the IMAP parser breaks
     * atoms on parentheses and spaces and this form has neither.
     */
    private fun ImapResult.bodyItem(): String {
        val fetch = untagged.firstOrNull { it.getOrNull(2) == "FETCH" } ?: return ""
        @Suppress("UNCHECKED_CAST")
        val items = fetch.getOrNull(3) as? List<Any?> ?: return ""
        val idx = items.indexOfFirst { it is String && it.startsWith("BODY", true) }
        if (idx < 0) return ""
        return (items.getOrNull(idx + 1) as? String).orEmpty()
    }

    fun setFlag(uid: Long, flag: String, set: Boolean) {
        val op = if (set) "+FLAGS" else "-FLAGS"
        command("UID STORE $uid $op ($flag)")
    }

    fun delete(uid: Long) = delete(listOf(uid))

    /**
     * Permanently delete many messages with one `UID STORE <set> +FLAGS (\Deleted)` per chunk
     * (Codeberg #29), erased with `UID EXPUNGE <set>` — "erase exactly these" — when the server
     * implements UIDPLUS (RFC 4315). The caller must have SELECTed the mailbox.
     *
     * Whether it does is asked ONCE, before the first chunk is touched, and not chunk by chunk:
     * the answer cannot change mid-operation, and the alternative it selects is folder-wide, so
     * asking per chunk would mean applying a folder-wide command up to fifty times over an
     * "Empty trash" (Codeberg #99). What happens when the answer is no is [purgeWithoutUidPlus]
     * — one decision, taken in one place, applied once per operation.
     *
     * A refused `UID EXPUNGE` on a server that DOES advertise UIDPLUS throws, and the destroy
     * worker retries: unlike a move, a repeated destroy costs nothing.
     */
    fun delete(uids: List<Long>) {
        val flagged = uids.distinct()
        val sets = flagged.chunked(UID_SET_CHUNK).map { compressUidSet(it) }.filter { it.isNotEmpty() }
        if (sets.isEmpty()) return
        val byUid = hasCapability(CAP_UIDPLUS)
        for (set in sets) {
            command("UID STORE $set +FLAGS (\\Deleted)")
            if (byUid) command("UID EXPUNGE $set")
        }
        // The union of every chunk, not a chunk: the folder-wide question is asked about the
        // whole operation, once, after the last message has been flagged.
        if (!byUid) finishPurgeWithoutUidPlus(flagged.toSet())
    }

    /**
     * Close a purge on a server without UIDPLUS, according to [purgeWithoutUidPlus]. Called at
     * most once per operation, after every message meant to go has been flagged `\Deleted`;
     * [flagged] is the union of every chunk this operation flagged in the selected mailbox.
     */
    private fun finishPurgeWithoutUidPlus(flagged: Set<Long>) {
        if (flagged.isEmpty()) return
        when (purgeWithoutUidPlus) {
            PurgeWithoutUidPlus.LEAVE_FLAGGED -> Unit
            PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS -> if (nothingElseIsFlagged(flagged)) command("EXPUNGE")
            PurgeWithoutUidPlus.EXPUNGE_WHOLE_FOLDER -> command("EXPUNGE")
        }
    }

    /**
     * Ask the server what is flagged `\Deleted` in the selected mailbox and answer whether a
     * bare `EXPUNGE` would erase [flagged] and nothing besides.
     *
     * `UID SEARCH DELETED` is one command and one line of numbers, the same cheap enumeration
     * [allUids] uses, and it is asked ONCE per operation — never per chunk, which is the whole
     * point of taking the decision here rather than inside the loop.
     *
     * A STRICT SUBSET is a yes, not a no: every id the server reports is one of ours, so the
     * `EXPUNGE` cannot reach anything else. (It happens when a message has already gone, or when
     * another client cleared a flag.) An empty answer is a no — there is nothing to erase, so the
     * command would only be noise. Anything the server reports that we did not flag is a no.
     *
     * The list is capped at one more than [flagged]: enough to tell "the same or fewer" from
     * "more" — one extra id cannot fit inside a set of our size — and never enough for a folder
     * holding two hundred thousand flagged messages to be held in memory. A truncated answer is
     * therefore always a no, which is the safe direction.
     *
     * THE RACE, stated plainly because this is the design record: another client can flag a
     * message between this `SEARCH` and the `EXPUNGE` that follows it, and that message would be
     * destroyed. The window is the round trip between two adjacent commands — milliseconds —
     * against the unbounded exposure of expunging on no evidence at all, which is what this
     * replaces. It is narrowed, not closed, and IMAP without UIDPLUS offers no way to close it.
     */
    private fun nothingElseIsFlagged(flagged: Set<Long>): Boolean {
        val onServer = uidSearchCapped("DELETED", cap = flagged.size + 1)
        return onServer.isNotEmpty() && onServer.size <= flagged.size && flagged.containsAll(onServer)
    }

    /** APPEND a raw message into [mailbox] (e.g. a sent copy into Sent), with [flags]. */
    fun append(mailbox: String, message: String, flags: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val tag = "a${++tagN}"
        val flagPart = if (flags.isNotBlank()) "($flags) " else ""
        output.write("$tag APPEND ${mailboxArg(mailbox)} $flagPart{${bytes.size}}\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val cont = input.readResponse()
        if (cont.getOrNull(0) != "+") throw ImapException("APPEND not accepted: $cont")
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.getOrNull(0) == tag) {
                val status = resp.getOrNull(1) as? String ?: "BAD"
                if (status != "OK") throw ImapException("APPEND failed: ${resp.drop(1).joinToString(" ")}")
                return
            }
        }
    }

    /**
     * Say `LOGOUT`, read its completion, and close the socket — with the READ bounded by
     * [LOGOUT_READ_BUDGET_MS].
     *
     * ⛔ **The LOGOUT is still sent and its answer is still read.** Dropping either would leave the
     * session open on the server until its own idle timeout, and an account with a small
     * simultaneous-connection limit then refuses the NEXT upload. What changed is only the bound on
     * the read — and on a peer that has gone MUTE (no FIN, so `ImapParser`'s end-of-stream check
     * never fires) that bound does not replace the same thing on the two ways in here:
     *
     * - from `ImapMailService.runWithRetry` it replaces NOTHING: `withReadTimeoutSuspending` puts
     *   `soTimeout` back in its `finally` and the close is made from the `catch`, i.e. outside the
     *   bounded block, so this read was an unbounded `recvfrom` holding the account mutex for as
     *   long as the OS took to notice (#95, measured at 9 minutes on the bench);
     * - from `ImapIdleConnection.close` it replaces 28 MINUTES: [idle] arms `soTimeout` to its
     *   refresh window and is parked inside it while the close arrives on ANOTHER thread, so the
     *   socket still carries that value and the goodbye inherited the IDLE window.
     */
    override fun close() {
        runCatching { withReadTimeout(LOGOUT_READ_BUDGET_MS) { command("LOGOUT") } }
        runCatching { socket.close() }
    }

    // ---- FETCH parsing ----

    private fun parseFetch(resp: List<Any?>): ImapMessage? {
        if (resp.getOrNull(2) != "FETCH") return null
        @Suppress("UNCHECKED_CAST")
        val items = resp.getOrNull(3) as? List<Any?> ?: return null
        val map = pairUp(items)

        val uid = (map["UID"] as? String)?.toLongOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val flags = (map["FLAGS"] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val envelope = map["ENVELOPE"] as? List<Any?>

        val subject = decodeWords(envelope?.getOrNull(1) as? String)
        val dateMillis = parseDate(envelope?.getOrNull(0) as? String)
        @Suppress("UNCHECKED_CAST")
        val fromAddr = (envelope?.getOrNull(2) as? List<Any?>)?.firstOrNull() as? List<Any?>
        val fromName = decodeWords(fromAddr?.getOrNull(0) as? String)
        // The local part and the domain need [decodeHeaderBytes] just as much as the display
        // name beside them, and for a sharper reason: an address is not shown and forgotten, it
        // is PERSISTED (EmailEntity.fromEmail), indexed for search, offered as a contact
        // suggestion and prefilled as the recipient of a reply. Getting it wrong sends mail to an
        // address that does not exist.
        //
        // Not hypothetical. RFC 3501 forbids 8-bit bytes in a quoted-string, so a conforming
        // server hands over ANY non-ASCII envelope value as a literal — precisely the token whose
        // reading changed. An EAI address (RFC 6531, which Stalwart speaks) arrives that way.
        val mailbox = (fromAddr?.getOrNull(2) as? String)?.let(::decodeHeaderBytes)
        val hostPart = (fromAddr?.getOrNull(3) as? String)?.let(::decodeHeaderBytes)
        val fromEmail = if (mailbox != null && hostPart != null) "$mailbox@$hostPart" else null
        // Envelope "to" (index 5): every parseable address — Sent-folder rows show the
        // recipients, not the sender (Codeberg #59). Group-syntax delimiters (no host) are
        // skipped. The halves of an address get [decodeHeaderBytes], same treatment as the From
        // address above and for the same reason — see [envelopeAddresses].
        @Suppress("UNCHECKED_CAST")
        val toAddrs = envelopeAddresses(envelope?.getOrNull(5) as? List<Any?>)
        // Envelope "reply-to" (index 4): where the SENDER said to answer. Read exactly like the
        // recipients above, decodeHeaderBytes included — this address is not displayed and
        // forgotten, it is the one a reply is addressed to, so a mangled one sends mail nowhere.
        //
        // ⛔ Index 4, between sender (3) and to (5). Taking its neighbour would answer the copied
        // parties instead, and the address the sender designated would hear nothing.
        @Suppress("UNCHECKED_CAST")
        val replyToAddrs = envelopeAddresses(envelope?.getOrNull(4) as? List<Any?>)
        // Envelope "cc" (index 6): the people put in copy. Read exactly like the recipients above,
        // decodeHeaderBytes on BOTH halves included — a copied address is not displayed and
        // forgotten, it is prefilled as a recipient of a reply-all and re-sent, so a mangled host
        // sends the copy to a domain that does not exist while the message looks sent.
        @Suppress("UNCHECKED_CAST")
        val ccAddrs = envelopeAddresses(envelope?.getOrNull(6) as? List<Any?>)
        // Envelope "bcc" (index 7): the BLIND copies, and the reason the two slots are read
        // separately here and stored in separate columns downstream.
        //
        // ⛔ Index 7 is not index 6. Filling `cc` from this slot would publish, on screen and in
        // the recipient list of a reply-all, addresses the sender chose to hide — the one mistake
        // in this function that cannot be walked back once the reply is sent.
        @Suppress("UNCHECKED_CAST")
        val bccAddrs = envelopeAddresses(envelope?.getOrNull(7) as? List<Any?>)
        val messageId = envelope?.getOrNull(9) as? String
        val inReplyTo = envelope?.getOrNull(8) as? String

        return ImapMessage(
            uid = uid,
            subject = subject,
            fromName = fromName,
            fromEmail = fromEmail,
            to = toAddrs,
            replyTo = replyToAddrs,
            cc = ccAddrs,
            bcc = bccAddrs,
            dateMillis = dateMillis,
            seen = flags.any { it.equals("\\Seen", true) },
            flagged = flags.any { it.equals("\\Flagged", true) },
            answered = flags.any { it.equals("\\Answered", true) },
            deleted = flags.any { it.equals("\\Deleted", true) },
            hasAttachment = hasAttachment(map["BODYSTRUCTURE"]),
            messageId = messageId,
            inReplyTo = inReplyTo,
            // The same structure the line above reduces to a boolean, read a second way and kept:
            // which section holds the text, and how to decode it. Costs no command — it is here
            // because every fetch in this class already asks for BODYSTRUCTURE.
            textPart = firstTextPart(map["BODYSTRUCTURE"]),
        )
    }

    /** Turn a flat FETCH item list [k1, v1, k2, v2, ...] into a name→value map. */
    private fun pairUp(items: List<Any?>): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var i = 0
        while (i < items.size) {
            val key = (items[i] as? String)?.uppercase()
            if (key == null) {
                i++
                continue
            }
            map[key] = items.getOrNull(i + 1)
            i += 2
        }
        return map
    }

    private fun hasAttachment(bodystructure: Any?): Boolean {
        fun walk(node: Any?): Boolean = when (node) {
            is List<*> -> node.any { child ->
                (child is String && child.equals("attachment", true)) || walk(child)
            }
            else -> false
        }
        return walk(bodystructure)
    }

    private companion object {
        fun parseDate(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val cleaned = raw.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            return runCatching { ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

    }
}

/**
 * IMAP quoted-string. Per RFC 3501 a quoted-string may not contain CR or LF; a raw
 * newline here would terminate the command line and let an attacker-controlled value
 * (folder name, search text) inject a second authenticated IMAP command. Reject it.
 *
 * File-level (not a method) so the SEARCH-command builder in ImapSearch.kt quotes its
 * user-supplied values through the exact same guard.
 */
internal fun quote(s: String): String {
    if (s.any { it == '\r' || it == '\n' }) throw ImapException("Illegal newline in IMAP argument")
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Render one parsed response token back to text, so a response code can be matched in it. */
internal fun flatten(v: Any?): String = when (v) {
    is List<*> -> v.joinToString(" ") { flatten(it) }
    null -> "NIL"
    else -> v.toString()
}

/**
 * The ID extension (RFC 2971): the one that lets a client name itself. Looked for in the
 * post-authentication list — see [ImapSession.identify].
 */
internal const val CAP_ID = "ID"

/** What Sterna calls itself in the RFC 2971 `ID` command. Not a user-visible string: it is a
 *  protocol field, it is never translated, and no `strings.xml` carries it. */
internal const val CLIENT_NAME = "Sterna Mail"

/** The UIDPLUS extension (RFC 4315): the one that provides `UID EXPUNGE <set>`. */
internal const val CAP_UIDPLUS = "UIDPLUS"

/**
 * The MOVE extension (RFC 6851): the one that provides `UID MOVE <set> <dest>`. Consulted only
 * to tell "this server has no MOVE" from "this MOVE failed" — see [ImapSession.move]; never to
 * decide whether to try the command.
 */
internal const val CAP_MOVE = "MOVE"

/**
 * Capability names from the untagged `* CAPABILITY …` line of a `CAPABILITY` command, upper-cased.
 * Empty when the server answered without one — read as "no optional extension", the conservative
 * reading, rather than as an unknown to be asked about again.
 */
internal fun parseCapabilities(untagged: List<List<Any?>>): Set<String> =
    untagged.firstOrNull { (it.getOrNull(1) as? String)?.equals("CAPABILITY", ignoreCase = true) == true }
        ?.drop(2)
        ?.mapNotNull { (it as? String)?.uppercase() }
        ?.toSet()
        .orEmpty()

/**
 * Capability names from a `[CAPABILITY …]` response code on [line], or `null` when it carries
 * none — which is not the same as carrying an empty one, hence the nullable return: a login
 * completion without the code leaves the question open for a later `CAPABILITY` command.
 *
 * Matched over the flattened line rather than token by token: `[` and `]` are not IMAP token
 * delimiters, so the parser hands back atoms like `[CAPABILITY` and `UIDPLUS]`. This is the same
 * shape [ImapSession.select] uses to read `[UIDVALIDITY n]`.
 */
internal fun capabilitiesInResponseCode(line: List<Any?>): Set<String>? {
    val flat = line.joinToString(" ") { flatten(it) }
    val code = Regex("\\[CAPABILITY([^\\]]*)\\]", RegexOption.IGNORE_CASE).find(flat) ?: return null
    return code.groupValues[1].split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet()
}

/**
 * The response code of [line] (RFC 5530): the first bracketed WORD, upper-cased —
 * `[AUTHENTICATIONFAILED] oops` answers `"AUTHENTICATIONFAILED"`, `[REFERRAL imap://…]` answers
 * `"REFERRAL"` — or `null` when the line carries no bracket at all. Matched over the flattened
 * line for the same reason as [capabilitiesInResponseCode]: `[` and `]` are not IMAP token
 * delimiters, so the parser hands back atoms like `[AUTHENTICATIONFAILED]` whole.
 *
 * On an OK completion, `[CAPABILITY …]` is itself a response code, so this answers
 * `"CAPABILITY"` on such a line. Deliberate and harmless: the throw sites only ask on a
 * tagged NO/BAD, where the bracketed word is the failure's name.
 */
internal fun responseCodeIn(line: List<Any?>): String? {
    val flat = line.joinToString(" ") { flatten(it) }
    return Regex("\\[([^\\]\\s]+)").find(flat)?.groupValues?.get(1)?.uppercase()
}

/**
 * What a permanent purge does on a server that does NOT implement UIDPLUS (RFC 4315), i.e. one
 * where `UID EXPUNGE <set>` — "erase exactly these" — does not exist.
 *
 * The only other way to erase is a bare `EXPUNGE`, which erases EVERY message flagged `\Deleted`
 * in the selected folder: the ones this app just flagged, but equally ones flagged from another
 * client, or flagged long ago and left there. Sent blind it destroys mail nobody designated; not
 * sent at all it leaves the folder un-emptied while the screen says otherwise.
 *
 * What breaks the deadlock is that the server can be ASKED what is flagged, and the answer is
 * one cheap command. So the honest option is not a compromise between the two but a condition on
 * the second: expunge when, and only when, the folder's flagged set is provably ours.
 *
 * This is a product decision, not a technical one, which is why it is a single named value
 * rather than a condition spread over the purge paths.
 */
internal enum class PurgeWithoutUidPlus {
    /**
     * Flag `\Deleted` and stop, always. Nothing is ever erased that the user did not designate;
     * in exchange the folder is never actually emptied on such a server.
     */
    LEAVE_FLAGGED,

    /**
     * Ask the server what is flagged, and send one bare `EXPUNGE` only if everything flagged is
     * something this operation flagged (see [ImapSession.nothingElseIsFlagged], which also states
     * the residual race). Otherwise behave as [LEAVE_FLAGGED]. Costs one extra command per
     * operation on servers without UIDPLUS, and nothing at all on servers with it.
     */
    EXPUNGE_WHEN_ONLY_OURS,

    /**
     * One bare `EXPUNGE` for the whole operation, asking nothing. The folder really is emptied —
     * including any message flagged `\Deleted` by somebody else, destroyed with no way back.
     */
    EXPUNGE_WHOLE_FOLDER,
}

/**
 * THE policy line. Changing this single assignment switches every purge path in this file
 * ([ImapSession.delete] and the copy fallback of [ImapSession.move]) and nothing else; both
 * extremes stay one word away, so neither has to be reconstructed to be tried.
 *
 * It ships as [PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS]: the Trash is genuinely emptied
 * whenever that can be shown to harm nothing, and destroying less than ordered remains the error
 * it falls back to (Codeberg #99).
 */
internal val purgeWithoutUidPlus = PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS

/**
 * Cap on how many UIDs go into one `UID MOVE`/`UID STORE` sequence-set, so an enormous
 * selection is split across a few commands instead of one over-long line. 200 UIDs
 * compress to well under any server's command-length limit.
 */
private const val UID_SET_CHUNK = 200

/**
 * How long [ImapSession.close] may wait for the answer to its `LOGOUT`.
 *
 * ⭐ **Why five seconds, and why a number at all**: this is not a read that does work, it is a
 * teardown courtesy. On a live link a `LOGOUT` costs one round trip — under three seconds even on a
 * bad mobile link — and on a dead one waiting buys nothing at all, since the bytes we are waiting
 * for are never coming. So the number only has to be comfortably above a slow round trip.
 *
 * ⚠ It is also a cost paid under the account mutex, and paid TWICE at worst: `runWithRetry` closes
 * the session on failure and, with `retryOnFailure`, can do so again on the second attempt. Five
 * seconds is therefore ten seconds of worst-case teardown; a minute here would be two.
 */
private const val LOGOUT_READ_BUDGET_MS = 5_000

/** Tokens always readable on a line, however spent a caller's token budget is: enough for a
 *  tagged status line (`a12 NO [BADCHARSET] …`) to stay recognisable. */
private const val STATUS_LINE_TOKENS = 8

/** Untagged responses collected for one tagged command. */
internal class ImapResult(
    val status: String,
    val untagged: List<List<Any?>>,
    val tagged: List<Any?> = emptyList(),
)

/**
 * Map the untagged responses of a `LIST` command to [ImapFolder]s, dropping
 * non-selectable container mailboxes. A `\Noselect` (or `\NonExistent`) entry can't be
 * opened: Gmail nests its special mailboxes under such a container literally named
 * "[Gmail]", so surfacing it only adds a dead level in the drawer. Its selectable
 * children keep their full path and re-parent to top level (the drawer infers parents
 * from the path, finds the dropped container missing, and promotes them). INBOX is
 * never dropped, even if a server mislabels it.
 *
 * THE ONE PLACE a mailbox name is decoded ([decodeMailboxPath], Codeberg #101): everything
 * above this function holds Unicode, and [ImapSession] encodes again on its way out.
 *
 * [ImapFolder.path] is the FAITHFUL decoding — nothing filtered, and nothing decoded that would
 * not encode back byte for byte — because it is an identifier: it has to reproduce the exact
 * bytes the server sent or the folder stops opening. The anti-spoofing filter therefore applies
 * to [ImapFolder.name], the display leaf, and only there. A path is never displayed and a name
 * is never sent.
 */
internal fun parseListFolders(untagged: List<List<Any?>>): List<ImapFolder> {
    val listed = untagged.mapNotNull { resp ->
        // * LIST (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LIST") return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val attrs = (resp.getOrNull(2) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        val delim = resp.getOrNull(3) as? String ?: "/"
        val wire = resp.getOrNull(4) as? String ?: return@mapNotNull null
        val path = decodeMailboxPath(wire)
        val leaf = path.substringAfterLast(delim)
        // A conforming name is modified UTF-7, i.e. pure ASCII on the wire, and its decoding is
        // already text — nothing to reinterpret. A name that arrived with 8-bit bytes is not
        // conforming: some client sent raw UTF-8 (Sterna itself did, up to 1.4.3 — see the
        // retry in [select]), [decodeMailboxPath] rightly kept it verbatim, and what the parser
        // hands over is therefore octets. Reading them before displaying them is the difference
        // between "Помеченные" and a truncated mojibake, since [stripBidiAndControls] eats
        // U+0080–U+009F. Testing the WIRE form, not the decoded one, is what keeps this free of
        // false positives: a legitimately decoded name can never trigger it.
        //
        // [path] is deliberately NOT touched. It is the identifier, it has to reproduce the
        // server's exact bytes, and it is the only reason the folder stays addressable.
        val name = stripBidiAndControls(if (wire.any { it.code >= 0x80 }) decodeHeaderBytes(leaf) else leaf)
        val nonSelectable = attrs.any {
            it.equals("\\Noselect", ignoreCase = true) || it.equals("\\NonExistent", ignoreCase = true)
        }
        if (nonSelectable && !path.equals("INBOX", ignoreCase = true)) return@mapNotNull null
        ListedFolder(name = name, path = path, delimiter = delim, attrs = attrs)
    }
    val roles = assignRoles(listed)
    return listed.mapIndexed { i, folder ->
        ImapFolder(
            name = folder.name,
            path = folder.path,
            role = roles[i].elected,
            delimiter = folder.delimiter,
            unelectedRole = roles[i].unelected,
        )
    }
}

/**
 * The mailbox paths an `LSUB` answer named, decoded EXACTLY as [parseListFolders] decodes a LIST
 * name — [decodeMailboxPath], faithful, nothing filtered — because the two sets are intersected by
 * path. Decode them differently by a single character and every non-ASCII folder falls out of the
 * intersection, is reported unsubscribed, and vanishes from the drawer in silence (#101, #174).
 *
 * The anti-spoofing filter of [ImapFolder.name] has no place here: these are identifiers, never
 * displayed, only compared.
 *
 * A path the LIST did not carry is kept: a subscription may outlive its mailbox (RFC 3501 §6.3.9),
 * and it is the intersection — not this parser — that ignores it. Lines that are not `LSUB`, and
 * `LSUB` lines with no mailbox name, are dropped.
 */
internal fun parseLsubPaths(untagged: List<List<Any?>>): Set<String> =
    untagged.mapNotNullTo(mutableSetOf()) { resp ->
        // * LSUB (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LSUB") return@mapNotNullTo null
        (resp.getOrNull(4) as? String)?.let { decodeMailboxPath(it) }
    }

/**
 * [folders] with each entry marked by whether [subscribed] named its path. Adds no folder and
 * removes none: a subscription to a mailbox that is not listed is ignored.
 *
 * ⛔ [subscribed] `null` — the server was not asked, or its answer never arrived — leaves every
 * folder subscribed, because the unknown may not hide anything.
 *
 * ⛔ And an EMPTY set is treated as that same unknown, deliberately. "The account is subscribed to
 * nothing" and "the server said nothing usable" are indistinguishable on the wire, one of them is
 * a legitimate state and the other is an accident, and the two possible mistakes are not worth the
 * same: reading an accident as "subscribed to nothing" hides EVERY folder — an empty drawer, with
 * the inbox gone — while reading a genuinely empty subscription as "unknown" shows a few folders
 * the user asked to hide. The second is the one this returns.
 */
internal fun withSubscriptions(folders: List<ImapFolder>, subscribed: Set<String>?): List<ImapFolder> {
    if (subscribed.isNullOrEmpty()) return folders
    return folders.map { it.copy(isSubscribed = it.path in subscribed) }
}

/** One selectable mailbox as the LIST described it, before its role is settled. Roles cannot be
 *  settled folder by folder ([assignRoles]), so parsing and role assignment are two passes. */
internal data class ListedFolder(
    val name: String,
    val path: String,
    val delimiter: String,
    val attrs: List<String>,
)

/**
 * The role of each folder of ONE listing, in the order they were listed.
 *
 * This takes the whole list because both rules below are properties of the list, not of a folder,
 * and neither can be decided by looking at one entry:
 *
 * 1. A role the SERVER STATED beats a role guessed from a name, wherever either sits in the
 *    response. Hence two passes rather than "first one wins": the guesser runs only once every
 *    stated role has been handed out. A user folder named "Archives" listed before the mailbox
 *    the server itself marked `\Archive` would otherwise take the role, and archiving would file
 *    mail where the server does not keep its archive.
 *
 *    The rule runs in BOTH directions: a folder that stated a recognised attribute never goes
 *    through the name table, even when the role it stated was already taken. Falling back on its
 *    name would let a second `\Trash` called "Archiv" come back as the ARCHIVE — the app would
 *    file archived mail into a folder the server purges. The server has spoken; where its word
 *    cannot be honoured the folder keeps no role rather than a contradicting guess.
 * 2. A role is carried ONCE. A second candidate is not ELECTED to it, INBOX included — INBOX is
 *    unique by definition (RFC 3501 §5.1), so a second folder whose leaf reads "Inbox" is a user
 *    folder. Two folders sharing a role means two Trashes in the drawer and a `roleMailboxId`
 *    that returns whichever the query hands back first: the delete lands in one of them and the
 *    user empties the other.
 *
 *    A folder that lost its claim stays an ORDINARY folder: listed, under its own name, with no
 *    trash icon, renameable, deletable, and never the destination of a delete. That is not a
 *    property of the marked role `:core:data` stores for it — it holds because the mark is ERASED
 *    at the crossing to the UI model (`MailboxMapper.toMailbox`, and dropped from `folderRoleMap`),
 *    so nothing above `:core:data` reads a ROLE on that folder. The one thing that does cross is
 *    the bare fact of the loss (`Mailbox.lostRoleClaim`), which keeps it LISTED when the account
 *    shows only its subscribed folders — being out of search and out of the drawer at once would
 *    leave it reachable from nowhere. What it does NOT
 *    stay is searchable when what it claimed was the trash or the junk. A mailbox the server
 *    itself marked `\Trash`, or one the name table reads as a trash, holds mail the user threw
 *    away — an account with a personal `Trash` and a shared `Shared/…/Trash` has two of them, and
 *    the loser used to come back in every search result and to be crawled into the local index,
 *    where a cache purge could not clear it. So the loss is REPORTED
 *    ([ImapFolder.unelectedRole]) rather than thrown away. This module states the fact and
 *    decides nothing: `:core:data` is what turns a lost `trash`/`junk` into a marked role
 *    (`~trash`, `~junk` — `MailRepository.UNELECTED_ROLE_MARK`) that its single
 *    `NOT_SEARCHED_ROLES` source excludes from search and from the index. No list of searched or
 *    unsearched roles exists here.
 */
internal fun assignRoles(listed: List<ListedFolder>): List<FolderRole> {
    val stated = listed.map { roleFromAttributes(it.attrs) }
    val roles = MutableList<String?>(listed.size) { null }
    val unelected = MutableList<String?>(listed.size) { null }
    val taken = mutableSetOf<String>()
    stated.forEachIndexed { i, role ->
        if (role != null && taken.add(role)) roles[i] = role
    }
    listed.forEachIndexed { i, folder ->
        if (stated[i] != null) {
            // It stated a recognised role and was not elected to it: the claim is what it stated,
            // and the name table stays out of it (rule 1).
            if (roles[i] == null) unelected[i] = stated[i]
            return@forEachIndexed
        }
        val guessed = roleFromName(folder.name) ?: return@forEachIndexed
        if (taken.add(guessed)) roles[i] = guessed else unelected[i] = guessed
    }
    return List(listed.size) { FolderRole(elected = roles[it], unelected = unelected[it]) }
}

/**
 * What [assignRoles] settled for one folder: the role it was ELECTED to, and the role it CLAIMED
 * and lost to another folder of the same listing. At most one of the two is non-null.
 */
internal data class FolderRole(val elected: String?, val unelected: String?)

/** The role a server STATED with an RFC 6154 SPECIAL-USE attribute — the only one it did not
 *  have to be guessed. */
internal fun roleFromAttributes(attrs: List<String>): String? =
    attrs.firstNotNullOfOrNull { attr ->
        when (attr.lowercase()) {
            "\\sent" -> "sent"
            "\\drafts" -> "drafts"
            "\\trash" -> "trash"
            "\\junk" -> "junk"
            "\\archive" -> "archive"
            "\\all" -> "all"
            else -> null
        }
    }

/**
 * The role GUESSED from a folder's name, for the servers that state nothing.
 *
 * The names below are the ones SERVERS carry — what Dovecot, Roundcube, Thunderbird and the
 * common webmails actually create in each language — never the app's own labels: the mailbox is
 * named once, on the server, and the phone's language has no say in reading it. The app speaks
 * nine languages and its detection used to know one, so a mailbox whose trash is called
 * "Papierkorb", "Kosz" or "Корзина" came back with no trash at all — and with no trash,
 * `MailRepository.deleteWouldDestroy` calls EVERY delete a destruction and the swipe erases mail
 * instead of moving it. That is the whole reason this table is not English-only.
 *
 * ACCEPTED, deliberately: a USER folder named exactly "Papierkorb" on a server that states
 * nothing takes the role, loses its Rename/Delete entries and drops out of search. That class of
 * false positive has always existed in English ("Archive", "Archives"), this widens it and does
 * not create it — and it destroys no mail, which the missing trash does. No heuristic (depth,
 * position, message count) is added to dodge it.
 *
 * [lowercase] with no argument is locale-independent by contract, unlike the `toLowerCase()` it
 * replaced: a phone set to Turkish maps 'I' to 'ı' and would stop recognising "INBOX".
 */
internal fun roleFromName(name: String): String? = when (name.lowercase()) {
    "inbox" -> "inbox"

    // Trash and Junk first: the role whose absence destroys, then the one whose absence pollutes
    // every search with spam.
    "trash", "deleted", "deleted items", "deleted messages", // en
    "papierkorb", "gelöschte objekte", "gelöschte elemente", // de
    "papelera", "elementos eliminados", // es
    "corbeille", "éléments supprimés", // fr
    "cestino", "posta eliminata", "elementi eliminati", // it
    "prullenbak", "prullenmand", "verwijderde items", // nl
    "kosz", "elementy usunięte", // pl
    "lixeira", "lixo", "itens excluídos", "itens eliminados", // pt
    "корзина", "удалённые", "удаленные", // ru
    -> "trash"

    "junk", "spam", "junk e-mail", "junk email", // en
    "junk-e-mail", // de
    "correo no deseado", "correo basura", // es
    "courrier indésirable", "indésirables", // fr
    "posta indesiderata", "indesiderata", // it
    "ongewenste e-mail", "ongewenst", // nl
    "wiadomości-śmieci", // pl
    "lixo eletrônico", "lixo eletrónico", // pt
    "спам", "нежелательная почта", // ru
    -> "junk"

    "sent", "sent mail", "sent items", "sent messages", // en
    "gesendet", "gesendete objekte", "gesendete elemente", // de
    "enviados", "elementos enviados", "correo enviado", // es + pt ("enviados")
    "envoyés", "éléments envoyés", "messages envoyés", // fr
    "inviata", "posta inviata", "elementi inviati", // it
    "verzonden", "verzonden items", // nl
    "wysłane", "elementy wysłane", // pl
    "itens enviados", // pt
    "отправленные", // ru
    -> "sent"

    "drafts", // en
    "entwürfe", // de
    "borradores", // es
    "brouillons", // fr
    "bozze", // it
    "concepten", // nl
    "kopie robocze", "wersje robocze", "robocze", // pl
    "rascunhos", // pt
    "черновики", // ru
    -> "drafts"

    "archive", "archives", // en + fr
    "archiv", // de
    "archivo", // es
    "archivio", // it
    "archief", // nl
    "archiwum", // pl
    "arquivo", // pt
    "архив", // ru
    -> "archive"

    else -> null
}
