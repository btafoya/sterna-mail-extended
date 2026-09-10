package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.settings.DeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #190: delivery is an account's setting, so the decision runs once per account. Executed,
 * not read: what matters is which arguments each account is decided on, and the arms' order.
 * The plug at the bottom is the one thing that cannot be executed here.
 */
class PerAccountDeliveryTest {

    private fun account(
        id: String,
        mode: DeliveryMode?,
        protocol: MailProtocol = MailProtocol.JMAP,
        isLinked: Boolean = false,
        unifiedPushActive: Boolean = false,
    ) = AccountDelivery(
        id = id,
        isLinked = isLinked,
        unifiedPushActive = unifiedPushActive,
        protocol = protocol,
        deliveryMode = mode,
    )

    // -- the arms, in the order the incidents behind them were closed in --------------------------

    @Test fun `an account on battery saver is left to the periodic worker`() {
        assertEquals(
            Transport.PERIODIC,
            transportOf(account("a", DeliveryMode.BATTERY_SAVER), DeliveryMode.INSTANT),
        )
        assertEquals(
            Transport.EVENT_SOURCE,
            transportOf(account("a", DeliveryMode.INSTANT), DeliveryMode.BATTERY_SAVER),
        )
        assertEquals(
            Transport.IMAP_IDLE,
            transportOf(account("a", DeliveryMode.INSTANT, protocol = MailProtocol.IMAP), DeliveryMode.INSTANT),
        )
    }

    @Test fun `an account that never chose is decided on the global setting`() {
        assertEquals(
            "an account with no choice of its own must be decided on the global setting, or the " +
                "update moves every battery-saver install back onto a live connection.",
            Transport.PERIODIC,
            transportOf(account("a", null), DeliveryMode.BATTERY_SAVER),
        )
        assertEquals(
            Transport.EVENT_SOURCE,
            transportOf(account("a", null), DeliveryMode.INSTANT),
        )
    }

    @Test fun `a linked sub-account is periodic whatever it chose`() {
        // It holds no push of its own (#31), so this arm wins over every other one.
        assertEquals(
            Transport.PERIODIC,
            transportOf(
                account("a", DeliveryMode.INSTANT, isLinked = true, unifiedPushActive = true),
                DeliveryMode.INSTANT,
            ),
        )
    }

    @Test fun `an armed UnifiedPush endpoint wins over battery saver`() {
        // UnifiedPush costs Sterna no connection, so battery saver must not knock it down to a
        // 30-minute poll.
        assertEquals(
            Transport.UNIFIED_PUSH,
            transportOf(
                account("a", DeliveryMode.BATTERY_SAVER, unifiedPushActive = true),
                DeliveryMode.INSTANT,
            ),
        )
    }

    // -- the invariant the whole issue turns on --------------------------------------------------

    @Test fun `one account on instant delivery keeps the service up for the others`() {
        val direct = directAccountIds(
            listOf(
                account("instant", DeliveryMode.INSTANT),
                account("saver", DeliveryMode.BATTERY_SAVER),
            ),
            DeliveryMode.INSTANT,
        )
        assertEquals(
            "with one account on instant delivery the direct list must not be empty: an empty " +
                "list stops the foreground service and takes its permanent notification down, " +
                "for an account whose mail is supposed to keep arriving as it lands.",
            listOf("instant"),
            direct,
        )
    }

    @Test fun `the global setting only decides the accounts that never chose`() {
        val direct = directAccountIds(
            listOf(
                account("chose-instant", DeliveryMode.INSTANT),
                account("never-chose", null),
            ),
            DeliveryMode.BATTERY_SAVER,
        )
        assertEquals(
            "under a global battery saver, the account that explicitly asked for instant delivery " +
                "is the only direct one — and it is direct.",
            listOf("chose-instant"),
            direct,
        )
    }

    @Test fun `every account on battery saver leaves nothing direct`() {
        assertEquals(
            emptyList<String>(),
            directAccountIds(
                listOf(
                    account("a", DeliveryMode.BATTERY_SAVER),
                    account("b", null),
                ),
                DeliveryMode.BATTERY_SAVER,
            ),
        )
    }

    // -- the plug: writing the setting must re-arm push ------------------------------------------

    /**
     * Source lint, and the only rule here that is not executed: `AccountsViewModel` needs an
     * `Application`. Without the re-arm the stored value is inert until the next cold start: the
     * connection stays open, `shouldPollInbox` keeps seeing it, and the account is skipped by the
     * fallback cycle it was just moved onto.
     */
    @Test fun `the account's delivery mode is written and push re-armed on the spot`() {
        assertEquals(
            "AccountsViewModel.setDeliveryMode must be exactly these three statements, in this " +
                "order: the store write, then refresh(), then the re-arm with " +
                "userInitiated = true. Dropping the re-arm leaves the choice with no effect at " +
                "all until the app is restarted, and nothing on screen says so. Body read:\n" +
                setDeliveryModeBody().joinToString("\n"),
            listOf(
                "store.setDeliveryMode(id, mode)",
                "refresh()",
                "PushController.apply(getApplication(), userInitiated = true)",
            ),
            setDeliveryModeBody(),
        )
    }

