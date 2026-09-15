package app.sterna.ui.compose

import android.app.PendingIntent
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpInteractionPauseTest {

    /** The unit-test `android.jar` throws from every constructor, factory, `equals` and `toString`
     *  of [PendingIntent]. Nothing here may compare or print a [ComposeState.PgpInteraction]: the
     *  assertions go by identity and name states by class. */
    private fun pendingIntent(): PendingIntent {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PendingIntent::class.java) as PendingIntent
    }

    private val launched = ComposeState.PgpInteraction(pendingIntent())

    @Test fun `the pause being launched is consumed`() {
        assertTrue(
            "the launched pause is still the state: a recreated screen launches the provider again",
            composeStateAfterPgpLaunch(launched, launched) === ComposeState.Idle,
        )
    }

    @Test fun `a state that replaced the launched pause is left as it is`() {
        listOf(
            ComposeState.Idle,
            ComposeState.Sending,
            ComposeState.Done,
            ComposeState.Error("provider said no"),
            ComposeState.Error("draft not saved", whileSaving = true),
            ComposeState.PgpInteraction(pendingIntent()),
            ComposeState.PgpInteraction(launched.pendingIntent),
        ).forEach { current ->
            assertTrue(
                "${current::class.simpleName} was overwritten by consuming a pause it is not",
                composeStateAfterPgpLaunch(current, launched) === current,
            )
        }
    }
}
