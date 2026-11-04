package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one filter both ways into a link go through: the brief tap and the long press (#287). */
class OpenableLinkTargetTest {

    @Test fun `schemes that could reach into another app or the device are refused`() {
        for (url in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "javascript:alert(1)",
            "file:///sdcard/secret.txt",
            "content://contacts/people/1",
            "data:text/html,<b>x</b>",
            "INTENT://x",
        )) {
            assertNull("must refuse $url", openableLinkTarget(url, stripTracking = true))
            assertNull("must refuse $url", openableLinkTarget(url, stripTracking = false))
        }
    }

    @Test fun `tracking parameters go only when stripping is on`() {
        assertEquals("https://x/?x=1", openableLinkTarget("https://x/?utm_source=a&x=1", stripTracking = true))
        assertEquals(
            "https://x/?utm_source=a&x=1",
            openableLinkTarget("https://x/?utm_source=a&x=1", stripTracking = false),
        )
    }

    @Test fun `contact and web schemes pass, whatever their case`() {
        assertEquals("mailto:a@b.c", openableLinkTarget("mailto:a@b.c", stripTracking = true))
        assertEquals("tel:+33123", openableLinkTarget("tel:+33123", stripTracking = true))
        assertEquals("HTTPS://x/", openableLinkTarget("HTTPS://x/", stripTracking = true))
    }

    @Test fun `an address with no scheme is refused`() {
        assertNull(openableLinkTarget("www.example.com", stripTracking = true))
    }
}
