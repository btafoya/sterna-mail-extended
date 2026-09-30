package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class K9SettingsImporterTest {

    private fun parse(resource: String): K9ImportResult {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("missing test fixture $resource")
        return stream.use { K9SettingsImporter.parse(it) }
    }

    @Test fun outlook_xoauth2_mapsToOAuthImapAccount() {
        val result = parse("/k9s/outlook-xoauth2.k9s")

        assertTrue(result.skipped.isEmpty())
        assertEquals(1, result.accounts.size)
        val account = result.accounts.single()

        assertEquals(MailProtocol.IMAP, account.protocol)
        assertEquals(AuthType.OAUTH, account.authType)
        assertEquals("outlook.office365.com", account.imapHost)
        assertEquals(993, account.imapPort)
        assertEquals(ConnectionSecurity.TLS, account.imapSecurity)
        assertEquals("smtp.office365.com", account.smtpHost)
        assertEquals(587, account.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, account.smtpSecurity)
        assertEquals("demo@example.com", account.username)

        // No secrets are ever carried across from the export.
        assertEquals("", account.oauthAccessToken)

        assertEquals(1, account.identities.size)
        val identity = account.identities.single()
        assertEquals("identity-0", identity.id)
        assertEquals("Demo User", identity.name)
        assertEquals("demo@example.com", identity.email)
        // signatureUse=false → no signature carried over.
        assertEquals("", identity.signature)
    }

    @Test fun imapPlain_mapsToBasicAndKeepsSignatureWhenUsed() {
        val result = parse("/k9s/imap-plain.k9s")

        assertEquals(1, result.accounts.size)
        val account = result.accounts.single()
        assertEquals(AuthType.BASIC, account.authType)
        assertEquals(ConnectionSecurity.TLS, account.imapSecurity)
        assertEquals(ConnectionSecurity.STARTTLS, account.smtpSecurity)

        val identity = account.identities.single()
        assertEquals("Cheers,\nVictor", identity.signature)
    }

    @Test fun knownSecurityValuesAreMappedAndNeedNoCheck() {
        val result = parse("/k9s/imap-plain.k9s")

        assertEquals(ConnectionSecurity.TLS, result.accounts.single().imapSecurity)
        assertEquals(ConnectionSecurity.STARTTLS, result.accounts.single().smtpSecurity)
        assertTrue(result.securityUnverified.isEmpty())
    }

    /** An unmapped or missing connection-security must never fall back to cleartext. */
    @Test fun unknownOrMissingSecurityFallsBackToEncryptedAndIsFlagged() {
        val result = parse("/k9s/security-unknown.k9s")

        assertEquals(2, result.accounts.size)
        for (account in result.accounts) {
            assertEquals(ConnectionSecurity.TLS, account.imapSecurity)
            assertEquals(ConnectionSecurity.STARTTLS, account.smtpSecurity)
        }
        // Both accounts are reported so the import tells the user to check them.
        assertEquals(
            listOf("Optional Security", "No Security Element"),
            result.securityUnverified,
        )
    }

    @Test fun pop3_isSkipped() {
        val result = parse("/k9s/pop3.k9s")

        assertTrue(result.accounts.isEmpty())
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.POP3, result.skipped.single().reason)
    }

    @Test fun multi_importsGoodImapAndSkipsPop() {
        val result = parse("/k9s/multi.k9s")

        assertEquals(2, result.accounts.size)
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.POP3, result.skipped.single().reason)

        val twoIdentities = result.accounts.first { it.username == "three@example.com" }
        assertEquals(2, twoIdentities.identities.size)
        assertEquals("identity-0", twoIdentities.identities[0].id)
        assertEquals("identity-1", twoIdentities.identities[1].id)
    }

    @Test fun malformedMixed_importsGoodOneAndSkipsBroken() {
        val result = parse("/k9s/malformed-mixed.k9s")

        assertEquals(1, result.accounts.size)
        assertEquals("healthy@example.com", result.accounts.single().username)
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.MALFORMED, result.skipped.single().reason)
    }

    @Test fun unknownVersion_isIgnored() {
        val result = parse("/k9s/unknown-version.k9s")

        assertEquals(1, result.accounts.size)
        assertEquals("future@example.com", result.accounts.single().username)
    }

    private fun k9s(incomingUser: String, outgoingUserElement: String, auth: String = "PLAIN") = """
        <k9settings version="110" format="1">
          <accounts>
            <account uuid="00000000-0000-0000-0000-000000000240">
              <name>Split login</name>
              <incoming-server type="IMAP">
                <host>imap.migadu.example</host>
                <port>993</port>
                <connection-security>SSL_TLS_REQUIRED</connection-security>
                <authentication-type>$auth</authentication-type>
                <username>$incomingUser</username>
                <password>imap-secret</password>
              </incoming-server>
              <outgoing-server type="SMTP">
                <host>smtp.origin.example</host>
                <port>587</port>
                <connection-security>STARTTLS_REQUIRED</connection-security>
                <authentication-type>$auth</authentication-type>
                $outgoingUserElement
                <password>smtp-secret</password>
              </outgoing-server>
            </account>
          </accounts>
        </k9settings>
    """.trimIndent()

    private fun single(xml: String): StoredAccount =
        xml.byteInputStream().use { K9SettingsImporter.parse(it) }.accounts.single()

    @Test fun outgoingUsername_differentFromIncoming_isKeptAsTheSmtpLogin() {
        val account = single(k9s("reader@migadu.example", "<username>sender@origin.example</username>"))
        assertEquals(
            "K-9 sent with this login: dropping it makes every send go out with the IMAP login (#240)",
            "sender@origin.example",
            account.smtpUsername,
        )
    }

    @Test fun outgoingUsername_sameAsIncoming_orAbsent_leavesTheSmtpLoginBlank() {
        assertEquals("", single(k9s("reader@migadu.example", "<username>reader@migadu.example</username>")).smtpUsername)
        assertEquals("", single(k9s("reader@migadu.example", "<username>Reader@Migadu.example</username>")).smtpUsername)
        assertEquals("", single(k9s("reader@migadu.example", "<username> </username>")).smtpUsername)
        assertEquals("", single(k9s("reader@migadu.example", "")).smtpUsername)
    }

    @Test fun outgoingUsername_underOAuth_isIgnored() {
        assertEquals(
            "",
            single(k9s("reader@outlook.example", "<username>other@outlook.example</username>", auth = "XOAUTH2")).smtpUsername,
        )
    }

    @Test fun anImportCarriesNoPasswordOfEitherServer() {
        val account = single(k9s("reader@migadu.example", "<username>sender@origin.example</username>"))
        val encoded = kotlinx.serialization.json.Json.encodeToString(StoredAccount.serializer(), account)
        assertTrue("an imported record holds a password from the file: $encoded", "secret" !in encoded)
    }

    @Test fun nonK9sRoot_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            "<foo/>".byteInputStream().use { K9SettingsImporter.parse(it) }
        }
    }
}
