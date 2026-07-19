package com.aiforum.ambient

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** One parsed feed entry — the minimal shape [FeedArticleSource] turns into an [Article]. */
data class FeedItem(val title: String, val url: String, val summary: String)

/** Thrown when feed XML cannot be parsed safely (malformed, or a DOCTYPE present — see [FeedParser]).
 *  The message is deliberately generic so it is safe to log without echoing hostile feed content. */
class FeedParseException(message: String) : RuntimeException(message)

/**
 * Pure (Tier-0), dependency-free RSS 2.0 / Atom parser (plan_docs/ambient-slice-5.md §2 "Parse", §3
 * security posture). No feed library — the needed subset (title/link/summary across two formats) is
 * small, and a dependency is supply-chain surface this posture would have to defend.
 *
 * Everything fetched through the owner's allowlist is still treated as adversarial data, so the XML
 * reader is HARDENED:
 *  - `disallow-doctype-decl` — rejects ALL DTDs outright, which kills XXE *and* entity-expansion
 *    ("billion laughs") bombs in one flag (both require a DOCTYPE);
 *  - `FEATURE_SECURE_PROCESSING` — the JAXP limits belt-and-suspenders;
 *  - external general + parameter entities off, external-DTD load off, XInclude off, entity-reference
 *    expansion off.
 * Any DOCTYPE, or malformed XML, therefore surfaces as a [FeedParseException] (message safe to log).
 *
 * Per §4's content decision — **link + short excerpt, never bodies** — summaries/titles are
 * HTML-stripped, entity-decoded, whitespace-collapsed and hard-truncated. Items whose link is not
 * http(s), or whose title/link is blank, are skipped (the scheme allowlist is the first render-firewall
 * layer; `MarkdownRenderer.sanitizeUrls` stays the second).
 */
object FeedParser {

    private const val MAX_TITLE = 200
    private const val MAX_SUMMARY = 400

    fun parse(xml: String): List<FeedItem> {
        val doc = try {
            val builder = hardenedFactory().newDocumentBuilder()
            // No custom EntityResolver needed — disallow-doctype-decl means the parser never resolves one.
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            // SAXParseException for a disallowed DOCTYPE or malformed XML, or any other reader fault:
            // collapse to a domain error carrying only the exception TYPE, never the raw feed content.
            throw FeedParseException("could not parse feed XML (${e.javaClass.simpleName})")
        }

        val root = doc.documentElement ?: throw FeedParseException("feed XML has no root element")
        val isAtom = root.tagName.equals("feed", ignoreCase = true)
        val entryTag = if (isAtom) "entry" else "item"

        val entries = doc.getElementsByTagName(entryTag)
        val items = ArrayList<FeedItem>(entries.length)
        for (i in 0 until entries.length) {
            val el = entries.item(i) as? Element ?: continue
            val title = clean(directChildText(el, "title"))
            val link = (if (isAtom) atomLink(el) else directChildText(el, "link")).trim()
            val summaryRaw = if (isAtom) {
                directChildText(el, "summary").ifBlank { directChildText(el, "content") }
            } else {
                directChildText(el, "description")
            }
            val summary = clean(summaryRaw)

            if (title.isBlank() || link.isBlank()) continue          // an entry needs both to be usable
            if (!isHttpScheme(link)) continue                        // drop javascript:/data:/relative links
            items += FeedItem(
                title = truncate(title, MAX_TITLE),
                url = link,
                summary = truncate(summary, MAX_SUMMARY),
            )
        }
        return items
    }

    /** A fresh, fully-locked-down factory per call (DocumentBuilderFactory/Builder are not thread-safe,
     *  and a tick may run on the request thread or the scheduler thread). */
    private fun hardenedFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            // The one flag that matters most: no DOCTYPE at all → no XXE, no entity-expansion bomb.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            // namespace-UNaware: RSS core elements and Atom's default-namespace elements are both written
            // with bare tag names, so getElementsByTagName("item"/"entry") + tagName matching works for both.
            isNamespaceAware = false
        }

    /** Text of the first DIRECT child element named [tag] (case-insensitive), or "". Direct-child only,
     *  so a nested `<title>` inside an entry's HTML content can never shadow the entry's own title. */
    private fun directChildText(parent: Element, tag: String): String {
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.tagName.equals(tag, ignoreCase = true)) return n.textContent ?: ""
            n = n.nextSibling
        }
        return ""
    }

    /** Atom `<link href="…">`: prefer a `rel="alternate"` (or rel-less) link, else the first href seen. */
    private fun atomLink(entry: Element): String {
        var fallback = ""
        var n = entry.firstChild
        while (n != null) {
            if (n is Element && n.tagName.equals("link", ignoreCase = true)) {
                val href = n.getAttribute("href")
                if (href.isNotBlank()) {
                    val rel = n.getAttribute("rel")
                    if (rel.isBlank() || rel.equals("alternate", ignoreCase = true)) return href
                    if (fallback.isBlank()) fallback = href
                }
            }
            n = n.nextSibling
        }
        return fallback
    }

    /** Strip HTML tags, decode the common entities, collapse whitespace. Handles descriptions that carry
     *  escaped HTML (`&lt;p&gt;…`) — the XML parser un-escapes them into literal `<p>…` text, which the
     *  tag-strip then removes. */
    // Decode-then-strip, to a fixed point: stripping BEFORE decoding lets double-encoded markup
    // ("&amp;lt;script&amp;gt;" — no literal '<' until decodeEntities runs) survive the one strip
    // pass and re-materialise as a real tag at the end of the pipeline. Iterating both steps until
    // stable also kills entity-splicing ("&am<b></b>p;lt;" assembling "&lt;" out of stripped-tag
    // fragments). Four rounds bounds any realistic nesting; leftovers are inert text at the
    // escaped render sinks.
    private fun clean(raw: String): String {
        var s = raw
        for (i in 0 until 4) {
            val next = decodeEntities(s).replace(TAG, " ")
            if (next == s) break
            s = next
        }
        return s.replace(WS, " ").trim()
    }

    private fun decodeEntities(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
            .replace("&amp;", "&") // amp LAST, so a doubly-encoded "&amp;lt;" decodes to "<" not garbage

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun isHttpScheme(url: String): Boolean {
        val scheme = try { URI(url).scheme } catch (e: Exception) { null } ?: return false
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    private val TAG = Regex("<[^>]*>")
    private val WS = Regex("\\s+")
}