    @Test fun `the mode shown for an account is the resolved one, read through one place`() {
        assertEquals(
            "AccountsViewModel.deliveryMode must hand back the RESOLVED mode, through " +
                "PushController.deliveryModeFor. Reading the stored field here would draw 'Instant' " +
                "for an account that has never chosen and is really running under a global battery " +
                "saver, and a second '?: global' of its own would be a copy of the rule.",
            listOf(
                "fun deliveryMode(id: String): DeliveryMode =",
                "PushController.deliveryModeFor(getApplication(), id)",
            ),
            codeLines(ACCOUNTS_VIEW_MODEL)
                .dropWhile { !it.startsWith("fun deliveryMode(id: String)") }
                .take(2),
        )
    }

    @Test fun `the service arm asks the transport too, not only the notification switch`() {
        assertEquals(
            "PushService.reconnectAll must filter on the transport as well. It is the fifth site " +
                "and the one with no symptom of its own: apply() decides whether the service runs " +
                "at all, so while delivery was one global setting an emptied direct list stopped " +
                "the service and this filter was never reached with a battery-saver account in " +
                "hand. Per account, the service runs for the instant ones and this arm would open " +
                "a connection for the battery-saver ones anyway — the setting would save nothing " +
                "and nothing would say so. The transport clause is the membership test, and the " +
                "two clauses before it keep their place: drop 'it.id in direct' and every " +
                "battery-saver account gets a connection back. Lines found:\n" +
                reconnectAllFilter().joinToString("\n"),
            listOf(
                "store.notificationsEnabled(it.id) && !up.isActive(it.id) &&",
                "it.id in direct",
            ),
            reconnectAllFilter(),
        )
        assertEquals(
            "and 'direct' must be that set, decided ONCE for the whole watched list by " +
                "PushController — the whole line. Per account, the transport read costs a " +
                "runBlocking on the settings DataStore plus two decodes of the account blob, on " +
                "the arm that runs on every account switch and every reconnect; and handing it " +
                "anything other than 'watched' decides the filter on a list the filter is not " +
                "about. There is exactly one such call in the file: a second one is a second " +
                "answer nobody keeps in step. Lines found:\n" +
                codeLines(PUSH_SERVICE).filter { "directAccountIdsFor" in it }.joinToString("\n"),
            listOf("val direct = PushController.directAccountIdsFor(application, watched)"),
            codeLines(PUSH_SERVICE).filter { "directAccountIdsFor" in it },
        )
    }

    @Test fun `the candidate count in the empty-arm line is still taken before the filter`() {
        assertEquals(
            "the 'nothing to watch' line counts the accounts CONSIDERED, so it must keep being " +
                "handed 'watched.size' and not the filtered list: with a transport filter in " +
                "front of it, counting after the filter would print '0 of 0' and hide the very " +
                "accounts whose transport turned them down.",
            listOf("Log.i(TAG, PushController.nothingToWatchLine(watched.size))"),
            codeLines(PUSH_SERVICE).filter { "nothingToWatchLine" in it },
        )
    }

    /** The lines of `reconnectAll`'s account filter, between its opener and its closing brace. */
    private fun reconnectAllFilter(): List<String> {
        val lines = codeLines(PUSH_SERVICE)
        val opener = "val accounts = watched.filter {"
        val at = lines.indexOfFirst { it == opener }
        assertTrue(
            "no '$opener' in PushService.kt — the arm's account filter was renamed or reshaped, " +
                "and this lint must be taught the new shape rather than left green over code it " +
                "never read. Filtering on one line again is enough to hide the transport clause " +
                "from this rule, so the shape is part of what is pinned.",
            at >= 0,
        )
        return lines.drop(at + 1).takeWhile { it != "}" }
    }

    /** The body of `fun setDeliveryMode(...)` in AccountsViewModel, closed by counting braces. */
    private fun setDeliveryModeBody(): List<String> {
        val lines = codeLines(ACCOUNTS_VIEW_MODEL)
        val opener = "fun setDeliveryMode(id: String, mode: DeliveryMode) {"
        val at = lines.indexOfFirst { it == opener }
        assertTrue(
            "no '$opener' in AccountsViewModel.kt — the writer was renamed or reshaped, and this " +
                "lint must be taught the new shape rather than left green over code it never read",
            at >= 0,
        )
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("setDeliveryMode is never closed in AccountsViewModel.kt")
    }

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        const val ACCOUNTS_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"
        const val PUSH_SERVICE_PATH = "app/src/main/kotlin/app/sterna/push/PushService.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ACCOUNTS_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate $ACCOUNTS_VIEW_MODEL_PATH from ${File("").absolutePath} — the " +
                        "rules below read the sources as text and need a working directory inside " +
                        "the checkout",
                )
        }

        val ACCOUNTS_VIEW_MODEL: File by lazy { File(root, ACCOUNTS_VIEW_MODEL_PATH) }
        val PUSH_SERVICE: File by lazy { File(root, PUSH_SERVICE_PATH) }
    }
}
