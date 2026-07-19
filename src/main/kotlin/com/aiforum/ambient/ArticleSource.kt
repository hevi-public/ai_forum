package com.aiforum.ambient

/**
 * The fifth IO port (see the bdd-tiered-testing skill's port table — alongside [com.aiforum.llm.LlmClient],
 * [com.aiforum.images.ImageDescriber], [com.aiforum.shortcut.ShortcutClient], [com.aiforum.github.GitHubClient]):
 * the ambient loop's source of raw material (plan_docs/ai-driven-forum-direction.md §3/§4, §9 row S1). A
 * narrow interface so the production adapter (a small canned fixture list in S1; real web sourcing +
 * dedupe in S5) and the scriptable test double (`ScriptableArticleSource` in TestBeans.kt) share one
 * shape. `next()` yields one article per call; `null` means nothing to post this tick (the ambient
 * tick's no-op path — no LLM call is made).
 */
interface ArticleSource {
    fun next(): Article?
}

/** One collected article: enough to open a thread from — title, source link, and a short summary. */
data class Article(val title: String, val url: String, val summary: String)
