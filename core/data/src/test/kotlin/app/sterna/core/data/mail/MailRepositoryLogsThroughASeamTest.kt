package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A SOURCE LINT. It reads `MailRepository.kt` and `DataFactory.kt` as text and states that the
 * repository's journal lines are written through the `logInfo` / `logWarn` seam, and that the
 * factory wires that seam back onto `android.util.Log` level for level.
 *
 * Why the seam exists: `android.util.Log` is a stub in a JVM unit test and answers every method
 * with `RuntimeException: Method i in android.util.Log not mocked`, measured by
 * [JvmBoundaryProbeTest]. One such call on a path under test takes the test down instead of the
 * path, and one of the sixteen sat inside `destroyAll`, on the `spared.isNotEmpty()` branch a
 * witness of the spared wave has to cross.
 *
 * Why the seam is two parameters and not one: `logWarn` cannot be an `error == null` branch of
 * `logInfo`, because three sites warn while carrying no throwable, and a reporter's logcat is read
 * filtered by level. The comment ratchet leaves `MailRepository.kt` and `DataFactory.kt` no room to
 * say that in place, so the decision and its cost live here, where the rules that hold it are.
 *
 * What this rule does NOT cover:
 * - Nothing here executes. It proves a call is written, never that a line reaches logcat, and
 *   never that the level or tag a device shows is the one read here.
 * - The position of a call in a control structure. A pinned line says nothing about the branch it
 *   sits on; [DestroyChecksTheFolderWiringTest] is what holds the spared line to its `if`.
 * - The other JVM blockers of these files. `android.util.Base64` constructs under the stub
 *   android.jar, so it stays where it is, deliberately. The crypto caches are a `MemoryLru`.
 * - The `Log` calls of `EmailDao.kt`, `ImapMailService.kt` and `AccountStore.kt`. Those files are
 *   reached by no test that builds a repository, and are out of this rule's scope.
 */
class MailRepositoryLogsThroughASeamTest {

    /** The code lines of [source] naming [needle], comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * One whole call of [REPOSITORY], found from a line [inside] it: the nearest line above that
     * opens a call, through the next line that is a bare `)`. A rule that pinned only the opener
     * would accept a call whose last argument was dropped a line lower, which is exactly how a
     * `Throwable` goes missing.
     */
    private fun callAt(inside: String): List<String> {
        val lines = codeLinesOf(REPOSITORY)
        val at = lines.indexOfFirst { inside in it }
        check(at >= 0) { "no code line of MailRepository contains '$inside' any more" }
        val from = (at downTo 0).firstOrNull { lines[it].endsWith("(") }
            ?: error("'$inside' no longer sits in a call spread over several lines")
        val length = lines.drop(from).indexOfFirst { it == ")" }
        check(length >= 0) { "the call around '$inside' no longer closes on a line of its own" }
        return lines.subList(from, from + length + 1)
    }

