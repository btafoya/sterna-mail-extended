package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHERE THE FROZEN NUMBERING IS PLUGGED INTO THE READ — the source-text half of
 * [ReadUnderTheNumberingItWasTickedUnderTest], and the twin of [ImapMoveUnderNumberingTest] for the
 * gesture that READS rather than moves (#99, #189).
 *
 * THE HOLE THIS FILLS, and it let a whole delivery through. `MailRepository.moveToAccount` froze
 * the numbering of the tick and handed it to `rawSource`, `rawSource` handed it to
 * `fetchRawSource` — and `fetchRawSource` used it for ONE thing, skipping the cache, then called
 * `imap.fetchSource(credentials, mailboxId, uid)` with three arguments. The fourth parameter fell
 * back on its default `null`, `onMailbox` selected under `null ?: recorded`, i.e. the folder's own
 * record — the one a background pass realigned the moment it met the renumbering — and the FETCH
 * went out. Every pinned line in the repository stayed byte-identical, every executable test stayed
 * green, and the defect was WORSE than before the change: the read now missed the warm cache that
 * used to hand back the RIGHT message's octets, asked the server without opposing anything, and the
 * WRONG message's octets were appended on the other account. The read having succeeded,
 * `crossAccountMove` went on to `writeTarget`, and only the Trash refused — `CopiedNotRemoved`, a
 * permanent duplicate on B, with no Undo on that path.
 *
 * Nothing here could have caught it, because nothing here READ these two bodies: the only rule that
 * came close filtered the file for `rawSource(` / `rawSourceBytes(`, which neither
 * `imap.fetchSource(` nor `rawSourceCache.get(` answers to.
 *
 * SOURCE LINT, and the last resort as always: `MailRepository` needs Room, a `Context` and a
 * socket, so no JVM test in this module instantiates it. What it guards is EXECUTED next door —
 * [ReadUnderTheNumberingItWasTickedUnderTest] drives `ImapMailService.fetchSource` onto a scripted
 * socket and proves the argument is what the SELECT opposes, [RawSourceFromCacheTest] runs the
 * cache decision — and what only the text can say is that the value still travels from the
 * parameter to those two calls.
 *
 * Everything is pinned as WHOLE LINES compared as a LIST, never `in` / `contains`: a substring rule
 * is blind to anything appended to what it matched, and "one more argument" is exactly the shape of
 * the mutation that shipped.
 */
class RawSourceReadUnderNumberingTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [text] — comments dropped, whitespace normalised. Whole lines: the
     *  assertions compare them, never search inside them. */
    private fun codeLinesOf(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(text: String, needle: String): List<String> =
        codeLinesOf(text).filter { needle in it }

    private fun codeLinesNaming(text: String, needle: Regex): List<String> =
        codeLinesOf(text).filter { needle.containsMatchIn(it) }

    /**
     * Any mention of a numbering, under any spelling — the needle [ImapMoveUnderNumberingTest]
     * invented for exactly this family of mutations, borrowed verbatim.
     *
     * Why a synonym needle and not the string `expectedUidValidity`: the mutation that matters
     * most here changes NO pinned line at all. Insert
     *
     *     val expectedUidValidity: Long? = null
     *
     * at the head of either body and Kotlin's shadowing does the rest — the parameter still exists,
     * the calls below still read word for word as they do here, and the stamp is silently replaced
     * by nothing on the way to the wire. A closed list can only see such a line if the line MATCHES,
     * so the needle is stated as what the value IS, whatever it is called.
     */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded)")

    /**
     * `rawSource` is a pass-through: it resolves the [Email] per protocol and hands the fetch
     * everything it was given. It must MENTION a numbering exactly once — on the hand-down — and
     * read none of its own.
     */
    @Test fun `the public read hands the numbering it was given straight down`() {
        val body = bodyOf("rawSource")
        assertEquals(
            "rawSource must mention a numbering on exactly ONE line, the one that hands its " +
                "parameter to fetchRawSource — and under ANY name. A `val expectedUidValidity: " +
                "Long? = null` here shadows the parameter, changes not one other line of this " +
                "body, and quietly turns the move between accounts back into a read that opposes " +
                "the folder's own realigned record. Body was:\n$body",
            listOf("return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))"),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * THE THREE USES, AND THEY ARE ALL THE POINT. `fetchRawSource` must spend what it was
     * frozen three times: once to SKIP THE CACHE (a four-entry LRU keyed `accountId:emailId`, which
     * holds no numbering and would answer before any SELECT), once on the DECISION (`Frozen(null)`
     * — a tick that froze nothing at all — is refused here, before one byte is appended on the
     * target account), and once ON THE WIRE (the argument the SELECT opposes).
     *
     * "Before one byte is appended on the target account", not "before one octet is read": the
     * cache detour above reads a whole message when nothing is frozen, and the refusal is about
     * what gets WRITTEN on B, which is nothing.
     *
     * Either one alone is worse than neither:
     *  - the cache skipped and the FETCH unopposed — what shipped — turns the case the user
     *    actually hits ("I open the message, then I move it") from a correct copy into a WRONG one:
     *    the warm cache used to hand back the right message's octets, and now the server hands back
     *    whatever holds that uid today, unopposed;
     *  - the FETCH opposed and the cache consulted first — the read is short-circuited exactly when
     *    the message was just opened, the refusal never happens, and the copy lands on B while only
     *    the Trash refuses: a permanent duplicate, no Undo.
     */
    @Test fun `the fetch skips the cache AND goes out under the numbering it was handed`() {
        val body = bodyOf("fetchRawSource")
        assertEquals(
            "fetchRawSource must spend the frozen numbering TWICE and mention one nowhere else: " +
                "on the cache decision, and on the FETCH itself. Dropping it from the " +
                "`imap.fetchSource(` call still compiles — the parameter defaults to null — and " +
                "`onMailbox` then selects under `null ?: recorded`, comparing the folder's own " +
                "record with itself; the octets of whatever holds that uid now are what gets " +
                "appended on the other account. Body was:\n$body",
            listOf(
                "rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }",
                "val numbering = when (val verdict = numberingToOppose(frozen)) {",
                "NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)",
                "is NumberingToOppose.Select -> verdict.stamp",
                "imap.fetchSource(credentials, mailboxId, uid, numbering)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * The cache is READ through the decision and nowhere else — a closed list over the whole file,
     * anchored on the field rather than on one call shape.
     *
     * Anchored on `rawSourceCache`, not on `rawSourceFromCache(`: a rule pinned to the decision's
     * NAME is satisfied while a second, direct `rawSourceCache.get(…)` is added beside it, and the
     * first hit wins. The write and the eviction stay as they are and are named here so that they
     * cannot be mistaken for a leak: octets fetched under a numbering that was just confirmed are
     * as good as any, and dropping them would make every OpenPGP interaction retry refetch the
     * whole message.
     */
    @Test fun `the raw-source cache is read through the decision, and nowhere else`() {
        assertEquals(
            "the raw-source cache may be READ only through rawSourceFromCache, which is what " +
                "refuses to answer a caller carrying a frozen numbering. A direct " +
                "`rawSourceCache.get(…)` anywhere is that refusal short-circuited: the move " +
                "between accounts would then never reach the SELECT that opposes the number, and " +
                "the copy on B would be written before anything could refuse.",
            listOf(
                "private val rawSourceCache = android.util.LruCache<String, String>(4)",
                "rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }",
                "rawSourceCache.put(cryptoKey(credentials.id, emailId), raw)",
                "rawSourceCache.remove(cryptoKey(credentials.id, emailId))",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "rawSourceCache"),
        )
        // AND THE ANCHOR IS THE TYPE, NOT THE NAME — the hole the rule above still had. Every
        // line of it names the FIELD, so a SECOND `android.util.LruCache` under any other name,
        // read before the refusal, satisfies the whole list and hands back the octets of whatever
        // holds that uid now. Those octets then go to account B under an APPEND that is never
        // replayed, and only the bin refuses: a permanent duplicate, no Undo. So the file's caches
        // are pinned whole, as a closed list of two.
        assertEquals(
            "the in-memory caches of MailRepository, whole and in order. A third one is a reader " +
                "nobody has decided the numbering of — and if it holds message source, it answers " +
                "in front of the refusal that this volet exists to reach.",
            listOf(
                "private val decryptedCache = android.util.LruCache<String, DecryptedEntry>(8)",
                "private val rawSourceCache = android.util.LruCache<String, String>(4)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "android.util.LruCache"),
        )
    }

    /**
     * And the three whole-message FETCHes of the file, pinned together: ONE carries a frozen
     * numbering, the two others carry none and must not grow one.
     *
     * Not a licence to add the stamp everywhere. The other two read on paths that froze nothing
     * — the id comes off the cache and the fetch goes out in the same call — and handing them a
     * value they do not have would be handing them `null`, which changes nothing, or a re-read,
     * which is the defect. What this list is for is the reverse: a FOURTH whole-message read added
     * later, unopposed, on a path that did freeze something.
     */
    @Test fun `exactly one whole-message fetch carries a frozen numbering`() {
        assertEquals(
            "the file's whole-message FETCHes, whole and in order. The one inside fetchRawSource " +
                "carries the numbering its caller froze; the two others carry none because their " +
                "callers froze none. A new one on this list is a read nobody has decided the " +
                "numbering of.",
            listOf(
                "val raw = imap.fetchSource(credentials, mailboxId, uid)",
                "imap.fetchSource(credentials, mailboxId, uid, numbering)",
                "val raw = imap.fetchSource(credentials, cached.mailboxId, uid)",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "imap.fetchSource("),
        )
    }
}
