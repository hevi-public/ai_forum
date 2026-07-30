package com.aiforum.web

/**
 * The two readings of the front page (plan_docs/ambient-slice-6.md §2.1): the thread cards the forum has
 * always shown — now activity-sorted and carrying a preview — and the reverse-chronological stream of
 * everything that has settled, behind the toggle.
 *
 * **[ACTIVITY] is named "Activity" and never "Ambient", in the toggle, the heading and the empty state.**
 * The schema cannot express provenance: an ambient comment goes through the same
 * `GenerationService.summonAsync` an owner summon does, carrying no marker, and `ambient_run` records no
 * `comment_id` on purpose (V22). So the honest superset ships — all settled activity, author-agnostic —
 * and an ambient-only stream is deferred to a slice whose §2 makes provenance representable (D1, I6).
 *
 * **This enum is the first of three layers guarding the stored preference.**
 * [com.aiforum.repo.OwnerPrefRepository.setFeedView] takes this type, so nothing above the database has a
 * String door to write through; [of] is the second — it answers null for a slug that names no view, which
 * is what lets the endpoint refuse one with a 400 instead of storing it; V29's `CHECK (feed_view IN …)`
 * is the third and weakest-last.
 *
 * [slug] is the wire form in every direction — the hidden form value the toggle submits, the value stored
 * in `owner_pref.feed_view`, and the `data-*` hook the acceptance suite reads. Every slug declared here
 * must therefore also appear in V29's CHECK list, or this enum can produce a preference the database
 * refuses to store. [emptyStateKey] is the view's own empty state: the two views are empty for different
 * reasons ("no threads yet" is not "nothing has happened yet"), so one shared key would make the stream's
 * empty page claim something about threads.
 *
 * **Why its own file.** [com.aiforum.repo.OwnerPrefRepository] names this type in a parameter, so it has
 * to be somewhere the repository can import; the plan doc sketches it beside `HomeController` (§2.1), but
 * a controller is not a place a repository should have to reach into. Living alone here keeps the
 * repository's import pointing at a two-constant vocabulary rather than at the web layer's entry point.
 */
enum class FeedView(val slug: String, val title: String, val emptyStateKey: String) {
    THREADS("threads", "Threads", "no-threads"),
    ACTIVITY("activity", "Activity", "no-activity");

    companion object {
        /**
         * What an ABSENT `owner_pref` row means. V29 seeds no row deliberately (D4), so this constant —
         * not a stored value, and not a config property — is the front page's out-of-the-box view.
         */
        val DEFAULT = THREADS

        /**
         * A stored or submitted slug back to its view, or null when it names none.
         *
         * Null is a real answer at both call sites and means different things at each: at the endpoint it
         * is the 400 that keeps an unknown view out of the table, and at
         * [com.aiforum.repo.OwnerPrefRepository.feedView] it is the corrupt-row fallback to [DEFAULT] —
         * a preference nobody can read must not take the front page down with it.
         */
        fun of(slug: String?) = entries.firstOrNull { it.slug == slug }
    }
}
