package app.sterna.ui

import app.sterna.ComposeShortcutActivity
import app.sterna.MainActivity
import app.sterna.UnreadShortcutActivity
import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resource lint, not a behaviour test, same instrument and disclaimer as
 * [app.sterna.ui.connect.OAuthRedirectManifestTest]: the launcher reads these declarations, this
 * module cannot. They are parsed as XML rather than matched as lines, so a comment explaining a
 * rule never satisfies it.
 */
class ComposeShortcutDeclarationTest {

    @Test fun `production declares the compose then the unread shortcut, aimed at its own package`() {
        assertEquals(expected("app.sterna"), shortcuts("app/src/main/res/xml/shortcuts.xml"))
    }

    /**
     * shortcuts.xml takes no placeholder, so the package is a literal, and the test app installs
     * beside production: a test-app shortcut naming `app.sterna` would open the real mailbox.
     */
    @Test fun `the test app's twin is identical but for its own package`() {
        assertEquals(expected("app.sterna.test"), shortcuts("app/src/testApp/res/xml/shortcuts.xml"))
    }

    @Test fun `the shortcuts are declared on the launcher activity and nowhere else`() {
        val carriers = elements(parse(MAIN_MANIFEST), "meta-data")
            .filter { it.android("name") == "android.app.shortcuts" }
            .map { meta ->
                val activity = meta.parentNode as Element
                Triple(activity.tagName, activity.android("name"), meta.android("resource"))
            }
        assertEquals(
            "the launcher only reads android.app.shortcuts on the activity whose intent-filter " +
                "carries the LAUNCHER category",
            listOf(Triple("activity", launcherActivity(), "@xml/shortcuts")),
            carriers,
        )
    }

    /**
     * Android starts a static shortcut with CLEAR_TASK. Only a relay in a task of its own absorbs
     * it: sharing the app's affinity, which is what leaving `taskAffinity` out means, lets the
     * system wipe the app's task and a composer open in it.
     */
    @Test fun `each relay lives in its own task, unexported, and answers no intent`() {
        RELAYS.forEach(::assertRelayDeclaration)
    }

    private fun assertRelayDeclaration(name: String) {
        val relay = elements(parse(MAIN_MANIFEST), "activity")
            .single { it.android("name").removePrefix("app.sterna") == ".$name" }
        assertTrue("the relay must declare its own android:taskAffinity", relay.hasAttributeNS(ANDROID, "taskAffinity"))
        val application = relay.parentNode as Element
        val appAffinities = if (application.hasAttributeNS(ANDROID, "taskAffinity")) {
            setOf(application.android("taskAffinity"))
        } else {
            setOf("app.sterna", "app.sterna.test", "${DOLLAR}{applicationId}")
        }
        assertTrue(
            "the relay's taskAffinity \"${relay.android("taskAffinity")}\" is the app's own",
            relay.android("taskAffinity") !in appAffinities,
        )
        assertEquals(
            name,
            mapOf(
                "exported" to "false",
                "excludeFromRecents" to "true",
                "noHistory" to "true",
                "theme" to "@android:style/Theme.NoDisplay",
            ),
            listOf("exported", "excludeFromRecents", "noHistory", "theme").associateWith { relay.android(it) },
        )
        assertEquals("$name answers no intent", 0, elements(relay, "intent-filter").size)
    }

    @Test fun `each relay hands over with NEW_TASK and never CLEAR_TASK`() {
        for (flags in listOf(ComposeShortcutActivity.LAUNCH_FLAGS, UnreadShortcutActivity.LAUNCH_FLAGS)) {
            assertEquals(FLAG_NEW_TASK, flags)
            assertEquals(0, flags and FLAG_CLEAR_TASK)
        }
    }

    /**
     * The value above is only what the relay sends if `onCreate` sends that constant and nothing
     * else: every flag the source names, and no read of the received intent, whose flags are the
     * CLEAR_TASK being shed.
     */
    @Test fun `each relay sends exactly LAUNCH_FLAGS and never forwards what it received`() {
        for (name in RELAYS) {
            val code = relaySource(name)
            assertEquals(
                name,
                listOf(".setFlags(LAUNCH_FLAGS),", "const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK"),
                code.filter { "flag" in it.lowercase() },
            )
            assertEquals(name, emptyList<String>(), code.filter { Regex("""\bintent\b""").containsMatchIn(it) })
        }
    }

    /** The widget's order opens All inboxes; this one must open the drawer's Unread view, and a
     *  shared key would make one shortcut, or the widget, open the other's view. */
    @Test fun `the unread relay sends the unread order and only that one`() {
        assertNotEquals(MainActivity.EXTRA_OPEN_UNIFIED, MainActivity.EXTRA_OPEN_UNREAD)
        assertEquals(
            listOf(".putExtra(MainActivity.EXTRA_OPEN_UNREAD, true)"),
            relaySource("UnreadShortcutActivity").filter { "extra" in it.lowercase() },
        )
    }

