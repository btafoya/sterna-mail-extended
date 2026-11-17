package app.sterna.ui.message

import kotlin.math.pow

internal const val DARK_PAGE_MAX_LUMINANCE = 0.2

internal const val LIGHT_BACKGROUND_MIN_LUMINANCE = 0.5

/** The dark background [html] declares for its whole page, `0xRRGGBB`, or null: any doubt keeps the invert (#302). */
internal fun declaredDarkPageBackground(html: String): Int? {
    val markup = STYLE_ELEMENT.replace(html, " ")

    // Null here stands for a page declaration that does not read as a colour: a doubt.
    val page = mutableListOf<Int?>()
    val others = mutableListOf<Int>()

    for (style in STYLE_ELEMENT.findAll(html)) {
        for (rule in cssRules(style.groupValues[1])) {
            val values = backgroundValues(rule.declarations)
            if (values.isEmpty()) continue
            val pageRule = rule.selectors.all { it == "body" || it == "html" }
            // Whether a conditional block applies depends on the device: a page background in one
            // is a doubt, an inner one still counts against the page.
            if (pageRule && rule.conditional) page.add(null)
            else if (pageRule) values.mapTo(page, ::cssColor)
            else values.mapNotNullTo(others, ::innerBackground)
        }
    }
    for (tag in HTML_TAG.findAll(markup)) {
        val name = tag.groupValues[1].lowercase()
        val attributes = tag.groupValues[2]
        val values = attributeValue(attributes, BGCOLOR_ATTRIBUTE).map(::bareHex).toList() +
            attributeValue(attributes, STYLE_ATTRIBUTE).flatMap(::backgroundValues)
        if (name == "body" || name == "html") values.mapTo(page, ::cssColor)
        else values.mapNotNullTo(others, ::innerBackground)
    }

    if (page.isEmpty() || page.any { it == null } || page.distinct().size != 1) return null
    val colour = page.first()!!
    if (relativeLuminance(colour) >= DARK_PAGE_MAX_LUMINANCE) return null
    // A newsletter often sets a dark body around a white table: shown as declared, that table would
    // stay a white card in the dark theme. One light or unreadable background anywhere keeps the invert.
    if (others.any { it == NO_COLOUR || relativeLuminance(it) >= LIGHT_BACKGROUND_MIN_LUMINANCE }) return null
    return colour
}

/** The `text=` and `link=` colours the message's `<body>` declares, each null when absent or unreadable. */
internal data class BodyColours(val text: Int?, val link: Int?)

internal fun declaredBodyColours(html: String): BodyColours {
    val body = HTML_TAG.findAll(STYLE_ELEMENT.replace(html, " "))
        .firstOrNull { it.groupValues[1].equals("body", ignoreCase = true) }
        ?: return BodyColours(null, null)
    fun read(pattern: Regex) = attributeValue(body.groupValues[2], pattern).firstOrNull()?.let { cssColor(bareHex(it)) }
    return BodyColours(read(TEXT_ATTRIBUTE), read(LINK_ATTRIBUTE))
}

// A colour reaches our `<style>` only rewritten by this, never as the message wrote it: a value
// such as `#000;}</style><script>` would close our style element and put markup in the head.
internal fun cssHex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

private class CssRule(val selectors: List<String>, val declarations: String, val conditional: Boolean)

