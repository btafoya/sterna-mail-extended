package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleRefusalTest {

    @Test fun `a plain message with valid recipients may be scheduled`() {
        assertNull(scheduleRefusal("alice@x.org; bob@y.org", "carol@z.org", "", PgpMode.OFF))
    }

    @Test fun `no To recipient is refused with its own sentence`() {
        assertEquals(R.string.compose_schedule_needs_recipient, scheduleRefusal("", "carol@z.org", "", PgpMode.OFF))
        assertEquals(R.string.compose_schedule_needs_recipient, scheduleRefusal(" , ", "", "", PgpMode.OFF))
    }

    @Test fun `an invalid address in any field is refused now, not at the hour`() {
        for ((to, cc, bcc) in listOf(
            Triple("alice@x.org, bo", "", ""),
            Triple("alice@x.org", "bo", ""),
            Triple("alice@x.org", "", "bo"),
        )) {
            assertEquals(
                "to=$to cc=$cc bcc=$bcc: accepted, it fails in the worker at the hour, nobody at the screen",
                R.string.compose_schedule_invalid_address,
                scheduleRefusal(to, cc, bcc, PgpMode.OFF),
            )
        }
    }

    @Test fun `an empty To is said before an invalid address`() {
        assertEquals(R.string.compose_schedule_needs_recipient, scheduleRefusal("", "bo", "", PgpMode.OFF))
    }

    @Test fun `every OpenPGP mode is refused, signing included`() {
        for (mode in listOf(PgpMode.SIGN, PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED)) {
            assertEquals(
                "$mode: the worker sends without OpenPGP, so the message would go out unsigned or in the clear",
                R.string.compose_pgp_no_schedule,
                scheduleRefusal("alice@x.org", "", "", mode),
            )
        }
    }

    @Test fun `the OpenPGP refusal is said first, a recipient would not lift it`() {
        assertEquals(R.string.compose_pgp_no_schedule, scheduleRefusal("", "bo", "", PgpMode.SIGN))
    }

    @Test fun `a failed schedule is worded as a failed schedule`() {
        assertEquals(
            "worded as a failed send, she believes the message tried to go now; nothing was queued",
            R.string.compose_could_not_schedule,
            composeErrorWording(ComposeState.Error("x", whileScheduling = true)),
        )
        assertEquals(R.string.compose_could_not_send, composeErrorWording(ComposeState.Error("x")))
        assertEquals(R.string.compose_could_not_save, composeErrorWording(ComposeState.Error("x", whileSaving = true)))
    }
}
