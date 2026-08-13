package app.sterna.core.data.account

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The one XML parser configuration this module trusts on input it did not write: the K-9
 * settings import and the mail-autoconfig cascade both parse documents handed over by the
 * outside world, and neither needs doctypes, external entities or XIncludes.
 *
 * Extracted verbatim from [K9SettingsImporter.parse]; the behaviour is the importer's,
 * unchanged. Each `setFeature` is wrapped in `runCatching` because a parser that does not
 * know a hardening feature must not make the whole parse throw — the features that ARE
 * known still apply.
 */
internal fun safeXmlDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        // Harden against XXE / entity-expansion — a settings file needs none of it.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
    }

/** The direct child ELEMENTS of this element, in document order. */
internal fun Element.childElements(): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
    }
    return out
}

/** Trimmed text of the first direct child element named [tag], or "" if absent. */
internal fun Element.childText(tag: String): String =
    childElements().firstOrNull { it.tagName == tag }?.textContent?.trim().orEmpty()
