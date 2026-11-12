package app.sterna

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Switching the system between 12 and 24 hours is not a configuration change: no screen recomposes
 * on its own, and a cached time keeps its old form next to fresh ones. `MainActivity` rereads the
 * setting into `LocalIs24Hour`, and each surface that forms a time reads that local. No JVM test can
 * run a composable here, so these pin the call sites.
 *
 * `ACTION_TIME_CHANGED` is what Settings sends on that switch, and the only signal that reaches an
 * app left resumed beside it in split screen. Recreating the activity instead was ruled out: it
 * drops the pending notification, share and mailto orders.
 *
 * Holes that apply: 1 (`is24Hour` passed is not proven to be the local's value, beyond the pinned
 * `val`), 3 (a pinned line inside `onStart`/`onStop` is not proven to stay there; only `onResume` is
 * pinned by position), 4 (nothing proves a recomposition actually reformats the text).
 */
class ClockSettingCallSiteTest {

    @Test fun `a list row's cached time is keyed on the clock setting`() {
        assertEquals(
            "EmailListItem must read LocalIs24Hour and key the remembered time on it; without the " +
                "key a row keeps the form it was first drawn in. Lines found:",
            listOf(
                "val is24Hour = LocalIs24Hour.current",
                "val receivedLabel = remember(email.receivedAt, is24Hour) { " +
                    "MailDates.formatListDate(email.receivedAt, is24Hour = is24Hour) }",
            ),
            codeLines(source("ui/components/EmailListItem.kt")).filter {
                "is24Hour" in it || "receivedLabel = " in it
            },
        )
    }

    @Test fun `the event card and the header form their times with the local's value`() {
        assertEquals(
            "MessageHeader and CalendarEventCard must each read LocalIs24Hour, the header through a " +
                "local formatFull, the card by passing it to formatEventWhen. Lines found:",
            listOf(
                "val is24Hour = LocalIs24Hour.current",
                "fun formatFull(iso: String?) = MailDates.formatFull(iso, is24Hour = is24Hour)",
                "val is24Hour = LocalIs24Hour.current",
                "text = formatEventWhen(event, is24Hour),",
                "private fun formatEventWhen(event: ParsedEvent, is24Hour: Boolean): String {",
            ),
            codeLines(source("ui/message/MessageScreen.kt")).filter {
                "LocalIs24Hour.current" in it || "formatEventWhen" in it || "fun formatFull(iso: String?) =" in it
            },
        )
    }

    @Test fun `quiet hours form their times and their picker with the local's value`() {
        assertEquals(
            "TimePickerRow must read LocalIs24Hour and hand it to formatMinutes and to the picker. " +
                "Lines found:",
            listOf(
                "private fun formatMinutes(minutes: Int, is24Hour: Boolean): String =",
                "if (is24Hour) {",
                "val is24Hour = LocalIs24Hour.current",
                "formatMinutes(minutes, is24Hour),",
                "is24Hour = is24Hour,",
            ),
            codeLines(source("ui/settings/SettingsScreen.kt")).filter { "is24Hour" in it },
        )
    }

    @Test fun `the activity holds the setting, provides it, and rereads it on resume`() {
        val lines = codeLines(source("MainActivity.kt"))
        assertEquals(
            "MainActivity must hold the setting in a state, reread it on ACTION_TIME_CHANGED, and " +
                "provide it as LocalIs24Hour. Lines found:",
            listOf(
                "private val is24Hour = androidx.compose.runtime.mutableStateOf(MailDates.is24Hour())",
                "override fun onReceive(context: Context, intent: Intent) { " +
                    "is24Hour.value = MailDates.is24Hour() }",
                "LocalIs24Hour provides is24Hour.value,",
                "is24Hour.value = MailDates.is24Hour()",
            ),
            lines.filter { "is24Hour" in it },
        )
        val resume = lines.indexOf("override fun onResume() {")
        assertEquals(
            "onResume must reread the setting, since the Settings app is usually in front when it " +
                "changes. onResume's first lines:",
            listOf("override fun onResume() {", "super.onResume()", "is24Hour.value = MailDates.is24Hour()"),
            if (resume < 0) emptyList() else lines.subList(resume, minOf(resume + 3, lines.size)),
        )
    }

    @Test fun `the activity listens to ACTION_TIME_CHANGED while started`() {
        assertEquals(
            "the receiver must be registered for Intent.ACTION_TIME_CHANGED, not exported, and " +
                "unregistered. A non-exported receiver still gets it: the system and the Settings " +
                "app send it under the system uid. Lines found:",
            listOf(
                "private val timeChangedReceiver = object : BroadcastReceiver() {",
                "unregisterReceiver(timeChangedReceiver)",
                "ContextCompat.registerReceiver(",
                "timeChangedReceiver,",
                "IntentFilter(Intent.ACTION_TIME_CHANGED),",
                "ContextCompat.RECEIVER_NOT_EXPORTED,",
            ),
            codeLines(source("MainActivity.kt")).filter {
                "timeChangedReceiver" in it || "ACTION_TIME_CHANGED" in it ||
                    "registerReceiver(" in it || "RECEIVER_" in it
            },
        )
    }

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun source(underPackage: String): String =
        locate("app/src/main/kotlin/app/sterna/$underPackage").readText()

    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
