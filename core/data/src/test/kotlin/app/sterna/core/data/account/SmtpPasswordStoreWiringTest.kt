package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint on the second encrypted slot of `AccountStore` (#240), same instrument and disclaimer
 * as [AccountStoreGateWiringTest]: it reads the text and proves nothing about behaviour. The store
 * needs a `Context` and the Keystore, so what these lines decide is executed in [SmtpLoginTest].
 *
 * What it does not see: a SMTP secret written by a path other than the two named here, and a
 * function whose braces sit inside a string literal, which would throw the body split off.
 */
class SmtpPasswordStoreWiringTest {

    @Test fun `every path that destroys the IMAP secret destroys the SMTP one`() {
        val destroyers = functionNames().filter { name -> body(name).any { "remove(passwordKey(" in it } }
        assertEquals(
            "the set of functions deleting `passwordKey` changed; each one must delete " +
                "`smtpPasswordKey` too, or a password outlives the account it belonged to.",
            listOf("remove", "convertToBasicAuth", "removeCascading").sorted(),
            destroyers.sorted(),
        )
        for (name in destroyers) {
            assertTrue(
                "$name deletes the IMAP secret and leaves the SMTP one behind:\n" + body(name).joinToString("\n"),
                body(name).any { "remove(smtpPasswordKey(" in it },
            )
        }
    }

    @Test fun `going to OAuth destroys the SMTP secret and forgets its username`() {
        val attach = body("attachOAuth")
        assertTrue(
            "attachOAuth keeps a SMTP secret that no send will ever read again:\n" + attach.joinToString("\n"),
            attach.contains("prefs.edit().remove(smtpPasswordKey(id)).apply()"),
        )
        assertTrue(attach.contains("smtpUsername = \"\","))
    }

    @Test fun `the SMTP slot has a key and an AAD of its own`() {
        val code = code()
        assertEquals(
            "the SMTP slot's AAD moved. Sharing `pw:` with the IMAP slot lets one blob be swapped " +
                "for the other and decrypt.",
            1,
            code.count { it == "private fun smtpAadFor(id: String) = \"smtppw:\$id\".toByteArray(Charsets.UTF_8)" },
        )
        assertEquals(1, code.count { it == "private fun smtpPasswordKey(id: String) = \"smtppw_\$id\"" })
        for (name in listOf("writeSmtpPassword", "readSmtpPassword")) {
            val b = body(name)
            assertTrue("$name no longer binds its blob to `smtpAadFor`:\n" + b.joinToString("\n"), b.any { "smtpAadFor(id)" in it })
            assertTrue("$name uses the IMAP slot's AAD:\n" + b.joinToString("\n"), b.none { "aadFor(id)" in it.replace("smtpAadFor(id)", "") })
        }
    }

    @Test fun `an unreadable SMTP secret never makes the credentials null`() {
        val b = body("credentials")
        assertEquals(
            "credentials() gained or lost a `return null`. The two it has are the unknown account " +
                "and the unreadable IMAP secret; a third on the SMTP secret makes the send workers " +
                "take a live account for a removed one.",
            2,
            b.count { "return null" in it },
        )
        assertTrue(
            b.joinToString("\n"),
            b.contains("smtpPassword = if (smtpUsername.isBlank()) \"\" else readSmtpPassword(login.id).orEmpty(),"),
        )
        assertTrue(b.joinToString("\n"), b.contains("val smtpUsername = if (login.authType == AuthType.OAUTH) \"\" else login.smtpUsername"))
    }

    @Test fun `the account editor writes the SMTP login through the shared decision`() {
        val b = body("updateSmtpLogin")
        assertTrue(
            "updateSmtpLogin no longer applies smtpSecretEdit with a blank that keeps:\n" + b.joinToString("\n"),
            b.contains("applySmtpSecretEdit(account.loginKey(), smtpSecretEdit(smtpUsername, smtpPassword, blankKeeps = true))"),
        )
        assertTrue(b.joinToString("\n"), b.contains("if (account.authType == AuthType.OAUTH) return"))
    }

    private fun code(): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private val declaration = Regex("""^(?:[a-z]+ )*fun ([A-Za-z]+)\(""")

    private fun functionNames(): List<String> = code().mapNotNull { declaration.find(it)?.groupValues?.get(1) }

    /** The code lines of the one `fun [name](`, its declaration to its closing brace. */
    private fun body(name: String): List<String> {
        val code = code()
        val starts = code.indices.filter { declaration.find(code[it])?.groupValues?.get(1) == name }
        val start = starts.singleOrNull() ?: return listOf("«${starts.size} declarations of `$name`»")
        var depth = 0
        var opened = false
        for (i in start until code.size) {
            depth += code[i].count { it == '{' } - code[i].count { it == '}' }
            if (code[i].contains('{')) opened = true
            if (opened && depth <= 0) return code.subList(start, i + 1)
        }
        return code.subList(start, code.size)
    }
}
