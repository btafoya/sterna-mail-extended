package app.sterna.ui.settings

import app.sterna.R
import app.sterna.contacts.normalizeForMatch
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub's search: the folding, the matching, which entries a given install offers, and the route
 *  a result opens. All four run here; what the hub draws with them is not reached. */
class SettingsSearchTest {

    // -- folding and matching ------------------------------------------------------------------

    private fun find(query: String, vararg titles: String): List<String> =
        searchSettings(query, titles.toList(), { it }, { emptyList() })

    @Test fun `an accent is folded away`() {
        assertEquals(listOf("Thème"), find("theme", "Thème", "Langue"))
    }

    @Test fun `case is folded away and a German umlaut is folded to its vowel`() {
        assertEquals(listOf("Textgröße"), find("TEXTGRO", "Textgröße", "Design"))
    }

    @Test fun `Cyrillic matches as typed`() {
        assertEquals(listOf("Тема"), find("тем", "Тема", "Язык"))
    }

    @Test fun `sharp s is not folded, a known limit`() {
        // `ß`, like `ł`, `ø` and `đ`, is a letter of its own and not a base plus a mark: NFD
        // leaves it whole. Pinned so that a fix, when it comes, is a deliberate one.
        assertEquals("straße", normalizeForMatch("Straße"))
        assertEquals(emptyList<String>(), find("strasse", "Straße"))
        assertEquals(listOf("Straße"), find("straße", "Straße"))
    }

    @Test fun `every word must be found, in any order`() {
        assertEquals(listOf("Serveur IMAP"), find("serveur imap", "Serveur IMAP", "Serveur SMTP"))
        assertEquals(listOf("Serveur IMAP"), find("imap serveur", "Serveur IMAP", "Serveur SMTP"))
        assertEquals(emptyList<String>(), find("serveur pop", "Serveur IMAP", "Serveur SMTP"))
    }

    @Test fun `words may be split between the title and an option`() {
        val theme = "Thème" to listOf("Automatique", "Clair", "Sombre")
        val found = searchSettings("theme sombre", listOf(theme), { it.first }, { it.second })
        assertEquals(listOf(theme), found)
    }

    @Test fun `a blank query matches nothing`() {
        assertEquals(emptyList<String>(), find("   ", "Thème"))
        assertEquals(emptyList<String>(), find("", "Thème"))
    }

    @Test fun `a match in the title comes before a match elsewhere, otherwise the given order`() {
        val bySummary = "Notifications du compte" to listOf("Son et vibration")
        val byTitle = "Son" to emptyList<String>()
        val alsoByTitle = "Son des alertes" to emptyList<String>()
        val found = searchSettings(
            "son",
            listOf(bySummary, byTitle, alsoByTitle),
            { it.first },
            { it.second },
        )
        assertEquals(listOf(byTitle, alsoByTitle, bySummary), found)
    }

    // -- which entries an install offers --------------------------------------------------------

    private fun imapAccount(authType: AuthType = AuthType.BASIC) = StoredAccount(
        id = "imap-uuid",
        server = "",
        username = "alex.rivera@example.org",
        protocol = MailProtocol.IMAP,
        authType = authType,
        imapHost = "imap.example.org",
    )

    private fun jmapAccount(authType: AuthType = AuthType.BASIC) = StoredAccount(
        id = "jmap-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
        authType = authType,
    )

    private fun offered(current: StoredAccount?, sdkInt: Int = 34): Set<String> =
        searchableSettings(current, sdkInt).map { it.key }.toSet()

    private val accountKeys = SETTINGS_INDEX.filter { it.host == SettingHost.CurrentAccount }.map { it.key }.toSet()

    @Test fun `an IMAP account offers its IMAP rows and not the JMAP server address`() {
        val keys = offered(imapAccount())
        assertTrue(keys.containsAll(listOf("imap_server", "smtp_port", "upload_sent", "relay_address", "password")))
        assertFalse("server_url" in keys)
    }

    @Test fun `a JMAP account offers its server address and none of the IMAP rows`() {
        val keys = offered(jmapAccount())
        assertTrue("server_url" in keys)
        val imapOnly = listOf("imap_server", "imap_port", "imap_security", "smtp_server", "smtp_port", "smtp_security", "upload_sent", "relay_address")
        assertEquals(emptyList<String>(), imapOnly.filter { it in keys })
    }

    @Test fun `an OAuth account offers neither a password nor a token`() {
        val keys = offered(imapAccount(AuthType.OAUTH))
        assertFalse("password" in keys)
        assertFalse("api_token" in keys)
        assertTrue("username" in keys)
    }

    @Test fun `an API-token account offers the token and not a password`() {
        val keys = offered(jmapAccount(AuthType.API_TOKEN))
        assertTrue("api_token" in keys)
        assertFalse("password" in keys)
    }

