package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [SubscribedFoldersSurfaceWiringTest],
 * pointed at the ONE thing a behaviour test cannot see: **who reads
 * `Mailbox.lostRoleClaim`, across every module.**
 *
 * The rule itself is EXECUTED next door ([VisibleFoldersTest], `LostRoleClaimTest`,
 * `UnelectedRoleStopsAtTheDataBoundaryTest`, `MailboxLostRoleClaimDecodeTest`). What none of them
 * can catch is a SECOND reader appearing somewhere else, and that is the whole danger of this
 * field: it is the surviving trace of a role claim, on a folder whose role was erased on purpose.
 *
 * The three mutations it exists for, all of which keep every other test in this repo green:
 *
 *  1. **it is read as a role.** A losing `Spam` is not the junk folder — the junk election already
 *    picked another one, and every role→mailbox lookup in `:core:data` compares roles by exact
 *    equality (`MailboxDao`). Anything resolving a destination, an icon, a snooze rule or a delete
 *    target through this boolean makes a folder that lost its claim the answer to "where is the
 *    junk", and mail would be MOVED there. `visibleFolders` is the only legitimate reader, and the
 *    question it asks is visibility;
 *  2. **it stops being written.** The mapper filling it from something other than the stored role
 *    (a literal `false`, `isSubscribed`, the unmarked role) silently reopens the defect: the losing
 *    folder is dropped from the drawer, both move pickers and the unread view, while it is already
 *    out of search — unreachable from the app, with its mail still on the server;
 *  3. **the mark is erased one line too early**, on the cache row that `MailRepository`
 *    hands to the crossing. Same damage as 2, from a file neither half of the fix touches, and
 *    nothing executes that producer.
 *
 * ⚠ Whole trimmed lines, by equality, screening EVERY code line of EVERY module's main sources that
 * names the field — never `contains` on a single line, which is blind to any mutation that
 * LENGTHENS it, and never a subset, which is blind to a new reader appearing.
 */
class LostRoleClaimBordersTest {

    @Test fun `the field is named in four places and no others`() {
        assertEquals(
            "a source line naming Mailbox.lostRoleClaim appeared, moved or changed shape. This " +
                "field is the surviving trace of a role a folder CLAIMED AND LOST: it is declared " +
                "once, filled once from the stored role, and read once — by visibleFolders, which " +
                "asks a visibility question. A reader anywhere else is either a routing decision " +
                "taken on a folder that is the junk of nothing, or a second copy of the reading.",
            listOf(
                "app/src/main/kotlin/app/sterna/ui/inbox/SubscribedFolders.kt: " +
                    "isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim",
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt: " +
                    "internal fun isLostRoleClaim(role: String?): Boolean =",
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailboxMapper.kt: " +
                    "lostRoleClaim = isLostRoleClaim(role),",
                "core/jmap/src/main/kotlin/app/sterna/core/jmap/model/Mailbox.kt: " +
                    "val lostRoleClaim: Boolean = false,",
            ),
            mentions("lostroleclaim"),
        )
    }

    /**
     * ⭐ The guard itself, whole lines. The sweep above sees the ONE line that names the field; this
     * sees what it sits in — five survivor families, OR-ed, on the mailbox as it arrives.
     *
     * Two mutations only this stands between: `&&` for one of the `||`, which makes a lost claim a
     * condition ON TOP of subscription instead of a reprieve from it (the defect, unchanged, with
     * the box ticked); and `mailbox.role != null` widened to the raw stored role, which is the ⋮
     * menu regression `UnelectedRoleStopsAtTheDataBoundaryTest` was written to close.
     */
    @Test fun `the survivor test is the five families, OR-ed, and nothing else`() {
        assertEquals(
            "visibleFolders' survivor test changed shape.",
            listOf(
                "val survives = mailbox.isSubscribed || mailbox.role != null ||",
                "isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim",
                "if (!survives) return@forEach",
            ),
            block(SUBSCRIBED_FOLDERS, "val survives =", 3),
        )
    }

