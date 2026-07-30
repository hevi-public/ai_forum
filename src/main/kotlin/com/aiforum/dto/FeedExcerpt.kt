package com.aiforum.dto

/**
 * The feed card's one-line preview: [Snippet]'s flatten-collapse-clip with bare URLs removed first.
 *
 * One body shape earns the rule. `AmbientTickService` opens an article thread with
 * `"${article.summary}\n\n${article.url}"`, and a thread with no replies previews its own opening
 * post — so the first thing a fresh ambient article card would show is a summary trailing an
 * `https://…`. [Snippet] does not save us: its parser is built with TablesExtension alone, so a bare
 * URL is never a Link node to drop the destination of — it arrives as ordinary Text and `plainText`
 * concatenates it like any other literal. `FeedExcerptTest` pins that contrast against
 * `Snippet.oneLine` on the same body rather than assuming it, so if [Snippet]'s parse ever changes,
 * the premise recorded here is re-read instead of trusted.
 *
 * [Snippet] is deliberately NOT taught the rule (S6 D8): it feeds every rail box, branch-index entry
 * and in-reply-to line, and a URL surviving into those is not a defect worth that blast radius. The
 * price is a duplicated three-line clip, and the guard against the two drifting apart is a Tier-0
 * test asserting they agree character-for-character on a URL-free body.
 */
object FeedExcerpt {

    fun of(body: String, max: Int): String {
        val s = BARE_URL.replace(Snippet.plainText(body), "").replace(WHITESPACE, " ").trim()
        return if (s.length <= max) s else s.take(max).trimEnd() + "…"
    }

    /**
     * Scheme-qualified only. A bare `example.com` in prose is a word the sentence needs, and `\S+`
     * anchored to nothing but a dot would eat it; the schemes named here are the ones a body can
     * carry as a link the author never marked up.
     */
    private val BARE_URL = Regex("(?i)\\b(?:https?|ftp)://\\S+")

    private val WHITESPACE = Regex("\\s+")
}
