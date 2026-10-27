package app.sterna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resource lint, not a behaviour test: it parses the splash resources and resolves each colour
 * the way the platform would on Android 12+, day and night (#268). Comments are dropped by the
 * XML parser, so prose cannot satisfy a rule.
 *
 * What it does not cover: whether the system honours `setSplashScreenTheme` on a given launcher,
 * and whether `system_*` colours draw what the wallpaper suggests. The qualifier order it encodes
 * (night before version) is Android's, restated here for the two folders this app uses; a third
 * qualifier folder added later would go unseen.
 */
class SplashThemeResourcesTest {

    @Test fun `both splash styles are declared, the dynamic one inheriting the fixed one`() {
        val styles = styles()
        assertTrue("values-v31/themes.xml must declare `$FIXED`", FIXED in styles)
        assertTrue("values-v31/themes.xml must declare `$DYNAMIC`", DYNAMIC in styles)
        assertEquals(
            "`$DYNAMIC` must inherit `$FIXED`, so the animation duration stays declared once",
            FIXED,
            styles.getValue(DYNAMIC).getAttribute("parent"),
        )
        assertEquals(
            "`$DYNAMIC` overrides the background and the animated icon, nothing else",
            setOf(BACKGROUND, ICON),
            items(styles.getValue(DYNAMIC)).keys,
        )
    }

    @Test fun `the dynamic splash resolves only to system colours, by day and by night, and they differ`() {
        val day = resolvedColours(DYNAMIC, night = false)
        val night = resolvedColours(DYNAMIC, night = true)
        for ((mode, colours) in listOf("day" to day, "night" to night)) {
            assertTrue("`$DYNAMIC` must reach at least the background and the tern colour ($mode): $colours", colours.size >= 2)
            assertTrue(
                "every colour `$DYNAMIC` draws must resolve to `@android:color/system_*` ($mode), " +
                    "a fixed colour would ignore Material You: $colours",
                colours.values.all { it.startsWith("@android:color/system_") },
            )
        }
        assertEquals("day and night must resolve the same colour names", day.keys, night.keys)
        for (name in day.keys) {
            assertNotEquals(
                "`@color/$name` must have its own night value in values-night-v31, or the dark " +
                    "splash shows the day palette",
                day.getValue(name),
                night.getValue(name),
            )
        }
        assertTrue(
            "the dynamic animated icon must paint the tern with `@color/sterna_splash_dynamic_tern`",
            "sterna_splash_dynamic_tern" in iconColourNames(DYNAMIC),
        )
    }

    @Test fun `the fixed splash never reaches a system colour`() {
        for (night in listOf(false, true)) {
            val colours = resolvedColours(FIXED, night)
            assertTrue("`$FIXED` must still reach its colours (night=$night): $colours", colours.size >= 2)
            assertEquals(
                "`$FIXED` is the splash of everyone who left Material You off; a `system_*` colour " +
                    "there imposes the wallpaper on all of them (night=$night)",
                emptyMap<String, String>(),
                colours.filterValues { "system_" in it },
            )
        }
    }

    private fun resolvedColours(style: String, night: Boolean): Map<String, String> {
        val item = items(styles().getValue(style).let { effective(it) })
        val names = mutableSetOf<String>()
        item[BACKGROUND]?.let { names += colourName(it) }
        names += iconColourNames(style)
        return names.associateWith { resolveColour(it, night) }
    }

    private fun iconColourNames(style: String): Set<String> {
        val icon = items(effective(styles().getValue(style)))[ICON] ?: error("`$style` has no `$ICON`")
        return drawableColourNames(icon.removePrefix("@drawable/"), mutableSetOf())
    }

    private fun drawableColourNames(name: String, seen: MutableSet<String>): Set<String> {
        if (!seen.add(name)) return emptySet()
        val file = listOf("drawable-v31", "drawable").map { res("$it/$name.xml") }.firstOrNull { it.isFile }
            ?: error("no drawable `$name` in drawable-v31/ or drawable/")
        val out = mutableSetOf<String>()
        for (value in attributeValues(parse(file).documentElement)) {
            when {
                value.startsWith("@color/") -> out += colourName(value)
                value.startsWith("@drawable/") -> out += drawableColourNames(value.removePrefix("@drawable/"), seen)
                value.startsWith("@android:color/") || value.startsWith("?") -> out += value
            }
        }
        return out
    }

    /** A literal platform colour or theme attribute in a drawable is kept as-is, so the rules see it. */
    private fun resolveColour(name: String, night: Boolean): String {
        if (name.startsWith("@android:") || name.startsWith("?")) return name
        val folders = if (night) listOf("values-night-v31", "values-night", "values-v31", "values") else listOf("values-v31", "values")
        for (folder in folders) {
            val file = res("$folder/colors.xml")
            if (!file.isFile) continue
            val colours = parse(file).documentElement.getElementsByTagName("color")
            for (i in 0 until colours.length) {
                val c = colours.item(i) as Element
                if (c.getAttribute("name") == name) {
                    val v = c.textContent.trim()
                    return if (v.startsWith("@color/")) resolveColour(colourName(v), night) else v
                }
            }
        }
        error("`@color/$name` is declared in none of $folders")
    }

    /** The style's own items over its parent's, when the parent lives in the same file. */
    private fun effective(style: Element): Element {
        val parent = style.getAttribute("parent")
        val styles = styles()
        if (parent !in styles) return style
        val merged = style.ownerDocument.createElement("style")
        (items(effective(styles.getValue(parent))) + items(style)).forEach { (k, v) ->
            merged.appendChild(style.ownerDocument.createElement("item").apply { setAttribute("name", k); textContent = v })
        }
        return merged
    }

    private fun items(style: Element): Map<String, String> {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun styles(): Map<String, Element> {
        val nodes = parse(res("values-v31/themes.xml")).documentElement.getElementsByTagName("style")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("name") }
    }

    private fun attributeValues(e: Element): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until e.attributes.length) out += e.attributes.item(i).nodeValue
        val children = e.childNodes
        for (i in 0 until children.length) (children.item(i) as? Element)?.let { out += attributeValues(it) }
        return out
    }

    private fun colourName(ref: String): String {
        assertTrue("expected a `@color/` reference, got `$ref`", ref.startsWith("@color/"))
        return ref.removePrefix("@color/")
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun res(path: String) = File(ROOT, "app/src/main/res/$path")

    private companion object {
        const val FIXED = "Theme.Sterna.Splash"
        const val DYNAMIC = "Theme.Sterna.Splash.Dynamic"
        const val BACKGROUND = "android:windowSplashScreenBackground"
        const val ICON = "android:windowSplashScreenAnimatedIcon"

        val ROOT: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
    }
}
