package app.sterna.pgp

import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Executes the start-mode decision. The expected modes are written out by hand per SDK level; the
 * platform constants are compile-time `static final int`, so they are inlined and need no Android
 * runtime.
 */
@Suppress("DEPRECATION")
class PgpInteractionStartModeTest {

    @Test fun `below Android 14 no options are sent`() {
        assertNull(pgpInteractionStartMode(33))
    }

    @Test fun `Android 14 and 15 grant the start with ALLOWED`() {
        assertEquals(
            "Android 14 blocks the provider's PendingIntent unless the visible sender grants it.",
            MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            pgpInteractionStartMode(34),
        )
        assertEquals(MODE_BACKGROUND_ACTIVITY_START_ALLOWED, pgpInteractionStartMode(35))
    }

    @Test fun `Android 16 and later grant the start only while Sterna is visible`() {
        assertEquals(MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE, pgpInteractionStartMode(36))
        assertEquals(MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE, pgpInteractionStartMode(37))
    }

    @Test fun `ALLOW_ALWAYS is never granted`() {
        for (sdk in 21..40) {
            assertNotEquals(
                "SDK $sdk: Sterna does not check which app produced the PendingIntent.",
                MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                pgpInteractionStartMode(sdk),
            )
        }
    }
}