    /** [count] whole code lines of [source] starting at the one that is exactly [from]. */
    private fun block(source: String, from: String, count: Int): List<String> {
        val lines = codeLinesOf(source)
        val at = lines.indexOf(from)
        check(at >= 0) { "no code line of that file reads '$from' any more" }
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private fun codeLinesOf(source: String): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test fun `the repository names android util Log nowhere`() {
        // The rule matches the call and the import, not the qualified name: an import, aliased or
        // not, followed by a bare `Log.i(` is what a fully qualified needle reads as absence.
        assertEquals(
            "MailRepository, MailSender, MailDrafts, MailFolders and MailAccounts must not call android.util.Log: " +
                "the stub android.jar of a JVM unit " +
                "test throws 'not mocked' on every one of them, so a single call turns a test of " +
                "the path around it into a test of the logger. Route the line through logInfo / " +
                "logWarn, whose production binding lives in DataFactory.create.",
            emptyList<String>(),
            (codeLinesOf(REPOSITORY) + codeLinesOf(SENDER) + codeLinesOf(DRAFTS) + codeLinesOf(FOLDERS) + codeLinesOf(ACCOUNTS))
                .filter { LOG_CALL.containsMatchIn(it) || LOG_IMPORT.containsMatchIn(it) },
        )
    }

    @Test fun `the spared wave of a purge stays an info line of the sync journal`() {
        // The level of this one site, which the other rules of this file do not hold and
        // DestroyChecksTheFolderWiringTest does not either: it pins the tag and the message, never
        // the line that opens the call. Warned instead of logged, the guard of #122 would report a
        // wave it spared as a fault of the pass.
        assertEquals(
            "the line the #122 guard leaves behind is an INFO of the sync journal, and the whole " +
                "call is pinned so neither its level nor its tag can move without a rule saying so",
            listOf(
                "logInfo(",
                "\"MailSync\",",
                "\"destroy spared \${credentials.id}/\$expectedMailboxId: \${spared.size} of \" +",
                "\"\${emailIds.size} not in that folder alone: \${spared.joinToString()}\",",
                "null,",
                ")",
            ),
            callAt("destroy spared"),
        )
    }

    @Test fun `what carried a Throwable still hands it to the seam`() {
        // Five sites, named one by one rather than counted: a count is satisfied by five calls
        // that each dropped their cause and grew one somewhere else. The cause is the only thing
        // the bench cannot see, and #92 says an account that fails must not vanish without trace.
        assertEquals(
            "the skipped account of a unified walk keeps `gone`, the whole call pinned so the " +
                "argument cannot be dropped from a line no rule reads",
            listOf(
                "logInfo(",
                "\"MailRepository\",",
                "\"unified refresh: \${accountGoneCause(credentials.id, accountStore.accountsUnreadable(), " +
                    "passKind = \"walk\")}, skipped\",",
                "gone,",
                ")",
            ),
            callAt("unified refresh: \${accountGoneCause("),
        )
        assertEquals(
            "the account that failed a unified refresh keeps its throwable (#92)",
            listOf("logWarn(\"MailRepository\", \"unified refresh: account \${credentials.id} failed\", t)"),
            codeLinesNaming(REPOSITORY, "unified refresh: account "),
        )
        assertEquals(
            "the unreadable origin header of one message keeps its failure",
            listOf("logWarn(\"MailRepository\", \"origin header of \$emailId unreadable\", failure)"),
            codeLinesNaming(REPOSITORY, "origin header of "),
        )
        assertEquals(
            "the unreadable INTERNALDATE of an Autocrypt import keeps its failure",
            listOf(
                "logWarn(\"MailRepository\", \"INTERNALDATE unreadable; no Autocrypt key imported\", failure)",
            ),
            codeLinesNaming(REPOSITORY, "INTERNALDATE unreadable"),
        )
        assertEquals(
            "the unreadable origin headers of a prefetched batch keep their failure",
            listOf(
                "logWarn(\"MailRepository\", \"origin headers of \${emails.size} prefetched messages " +
                    "unreadable\", failure)",
            ),
            codeLinesNaming(REPOSITORY, "origin headers of "),
        )
    }

    @Test fun `a warning stays a warning, and the search line still flattens its own error`() {
        // The seam is two lambdas and not one because of these: folding a warning with no
        // throwable into the info lambda would drop the line a level, and a reporter's logcat is
        // read filtered. The search line flattens its error into text on purpose, so an account
        // dropped from a unified search costs one line rather than a stack.
        assertEquals(
            "the dropped account of a unified search is a WARNING, tagged MailSearch, and keeps " +
                "flattening its own error into the message rather than handing a throwable over",
            listOf(
                "logWarn(",
                "\"MailSearch\",",
                "\"account \${credentials.id} dropped from unified search: \" +",
                "\"\${error.javaClass.simpleName}: \${error.message}\",",
                "null,",
                ")",
            ),
            callAt("dropped from unified search"),
        )
        assertEquals(
            "an autodiscovery that resolved nothing stays a WARNING (#55)",
            listOf(
                "logWarn(\"MailRepository\", \"OAuth add: nothing autodiscovered for \$host; " +
                    "stored as-is (#55)\", null)",
            ),
            codeLinesNaming(ACCOUNTS, "nothing autodiscovered"),
        )
        assertEquals(
            "an outbox item that could not be armed stays a WARNING (#70)",
            listOf("logWarn(\"MailRepository\", \"outbox \$id: \$note\", null)"),
            codeLinesNaming(SENDER, "outbox \$id: "),
        )
    }

    @Test fun `the factory binds the seam back onto android util Log, level for level`() {
        assertEquals(
            "DataFactory.create is the one place that builds a MailRepository for the app, so it " +
                "is the one place that may name android.util.Log for it. These two lines are the " +
                "whole binding: an info that is not Log.i, or a warn that is not Log.w, silently " +
                "moves every line of the repository to another level in a reporter's logcat.",
            listOf(
                "if (error == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, error)",
                "if (error == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, error)",
            ),
            codeLinesNaming(FACTORY, "android.util.Log"),
        )
        assertEquals(
            "and each of the two must be bound to the parameter of its own level: the pair above " +
                "is satisfied by a logInfo lambda holding both calls and a logWarn that logs nothing",
            listOf(
                "logInfo = { tag, message, error ->",
                "if (error == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, error)",
                "},",
                "logWarn = { tag, message, error ->",
                "if (error == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, error)",
                "},",
            ),
            block(FACTORY, from = "logInfo = { tag, message, error ->", count = 6),
        )
    }

    @Test fun `the account half is built with the repository's own seam, scope and host`() {
        assertEquals(
            "MailRepository must hand MailAccounts its own logWarn, its own bgScope and itself as " +
                "host, every one of them named. Dropped, `logWarn` falls to the class's silent " +
                "default and the #55 warning above never reaches logcat; a second scope would " +
                "let a purge die with a sync call.",
            listOf(
                "val accounts: MailAccounts = MailAccounts(",
                "client = client,",
                "discoveryClient = discoveryClient,",
                "imap = imap,",
                "emailDao = emailDao,",
                "emailFtsDao = emailFtsDao,",
                "emailBodyDao = emailBodyDao,",
                "mailboxDao = mailboxDao,",
                "snoozedDao = snoozedDao,",
                "serverContactDao = serverContactDao,",
                "accountStore = accountStore,",
                "oauthClient = oauthClient,",
                "bgScope = bgScope,",
                "host = this,",
                "logWarn = logWarn,",
                "searchHistory = searchHistory,",
                ")",
            ),
            callAt("= MailAccounts("),
        )
    }

    private companion object {
        /** A call of the platform logger, however it was imported, and the imports that allow one. */
        val LOG_CALL = Regex("""\bLog\s*\.\s*[a-z]+\s*\(""")
        val LOG_IMPORT = Regex("""^import\s+android\.util\.(\*|Log)(\s|$)""")

        val REPOSITORY: String by lazy { DaoQuerySource.mailSource("MailRepository") }

        /** The send half of the repository, held to the same ban on the platform logger. */
        val SENDER: String by lazy { DaoQuerySource.mailSource("MailSender") }

        /** And the draft half, which a JVM test now enters as far as the wire. */
        val DRAFTS: String by lazy { DaoQuerySource.mailSource("MailDrafts") }

        /** And the folder half, entered by a JVM test on both protocols. */
        val FOLDERS: String by lazy { DaoQuerySource.mailSource("MailFolders") }

        /** And the account half, whose prune a JVM test runs against a store and a server. */
        val ACCOUNTS: String by lazy { DaoQuerySource.mailSource("MailAccounts") }

        val FACTORY: String by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/DataFactory.kt").readText()
        }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $path from ${File("").absolutePath}")
    }
}
