package app.sterna.ui

import app.sterna.MailtoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLinkRouteTest {

    @Test fun `a 200 000 character shared body stays out of the route`() {
        val body = "lorem ipsum ".repeat(20_000).take(200_000)
        val route = composeLinkRoute(MailtoDraft("", "", "", "", body), TOKEN, IDENTITY)
        assertFalse(
            "the compose route carries the shared body (${route.length} characters): a body of " +
                "~100 kB makes it match no destination and kills the app before the composer opens",
            "lorem ipsum" in route,
        )
        assertTrue("the route must stay short, it is ${route.length} characters", route.length < 200)
    }

    @Test fun `the route carries the addresses, the subject and the token, nothing else`() {
        assertEquals(
            "compose?to=a@x&cc=c@x&bcc=b@x&subject=Hi&bodyToken=$TOKEN",
            composeLinkRoute(MailtoDraft("a@x", "c@x", "b@x", "Hi", "the body"), TOKEN, IDENTITY),
        )
    }

    @Test fun `no token, no token argument`() {
        assertEquals(
            "compose?to=a@x&cc=&bcc=&subject=",
            composeLinkRoute(MailtoDraft("a@x", "", "", "", ""), null, IDENTITY),
        )
    }

    @Test fun `every prefill field goes through the encoder`() {
        assertEquals(
            "compose?to=[a]&cc=[c]&bcc=[b]&subject=[s]&bodyToken=$TOKEN",
            composeLinkRoute(MailtoDraft("a", "c", "b", "s", "body"), TOKEN) { "[$it]" },
        )
    }

    private companion object {
        const val TOKEN = "0f8e2c1a-5b7d-4e3f-9a6b-2c4d8e1f0a3b"

        /** The app passes `Uri.encode`, which the JVM's android.jar stub cannot run. */
        val IDENTITY: (String) -> String = { it }
    }
}
