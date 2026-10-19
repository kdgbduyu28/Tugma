package com.timborja.tugma.upnp

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Tiny DOM helper. javax.xml ships with both Android and the JVM, so there are no extra dependencies. */
object Xml {
    fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Speakers never send DTDs; refuse them so a hostile device can't do XXE tricks.
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t'))))
        return doc.documentElement
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}

/** Element's local name, ignoring namespace prefixes. */
val Element.name: String get() = localName ?: tagName.substringAfter(':')

fun Element.children(): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
    }
    return out
}

fun Element.children(localName: String): List<Element> = children().filter { it.name == localName }

fun Element.child(localName: String): Element? = children().firstOrNull { it.name == localName }

fun Element.childText(localName: String): String? = child(localName)?.textContent?.trim()

/** Depth-first search for the first descendant with [localName]. */
fun Element.find(localName: String): Element? {
    for (c in children()) {
        if (c.name == localName) return c
        c.find(localName)?.let { return it }
    }
    return null
}

fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null