    /** ⛔ The reading of the mark has ONE caller, which is what its purity is there to buy:
     *  a second call site is a second answer to give the day they disagree. ⚠ The declaration is
     *  screened out by WHOLE-LINE equality, never by `contains`, which would also swallow a
     *  one-line `internal fun` that happens to call it. */
    @Test fun `the reading of the mark is called exactly once in the whole app`() {
        val declaration = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt: " +
            "internal fun isLostRoleClaim(role: String?): Boolean ="
        assertEquals(
            "isLostRoleClaim gained or lost a call site. It answers for the crossing to the UI " +
                "model and for nothing else; inside :core:data the mark is read by the search " +
                "filters, on the STORED role, and that must not be routed through here.",
            listOf(
                "core/data/src/main/kotlin/app/sterna/core/data/mail/MailboxMapper.kt: " +
                    "lostRoleClaim = isLostRoleClaim(role),",
            ),
            mentions("islostroleclaim(").filterNot { it == declaration },
        )
    }

    /**
     * ⭐ THE SEAM, and the one mutation everything else here misses. The two halves of this fix are
     * pinned apart — the reading of the mark in `:core:data`, the survivor rule in `:app` — and
     * NOTHING executes what joins them: `MailRepository.observeMailboxes` is the only producer of
     * the [app.sterna.core.jmap.model.Mailbox] list every screen sees, and no JVM test runs it (it
     * needs a DAO). Erase the mark on the ENTITY one line earlier — `it.copy(role =
     * unmarkedRole(it.role)).toMailbox()`, a plausible tidy-up — and `isLostRoleClaim` is handed a
     * null on EVERY IMAP account, `lostRoleClaim` is false everywhere, the defect is back whole,
     * and every other test in this repo stays green because all four shipped lines are untouched.
     *
     * So the crossing is pinned where it is called, by whole lines: the entity reaches
     * `toMailbox()` as the cache stores it, mark and all.
     */
    @Test fun `the cache row reaches the crossing with its role untouched`() {
        assertEquals(
            "MailRepository.observeMailboxes changed shape. It is the ONE producer of the mailbox " +
                "list the screens see, and nothing executes it: a row whose role is unmarked " +
                "BEFORE toMailbox() carries no lost claim, and a folder that lost its election " +
                "leaves the drawer, both move pickers and the unread view while it is already out " +
                "of search — reachable from nowhere, its mail still on the server.",
            listOf(
                "mailboxDao.observeAll(accountId).map { rows -> rows.map { it.toMailbox() } }",
                "rows.map { it.toMailbox().copy(unreadForList = unread[it.id] ?: 0) }",
            ),
            codeLines(MAIL_REPOSITORY).filter { "it.toMailbox()" in it },
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** Every main-source code line of every module containing [needle], case-insensitively, as
     *  "<repo-relative path>: <trimmed line>", sorted so the answer does not depend on walk order. */
    private fun mentions(needle: String): List<String> = MAIN_SOURCES
        .flatMap { file ->
            val path = file.absolutePath.removePrefix(root.absolutePath + "/")
            codeLines(file).filter { needle in it.lowercase() }.map { "$path: $it" }
        }
        .sorted()

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$prefix` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SUBSCRIBED_FOLDERS_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/SubscribedFolders.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SUBSCRIBED_FOLDERS_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SUBSCRIBED_FOLDERS: File by lazy { File(root, SUBSCRIBED_FOLDERS_PATH) }

        val MAIL_REPOSITORY: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }

        /** ⛔ EVERY module, not just `:app`: the field is declared in `:core:jmap`, filled in
         *  `:core:data` and read in `:app`, so a sweep of one module proves nothing about it. */
        val MAIN_SOURCES: List<File> by lazy {
            listOf(
                "app/src/main/kotlin",
                "core/jmap/src/main/kotlin",
                "core/imap/src/main/kotlin",
                "core/data/src/main/kotlin",
                "libs/openpgp-api/src/main",
                // ⛔ The test-app and bench source sets too: they are compiled into a real APK,
                // and "one reader" has to mean one reader there as well.
                "app/src/testApp",
                "app/src/benchShared",
            ).map { File(root, it) }.filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        }
    }
}
