package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.MailNotificationText.WidgetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one row of the latest-mail home-screen widget is allowed to say. A widget is drawn by
 * the launcher, outside the app's window, in front of whoever is holding the phone — so the row
 * answers to the notification-content setting the reader has already set, and to the app lock,
 * and never to anything of its own.
 *
 * Every expectation here is a literal. A test that recomputes the rule to decide what to expect
 * agrees with any rule at all, the inverted one included.
 */
class MailNotificationWidgetRowTest {

    private fun row(content: NotificationContent, appLockEnabled: Boolean = false) =
        MailNotificationText.widgetRow(content, appLockEnabled, SENDER, SUBJECT, GENERIC)

    // -- the four positions, pinned ---------------------------------------------------------------

    @Test fun `what a widget row may say, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to WidgetRow("Jordan Lee", "Blood test results", namesSender = true),
            NotificationContent.SENDER_AND_SUBJECT to
                WidgetRow("Jordan Lee", "Blood test results", namesSender = true),
            NotificationContent.SENDER_ONLY to WidgetRow("Jordan Lee", "New message", namesSender = true),
            NotificationContent.NONE to WidgetRow("New message", null, namesSender = false),
        )
        assertEquals(
            "a position was added to the setting without deciding what a widget row may say there",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(expected, NotificationContent.entries.associateWith { row(it) })
    }

    @Test fun `body preview shows the sender and the subject, and no body`() {
        val row = row(NotificationContent.BODY_PREVIEW)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("Blood test results", row.secondary)
    }

    @Test fun `sender and subject shows both`() {
        val row = row(NotificationContent.SENDER_AND_SUBJECT)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("Blood test results", row.secondary)
    }

    @Test fun `sender only keeps the name and puts the stand-in under it`() {
        val row = row(NotificationContent.SENDER_ONLY)
        assertEquals("Jordan Lee", row.primary)
        assertEquals("New message", row.secondary)
    }

    @Test fun `neither shows the stand-in alone, with no second line at all`() {
        val row = row(NotificationContent.NONE)
        assertEquals("New message", row.primary)
        assertNull(row.secondary)
    }

    /**
     * The two talkative positions render the SAME row, deliberately. The preview column is
     * populated as a matter of course on JMAP, while on IMAP it is only pre-fetched when
     * BODY_PREVIEW was asked for (NotificationPreviews.gather returns nothing at the other three).
     * Drawing a body here would therefore make the widget more talkative on one protocol than on
     * the other — on top of putting the body of a message on a home screen, which no position of
     * this setting has ever asked for. Do not "fix" the duplication.
     */
    @Test fun `body preview and sender and subject render the same row`() {
        assertEquals(row(NotificationContent.SENDER_AND_SUBJECT), row(NotificationContent.BODY_PREVIEW))
    }

    /**
     * A widget row never says more than the collapsed notification for the same mail: the pair
     * (primary, secondary) is the pair (title, text) of [MailNotificationText.resolve].
     */
    @Test fun `a widget row says exactly what the collapsed notification says`() {
        NotificationContent.entries.forEach { content ->
            val reveal = MailNotificationText.resolve(content, SENDER, SUBJECT, GENERIC, PREVIEW)
            val row = row(content)
            assertEquals("the primary line drifted from the notification title at $content", reveal.title, row.primary)
            assertEquals("the secondary line drifted from the collapsed line at $content", reveal.text, row.secondary)
        }
    }

    // -- the app lock ------------------------------------------------------------------------------

    /**
     * Read on the lock being ENABLED (AccountStore.appLockEnabled()), not on AppLock.locked, which
     * is a process-scoped StateFlow that dies with the process: the widget would be talkative while
     * the app is open and mute afterwards, with nothing on screen explaining the switch. The
     * promise in SECURITY.md is about someone who picks up an unlocked phone, and that is exactly
     * who reads a home screen.
     */
    @Test fun `the app lock renders what NONE renders, at every position`() {
        NotificationContent.entries.forEach { content ->
            assertEquals(
                "the lock did not fold $content onto NONE",
                WidgetRow("New message", null, namesSender = false),
                row(content, appLockEnabled = true),
            )
        }
    }

    @Test fun `the app lock changes nothing about NONE itself`() {
        assertEquals(row(NotificationContent.NONE), row(NotificationContent.NONE, appLockEnabled = true))
    }

    // -- does the row NAME anybody ------------------------------------------------------------------

    /**
     * THE THIRD FIELD, AND WHY IT IS NOT A CONVENIENCE. A monogram badge is the first letter of a
     * sender's NAME: drawing one beside a row is naming that sender, one letter at a time. Only this
     * function knows the EFFECTIVE position — the one the app lock has already folded onto NONE —
     * so only this function can say whether the widget has the right to a badge at all.
     *
     * Pinned as literals, one per position, and never recomputed from the setting: a test that
     * derived this from `content != NONE` would stay green with the shipped arms swapped.
     */
    @Test fun `whether a row names its sender, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to true,
            NotificationContent.SENDER_AND_SUBJECT to true,
            NotificationContent.SENDER_ONLY to true,
            NotificationContent.NONE to false,
        )
        assertEquals(
            "a position was added to the setting without deciding whether a widget row NAMES its " +
                "sender there. The answer governs the monogram badge, which is a letter of " +
                "somebody's name — defaulting it to true puts an initial on a home screen that " +
                "was asked to identify nobody.",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(expected, NotificationContent.entries.associateWith { row(it).namesSender })
    }

    /**
     * AND THE LOCK TAKES IT AWAY FROM THE MOST TALKATIVE POSITION. SECURITY.md promises a locked
     * home screen "names neither sender nor subject"; an initial is a letter of the sender's name,
     * so a badge drawn under the lock breaks that promise in the smallest possible type.
     */
    @Test fun `under the lock the loudest position names nobody`() {
        assertEquals(
            "with the app lock enabled, BODY_PREVIEW must report that the row names NOBODY — the " +
                "badge is drawn from that answer, and an initial on a locked home screen is a " +
                "letter of the sender's name sitting where SECURITY.md promises no name at all.",
            false,
            row(NotificationContent.BODY_PREVIEW, appLockEnabled = true).namesSender,
        )
    }

    @Test fun `the lock takes the name away at every position`() {
        assertEquals(
            "the lock folds every position onto NONE, and NONE names nobody.",
            NotificationContent.entries.associateWith { false },
            NotificationContent.entries.associateWith { row(it, appLockEnabled = true).namesSender },
        )
    }

    // -- nothing identifying escapes a position that hides it --------------------------------------

    @Test fun `sender only lets the subject through neither line`() {
        val row = row(NotificationContent.SENDER_ONLY)
        assertTrue("the subject reached the primary line: ${row.primary}", "Blood test results" !in row.primary)
        assertTrue(
            "the subject reached the secondary line: ${row.secondary}",
            "Blood test results" !in (row.secondary ?: ""),
        )
    }

    @Test fun `neither lets the sender or the subject through either line`() {
        val row = row(NotificationContent.NONE)
        assertTrue("the sender reached the primary line: ${row.primary}", "Jordan Lee" !in row.primary)
        assertTrue("the subject reached the primary line: ${row.primary}", "Blood test results" !in row.primary)
        assertTrue("the sender reached the secondary line: ${row.secondary}", "Jordan Lee" !in (row.secondary ?: ""))
        assertTrue(
            "the subject reached the secondary line: ${row.secondary}",
            "Blood test results" !in (row.secondary ?: ""),
        )
    }

    @Test fun `under the lock, neither the sender nor the subject reaches either line, at any position`() {
        NotificationContent.entries.forEach { content ->
            val row = row(content, appLockEnabled = true)
            assertTrue("the sender survived the lock at $content: ${row.primary}", "Jordan Lee" !in row.primary)
            assertTrue(
                "the subject survived the lock at $content: ${row.primary}",
                "Blood test results" !in row.primary,
            )
            val secondary = row.secondary ?: ""
            assertTrue("the sender survived the lock at $content: $secondary", "Jordan Lee" !in secondary)
            assertTrue("the subject survived the lock at $content: $secondary", "Blood test results" !in secondary)
        }
    }

    // -- the signature itself, because prose was guarding it --------------------------------------

    /**
     * THE ABSENCE OF A `preview` PARAMETER IS THE GUARD, and until this test it was written down
     * and nothing else. No test can pass an argument that does not exist, so every assertion above
     * stays green the moment someone adds one: `resolve` sits one line away and already knows how
     * to append a shaped body to the subject, and the reviewer who pointed this out wrote the
     * three-line change that would put a message's body on a home screen with the whole suite green.
     *
     * So the signature is read back and compared whole. Java reflection rather than the source
     * text: a lint on the source would be blind to a parameter added on a line of its own, and this
     * runs what shipped.
     *
     * A DEFAULT VALUE is pinned by the same assertion, from the other end. Kotlin compiles default
     * arguments into a second, synthetic `widgetRow$default` method, so `appLockEnabled: Boolean =
     * false` — which reads as harmless and makes the lock fail OPEN for any caller who forgets it —
     * shows up here as an extra overload and fails.
     */
    @Test fun `the widget rule takes five arguments and no preview, and none of them has a default`() {
        val overloads = MailNotificationText::class.java.methods
            .filter { it.name.startsWith("widgetRow") }
            .map { m -> m.name + m.parameterTypes.joinToString(", ", "(", ")") { it.simpleName } }
            .sorted()
        assertEquals(
            "widgetRow must take exactly (content, appLockEnabled, sender, subject, generic) and " +
                "nothing else. A sixth parameter — a preview above all, but a body under any name — " +
                "is a body reaching a home screen, which no position of this setting ever asked " +
                "for; and a default value on any of the five compiles to a widgetRow$DOLLAR" +
                "default overload, which is the app lock failing open for a caller who omits it.",
            listOf("widgetRow(NotificationContent, boolean, String, String, String)"),
            overloads,
        )
    }

    private companion object {
        const val DOLLAR = "$"
        const val SENDER = "Jordan Lee"
        const val SUBJECT = "Blood test results"
        const val GENERIC = "New message"
        const val PREVIEW = "Your results are in, please call the surgery"
    }
}