    @Test fun `a shared current account offers no account entry, the app entries stay`() {
        // Its login's rows (password, server, OpenPGP) are drawn on no screen this mailbox opens.
        val keys = offered(delegated())
        assertEquals(emptySet<String>(), keys intersect accountKeys)
        assertTrue(keys.containsAll(listOf("theme", "quiet_hours", "vacation", "accounts")))
    }

    @Test fun `a login current account does offer the account entries`() {
        assertTrue(offered(login()).containsAll(listOf("username", "password", "pgp", "identities")))
    }

    @Test fun `no current account offers no account entry, the app entries stay`() {
        val keys = offered(null)
        assertEquals(emptySet<String>(), keys intersect accountKeys)
        assertTrue(keys.containsAll(listOf("theme", "quiet_hours", "vacation", "accounts")))
    }

    @Test fun `Material You colours are offered from Android 12 only, as the screen draws them`() {
        assertFalse("dynamic_color" in offered(jmapAccount(), sdkInt = 30))
        assertTrue("dynamic_color" in offered(jmapAccount(), sdkInt = 31))
    }

    // -- where an opened screen lands -----------------------------------------------------------

    @Test fun `a row drawn under a condition opens on its parent, which is always drawn`() {
        val expected = mapOf(
            "quiet_hours_start" to R.string.settings_quiet_hours_title,
            "quiet_hours_end" to R.string.settings_quiet_hours_title,
            "relay_address" to R.string.settings_account_notifications_title,
            "pgp_provider" to R.string.settings_pgp_enable_title,
            "pgp_key" to R.string.settings_pgp_enable_title,
            "pgp_encrypt_default" to R.string.settings_pgp_enable_title,
            "signature" to R.string.settings_identities_section,
        )
        expected.forEach { (key, parent) -> assertEquals(key, parent, settingAnchorFor(key)) }
    }

    @Test fun `a section with no setting row of its own opens on its header`() {
        val expected = mapOf(
            "image_allowlist" to R.string.settings_image_allowlist_section,
            "account_colour" to R.string.settings_account_colour_section,
            "identities" to R.string.settings_identities_section,
            "signature" to R.string.settings_identities_section,
        )
        expected.forEach { (key, header) ->
            val entry = settingEntryFor(key)
            assertEquals(key, header, entry?.anchor)
            assertEquals(key, AnchorKind.SECTION, entry?.anchorKind)
        }
    }

    @Test fun `a row anchor is flashed on the row, never on a header of the same name`() {
        // "Quiet hours" and "Theme" title both a section and the row under it.
        assertEquals(AnchorKind.ROW, settingEntryFor("quiet_hours")?.anchorKind)
        assertEquals(AnchorKind.ROW, settingEntryFor("theme")?.anchorKind)
    }

    // -- the route a result opens ---------------------------------------------------------------

    private fun login() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun delegated() = StoredAccount(
        id = "jordan-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        accountName = "jordan.lee@example.org",
        loginId = "login-uuid",
        jmapAccountId = "u",
    )

    private fun entry(key: String) = SETTINGS_INDEX.single { it.key == key }

    @Test fun `a rubric opens its screen without a focus key`() {
        assertEquals("vacation", searchResultRoute(entry("vacation"), "login-uuid", listOf(login())))
    }

    @Test fun `an app setting opens its screen with its key`() {
        assertEquals("appearance?focus=theme", searchResultRoute(entry("theme"), null, emptyList()))
    }

    @Test fun `an account setting opens the current account through the account route`() {
        assertEquals(
            "account/login-uuid?focus=username",
            searchResultRoute(entry("username"), "login-uuid", listOf(login(), delegated())),
        )
    }

    @Test fun `an account setting never opens the login editor for a shared mailbox`() {
        // The index offers no account entry for a shared current account; the route is still asked
        // of settingsRouteFor, so nothing reaching here with one lands on the editor whose password
        // field writes under the login's key.
        val route = searchResultRoute(entry("username"), "jordan-uuid", listOf(login(), delegated()))
        assertTrue("got $route", route!!.startsWith("sharedAccount/jordan-uuid"))
    }

    @Test fun `an account setting without a current account opens nothing`() {
        assertNull(searchResultRoute(entry("username"), null, listOf(login())))
    }

    @Test fun `the focus key is a plain word, unique, and leads back to its anchor`() {
        val keys = SETTINGS_INDEX.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals(emptyList<String>(), keys.filterNot { Regex("[a-z_]+").matches(it) })
        SETTINGS_INDEX.forEach { assertEquals(it.key, it.anchor, settingAnchorFor(it.key)) }
    }

    @Test fun `a route pattern made focusable still carries its own arguments`() {
        assertEquals("account/{id}?focus={focus}", focusable("account/{id}"))
    }
}