private val STYLE_ELEMENT = Regex("""<style\b[^>]*>(.*?)</style\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val IMPORTANT = Regex("""!\s*important""", RegexOption.IGNORE_CASE)
// Quoted values matched whole and possessively: a '>' in one does not end the tag, a stray '<' does not backtrack.
private val HTML_TAG = Regex("""<([a-zA-Z][\w-]*+)((?:[^>"']++|"[^"]*+"|'[^']*+')*+)>""")
private val BACKGROUND_DECLARATION = Regex("""(?<![\w-])background(?:-color)?\s*:\s*([^;]*)""", RegexOption.IGNORE_CASE)
private val HEX_COLOUR = Regex("""#([0-9a-f]{3}|[0-9a-f]{6})""", RegexOption.IGNORE_CASE)
private val BARE_HEX = Regex("""[0-9a-f]{3}|[0-9a-f]{6}""", RegexOption.IGNORE_CASE)
private val RGB_COLOUR = Regex(
    """rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*([0-9.]+)\s*)?\)""",
    RegexOption.IGNORE_CASE,
)
private val SHORTHAND_TOKEN = Regex("""url\([^)]*\)|rgba?\([^)]*\)|[^\s()]+""", RegexOption.IGNORE_CASE)
private val LENGTH = Regex("""-?\d*\.?\d+(px|%|em|rem|pt|vh|vw)?""")
private val NEUTRAL_BACKGROUNDS = setOf("transparent", "none", "inherit", "initial", "unset", "currentcolor")
private val SHORTHAND_KEYWORDS = setOf(
    "none", "no-repeat", "repeat", "repeat-x", "repeat-y", "space", "round",
    "left", "right", "top", "bottom", "center", "auto", "cover", "contain", "/",
    "scroll", "fixed", "local", "border-box", "padding-box", "content-box",
)

private val BGCOLOR_ATTRIBUTE = attributePattern("bgcolor")
private val STYLE_ATTRIBUTE = attributePattern("style")
private val TEXT_ATTRIBUTE = attributePattern("text")
private val LINK_ATTRIBUTE = attributePattern("link")

private fun attributePattern(name: String) =
    Regex("""(?<![\w-])$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)

private fun attributeValue(attributes: String, pattern: Regex): Sequence<String> =
    pattern.findAll(attributes)
        .map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }

private fun backgroundValues(declarations: String): List<String> =
    BACKGROUND_DECLARATION.findAll(declarations).map { it.groupValues[1] }.toList()

// Browsers read a `bgcolor` written without its '#'.
private fun bareHex(value: String): String = value.trim().let { if (BARE_HEX.matches(it)) "#$it" else it }

/** Every rule of [css], flattened, marked conditional when it sits inside any braced at-rule. */
private fun cssRules(css: String): List<CssRule> {
    val out = mutableListOf<CssRule>()
    collectRules(CSS_COMMENT.replace(css, " ").replace("<!--", " ").replace("-->", " "), false, out)
    return out
}

private fun collectRules(css: String, conditional: Boolean, out: MutableList<CssRule>) {
    var i = 0
    while (i < css.length) {
        val open = css.indexOf('{', i)
        if (open < 0) return
        val prelude = css.substring(i, open).substringAfterLast(';').substringAfterLast('}').trim()
        if (prelude.startsWith('@')) {
            var depth = 0
            var j = open
            while (j < css.length) {
                if (css[j] == '{') depth++
                else if (css[j] == '}' && --depth == 0) break
                j++
            }
            collectRules(css.substring(open + 1, minOf(j, css.length)), true, out)
            i = j + 1
        } else {
            val close = css.indexOf('}', open).let { if (it < 0) css.length else it }
            val selectors = prelude.split(',').map { it.trim().lowercase() }
            out.add(CssRule(selectors, css.substring(open + 1, close), conditional))
            i = close + 1
        }
    }
}

// An inner background: null to ignore it, NO_COLOUR when it does not read, else its colour.
private fun innerBackground(value: String): Int? {
    val v = IMPORTANT.replace(value, "").trim().lowercase()
    if (v in NEUTRAL_BACKGROUNDS) return null
    cssColor(v)?.let { return it }
    if (',' in v.replace(RGB_COLOUR, "")) return NO_COLOUR
    val tokens = SHORTHAND_TOKEN.findAll(v).map { it.value }.toList()
    val colours = tokens.mapNotNull(::cssColor)
    val rest = tokens.filter { cssColor(it) == null }
    val restReadable = rest.all { it in SHORTHAND_KEYWORDS || it.startsWith("url(") || LENGTH.matches(it) }
    return if (colours.size == 1 && restReadable) colours.single() else NO_COLOUR
}

// Outside 0..0xFFFFFF, so it is never a colour, and its luminance is never computed.
private const val NO_COLOUR = -1

// `white` is read only so a white card counts as light: it can never make a page dark.
private fun cssColor(value: String): Int? {
    val v = IMPORTANT.replace(value, "").trim().lowercase()
    when (v) {
        "black" -> return 0x000000
        "white" -> return 0xffffff
    }
    HEX_COLOUR.matchEntire(v)?.let { m ->
        val hex = m.groupValues[1]
        val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        return full.toInt(16)
    }
    RGB_COLOUR.matchEntire(v)?.let { m ->
        val alpha = m.groupValues[4]
        if (alpha.isNotEmpty() && alpha.toDoubleOrNull() != 1.0) return null
        val (r, g, b) = m.groupValues.subList(1, 4).map { it.toInt() }
        if (r > 255 || g > 255 || b > 255) return null
        return (r shl 16) or (g shl 8) or b
    }
    return null
}

private fun relativeLuminance(rgb: Int): Double {
    fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(rgb shr 16 and 0xff) + 0.7152 * channel(rgb shr 8 and 0xff) + 0.0722 * channel(rgb and 0xff)
}