    /** The test app's manifest is merged into the main one under -PtestApp. An activity it
     *  redeclared could replace the launcher entry and drop the meta-data with it. */
    @Test fun `the test app manifest redeclares no activity`() {
        assertEquals(emptyList<String>(), elements(parse(TEST_MANIFEST), "activity").map { it.android("name") })
    }

    /** Android asks for shortcut labels of at most 25 characters, and falls back to English for a
     *  missing translation without a word. */
    @Test fun `every label exists in every language and fits the launcher`() {
        val res = file("app/src/main/res")
        val locales = res.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!
            .sortedBy { it.name }
        val wrong = locales.flatMap { dir ->
            val strings = elements(parse(File(dir, "strings.xml")), "string")
                .associate { it.getAttribute("name") to it.textContent }
            LABELS.mapNotNull { key ->
                val text = strings[key]
                when {
                    text.isNullOrBlank() -> "${dir.name}/$key: missing"
                    text.codePointCount(0, text.length) > 25 -> "${dir.name}/$key: \"$text\" is over 25"
                    else -> null
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertEquals("the eleven shipped languages", 11, locales.size)
    }

    /** The shortcut is a way into the drawer's Unread view, so it says that view's word. */
    @Test fun `the unread shortcut's short label is the drawer's own word in every language`() {
        val res = file("app/src/main/res")
        val differing = res.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val strings = elements(parse(File(dir, "strings.xml")), "string")
                    .associate { it.getAttribute("name") to it.textContent }
                val drawer = strings["inbox_unread_view"]
                val shortcut = strings["shortcut_unread_short"]
                if (drawer != null && drawer == shortcut) null else "${dir.name}: \"$shortcut\" vs \"$drawer\""
            }
        assertEquals(emptyList<String>(), differing)
    }

    private fun relaySource(name: String) = DeclarationSource.kotlinLines(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/$name.kt"),
    )

    private fun expected(pkg: String) = listOf(compose(pkg), unread(pkg))

    private fun unread(pkg: String) = mapOf(
        "shortcutId" to "unread",
        "enabled" to "true",
        "icon" to "@drawable/ic_shortcut_unread",
        "shortcutShortLabel" to "@string/shortcut_unread_short",
        "shortcutLongLabel" to "@string/shortcut_unread_long",
        "intents" to listOf(
            mapOf(
                "action" to "android.intent.action.MAIN",
                "targetPackage" to pkg,
                "targetClass" to "app.sterna.UnreadShortcutActivity",
            ),
        ),
    )

    private fun compose(pkg: String) = mapOf(
        "shortcutId" to "compose",
        "enabled" to "true",
        "icon" to "@drawable/ic_shortcut_compose",
        "shortcutShortLabel" to "@string/shortcut_compose_short",
        "shortcutLongLabel" to "@string/shortcut_compose_long",
        "intents" to listOf(
            mapOf(
                "action" to "android.intent.action.SENDTO",
                "data" to "mailto:",
                "targetPackage" to pkg,
                "targetClass" to "app.sterna.ComposeShortcutActivity",
            ),
        ),
    )

    /** Every `<shortcut>` with all of its android: attributes and its intents', so an attribute
     *  added to one twin and not the other is a difference too. */
    private fun shortcuts(path: String): List<Map<String, Any>> =
        elements(parse(file(path)), "shortcut").map { s ->
            androidAttributes(s) + ("intents" to elements(s, "intent").map(::androidAttributes))
        }

    private fun launcherActivity(): String = elements(parse(MAIN_MANIFEST), "activity")
        .filter { a ->
            elements(a, "category").any { it.android("name") == "android.intent.category.LAUNCHER" }
        }
        .map { it.android("name") }
        .single()

    private fun androidAttributes(e: Element): Map<String, String> {
        val attrs = e.attributes
        return (0 until attrs.length).map { attrs.item(it) }
            .filter { it.namespaceURI == ANDROID }
            .associate { it.localName to it.nodeValue }
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    private fun elements(root: org.w3c.dom.Node, tag: String): List<Element> {
        val list = when (root) {
            is org.w3c.dom.Document -> root.getElementsByTagName(tag)
            is Element -> root.getElementsByTagName(tag)
            else -> error("not a document or an element")
        }
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun parse(f: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(f)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val DOLLAR = '$'

        /** The platform's documented values for FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TASK. */
        const val FLAG_NEW_TASK = 0x10000000
        const val FLAG_CLEAR_TASK = 0x00008000
        val LABELS = listOf("shortcut_compose_short", "shortcut_compose_long", "shortcut_unread_short", "shortcut_unread_long")
        val RELAYS = listOf("ComposeShortcutActivity", "UnreadShortcutActivity")

        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        fun file(path: String): File = File(ROOT, path).also {
            if (!it.exists()) error("expected ${it.path} — was it moved?")
        }

        val MAIN_MANIFEST: File by lazy { file("app/src/main/AndroidManifest.xml") }
        val TEST_MANIFEST: File by lazy { file("app/src/testApp/AndroidManifest.xml") }
    }
}
