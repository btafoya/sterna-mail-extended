package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as [OAuthWiringTest]: it
 * reads two files as text and proves nothing about what runs. `ConnectViewModel` is an
 * `AndroidViewModel` wired to the app container and `ConnectScreen` is a composable, so this module
 * (no Robolectric, no instrumented tests) can instantiate neither, and no test in this repo executes
 * the approval screen. The DECISION this wiring defers to — what a `login_hint` does to a
 * verification URL — is covered for real, by execution, in `OAuthClientTest` (`core/jmap`). What is
 * left to hold is WHO is handed the hint, who is handed `null`, and that the button actually goes
 * through the function at all — and reading the source is the only way to hold any of it.
 *
 * The rule: the hint is the address the user typed at the previous step, and it belongs to the
 * generic JMAP device flow ONLY (#55). The Microsoft paths — the app-scoped Outlook sign-in
 * mirrored into this screen, and the post-import sign-in — pass `null`, deliberately: sending the
 * address there was never asked for and is not this branch's to decide.
 *
 * ⚠ WHAT IT DOES NOT SAY. It does not prove the hint reaches a browser, that anything runs at the
 * tap, that `state.loginHint` holds anything at runtime, or that the approval page pre-fills
 * anything at all — the last one is a bench observation and nothing else. It does not read
 * `SettingsScreen.InlineDeviceApproval`, the other Microsoft device panel, which is out of scope
 * and passes no hint because it calls none of this. A green run here means "the five call sites
 * still read the way they were written", not "the pre-fill works".
 *
 * ⚠ HOW IT COMPARES. Each rule pins a WHOLE CALL, whitespace-normalised, compared for EQUALITY: a
 * `contains` on a fragment is blind to any mutation that LENGTHENS the line (a fifth argument, a
 * `?: null`, a wrapper around the hint), and that blindness has cost this repo real defects. The
 * arguments are pinned, not merely the name of the function called — passing `null` to
 * `AwaitingApproval` is exactly the defect, and it calls the same constructor as the fix.
 */
class LoginHintWiringTest {

    /**
     * The mutation this exists to kill: `connectOAuth` handing `null` (or the untrimmed `email`, or
     * `accountName`) as the hint. That is the defect itself — the address is right there in
     * `emailTrim`, the user typed it one screen ago, and the approval page asks for it again.
     */
    @Test fun `the generic device flow hands over the address the user typed`() {
        assertEquals(
            "connectOAuth must put emailTrim in AwaitingApproval as the fourth argument: it is " +
                "the address the user typed, and the whole of #55 is that the approval page asks " +
                "for it a second time. ⚠ The call is pinned WHOLE — a fifth argument, or a hint " +
                "wrapped in anything, is a different call and must be re-read here.",
            "ConnectState.AwaitingApproval(device.userCode, device.verificationUri, " +
                "device.verificationUriComplete, emailTrim)",
            callIn(CONNECT_VIEW_MODEL, "connectOAuth", "ConnectState.AwaitingApproval("),
        )
    }

    /**
     * The mutation this exists to kill: the Outlook mirror passing `p.userCode`'s address — i.e.
     * anything but `null`. The Microsoft device page is not in this branch's scope; a hint arriving
     * there is a behaviour change nobody asked for, on the one sign-in path a maintainer's real
     * account uses.
     */
    @Test fun `the Outlook mirror passes no hint`() {
        assertEquals(
            "observeOutlookSignIn must pass null as AwaitingApproval's fourth argument. The " +
                "Microsoft device flow is out of scope for #55 and OutlookProgress carries no " +
                "address anyway — anything else here is the hint leaking to a second provider.",
            "ConnectState.AwaitingApproval(p.userCode, p.verificationUri, " +
                "p.verificationUriComplete, null)",
            callIn(CONNECT_VIEW_MODEL, "observeOutlookSignIn", "ConnectState.AwaitingApproval("),
        )
    }

    /**
     * The mutation this exists to kill: `DeviceApprovalPanel` passing `null` instead of
     * `state.loginHint`. It compiles, every other test stays green, and the fix is silently gone —
     * the panel is the ONLY place the state's hint reaches the button.
     */
    @Test fun `the approval panel forwards the state's hint`() {
        assertEquals(
            "DeviceApprovalPanel must forward state.loginHint to DeviceApprovalContent. Passing " +
                "null (or the composable's own username field) here is #55 undone: the state is " +
                "where the hint travels precisely because this panel cannot tell the two " +
                "producers apart.",
            "DeviceApprovalContent(state.userCode, state.verificationUri, " +
                "state.verificationUriComplete, state.loginHint, onCancel)",
            callIn(CONNECT_SCREEN, "DeviceApprovalPanel", "DeviceApprovalContent("),
        )
    }

    /**
     * The mutation this exists to kill: the post-import panel passing `approval.loginHint` or a
     * field of its own. That path is Microsoft's after a K-9 import, it shares the composable with
     * the generic flow, and sharing the composable must not mean sharing the hint.
     */
    @Test fun `the imported-account sign-in passes no hint`() {
        assertEquals(
            "ImportAccountSignIn must pass null to DeviceApprovalContent: the post-import device " +
                "flow is Microsoft's, out of scope for #55, and it reuses this composable only " +
                "for the code + button body.",
            "DeviceApprovalContent(approval.userCode, approval.verificationUri, " +
                "approval.verificationUriComplete, null, onCancel = viewModel::cancelImportOAuth)",
            callIn(CONNECT_SCREEN, "ImportAccountSignIn", "DeviceApprovalContent("),
        )
    }

    /**
     * THE ONE LINE OF PRODUCTION THAT JOINS the pure function to the user, and the four rules above
     * do not read it: they stop at who hands the hint to `DeviceApprovalContent`, never at what its
     * button does with it. Two mutations passed with the whole suite green until this rule existed.
     *
     *  - `val target = approval` — the fix is simply gone and #55's symptom is back. The import on
     *    line 91 goes unused, and nothing in this repo objects: no ktlint, no detekt, no
     *    `allWarningsAsErrors`.
     *  - `withLoginHint(loginHint, approval)` — the ARGUMENTS SWAPPED, which compiles
     *    (`isNullOrBlank()` smart-casts the hint in the else branch). At runtime
     *    `"nina@example.com".toHttpUrlOrNull()` is null, the function gives its first argument
     *    back, `target` becomes the ADDRESS, `Uri.parse` has no scheme to launch,
     *    `ActivityNotFoundException` is swallowed by `runCatching{}.isSuccess`, and the latch
     *    records nothing because the action returned false. The "Open the sign-in page" button
     *    then does nothing, every tap — worse than the defect being fixed.
     *
     * ⚠ It still proves NOTHING about a browser opening: it is the same text-reading instrument as
     * the rest of this file. What it holds is that the call is written the way it was reviewed.
     */
    @Test fun `the approval button computes its target through the hint`() {
        assertEquals(
            "DeviceApprovalContent must build its target with withLoginHint(approval, loginHint) — " +
                "the URL first, the hint second. Dropping the call restores #55; swapping the two " +
                "arguments compiles, opens nothing at all, and no other test in this repo sees it.",
            "withLoginHint(approval, loginHint)",
            callIn(CONNECT_SCREEN, "DeviceApprovalContent", "withLoginHint("),
        )
    }

    // -- reading the files -------------------------------------------------------------------------

    /**
     * The first [marker] call inside function [function] of [file], as one normalised string:
     * whitespace runs collapsed to a single space and a trailing comma dropped, so wrapping the
     * arguments over three lines is not a failure while changing one of them is.
     */
    private fun callIn(file: File, function: String, marker: String): String {
        val body = functionBody(file, function)
        val start = body.indexOf(marker)
        check(start >= 0) {
            "$function() of ${file.name} no longer calls `$marker`: this lint would read nothing " +
                "and pass. Re-point the rule rather than let it go blind."
        }
        var depth = 0
        var end = -1
        for (i in (start + marker.length - 1) until body.length) {
            if (body[i] == '(') depth++
            if (body[i] == ')') depth--
            if (depth == 0) { end = i; break }
        }
        check(end > 0) { "unbalanced parentheses after `$marker` in $function() of ${file.name}" }
        return body.substring(start, end + 1)
            .replace(Regex("""\s+"""), " ")
            .replace("( ", "(")
            .replace(" )", ")")
            .replace(",)", ")")
    }

    /** The body of a function of [file], as text. Braces counted raw, as the other source lints do
     *  — sound only while the file's string literals carry no braces. */
    private fun functionBody(file: File, name: String): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ${file.name}: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private companion object {
        const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
        const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        private fun sourceFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )

        val CONNECT_VIEW_MODEL: File by lazy { sourceFile(VIEW_MODEL_PATH) }
        val CONNECT_SCREEN: File by lazy { sourceFile(SCREEN_PATH) }
    }
}
