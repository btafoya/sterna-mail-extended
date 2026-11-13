package app.sterna.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailFormatTest {

    @Test fun `a domain of one label or more is an address (#294)`() {
        listOf("a@b", "x@yggmail", "root@localhost", "bob@example.c", "bob@example.com", "  leo@yggmail  ")
            .forEach { assertTrue("should be an address: \"$it\"", isValidEmail(it)) }
    }

    @Test fun `a missing part, a second at sign, a space or an empty label is not`() {
        listOf("", "bob", "bob@", "@b", "a@@b", "a b@c", "a@b c", "bob@example.", "bob@.com", "bob@a..b",
            "bob@example.co m")
            .forEach { assertFalse("should not be an address: \"$it\"", isValidEmail(it)) }
    }
}
