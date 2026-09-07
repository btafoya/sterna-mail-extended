package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-receipt switch's stored value, RUN — the first of the two locks on #148.
 *
 * It is off until somebody turns it on, and that is not a taste. A read receipt tells a stranger
 * that a person opened their mail, at a time, on a device; `SECURITY.md` promises no such signal
 * leaves on open. Flipping the elvis in `SettingsRepository` would put the question in front of
 * every reader who has never heard of the setting, and nothing else in the suite would notice.
 *
 * The whole [androidx.datastore.preferences.core.Preferences] goes into [askReadReceiptFrom], for
 * the reason spelled out on `replyBarFrom`: as a function of an already-defaulted `Boolean?` the
 * default is defeatable from outside in one line, and the function under test stays green.
 */
class ReadReceiptSettingDefaultTest {

    @Test fun `nobody is asked until they ask to be asked`() {
        assertFalse(
            "a reader who has never seen this switch must never be offered to answer a sender's " +
                "read-receipt request",
            askReadReceiptFrom(emptyPreferences()),
        )
        assertFalse("and the named default says so too", ASK_READ_RECEIPT_DEFAULT)
    }

    @Test fun `the stored answer is honoured in both directions`() {
        assertTrue(askReadReceiptFrom(preferencesOf(storedKey to true)))
        assertFalse(askReadReceiptFrom(preferencesOf(storedKey to false)))
    }

    /**
     * The key is spelled out here rather than imported: it names persisted user data. Renaming it
     * silently turns the switch back off for everyone who had turned it on — which fails safe, and
     * is exactly why nothing else would ever report it.
     */
    @Test fun `the switch is read back from the key it was written to`() {
        assertTrue(askReadReceiptFrom(preferencesOf(booleanPreferencesKey("ask_read_receipt") to true)))
        // A neighbouring switch must not answer for it.
        assertFalse(askReadReceiptFrom(preferencesOf(booleanPreferencesKey("confirm_links") to true)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("ask_read_receipt")
}
