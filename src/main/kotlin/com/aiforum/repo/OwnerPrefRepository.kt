package com.aiforum.repo

import com.aiforum.web.FeedView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * The owner's persisted front-page view (V29 `owner_pref`, plan_docs/ambient-slice-6.md §2.3).
 *
 * **One global row.** Single-user PoC, no auth by design, so "per owner" is `id = 1` and the owner is
 * implicit — the `thread_read` precedent (V2), keyed by the thing rather than by a user. `CHECK (id = 1)`
 * in the DDL means a second preference is unrepresentable, not merely never written by this class.
 *
 * **Absence is the default, and no method here ever creates a row to say so** (D4). A forum nobody has
 * touched the toggle on has an empty table, which is what lets every pre-S6 front-page scenario keep its
 * Gherkin and what makes the acceptance reset hook's `DELETE` restore the default rather than a value.
 *
 * **There is no String door.** [setFeedView] takes [FeedView], so an unknown view cannot be written from
 * anywhere above this class — the endpoint's `FeedView.of` refusal and V29's CHECK are the second and
 * third layers, in that order of preference. That is also why the Tier-1 test reaches past this class
 * with raw SQL: the DDL layer has to be shown standing on its own.
 *
 * **Why it imports from `com.aiforum.web`.** [FeedView] is view vocabulary, and this is the one repository
 * that stores a view choice; the alternative — a String column parameter with the enum resolved above —
 * is precisely the String door the layering would have bought at the cost of the guarantee.
 */
@Repository
class OwnerPrefRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    /**
     * The view the front page should render.
     *
     * Falls back to [FeedView.DEFAULT] on an absent row (the normal case — nobody has chosen yet) **and**
     * on a row whose slug parses to nothing. The second branch is not defensive noise: a preference the
     * code cannot read is still a value the DDL let in under some older CHECK list, or a hand-edited DB,
     * and neither is a reason to 500 the only page the forum has.
     */
    fun feedView(): FeedView = FeedView.of(storedSlug()) ?: FeedView.DEFAULT

    /**
     * Remember [view] as the owner's choice.
     *
     * An upsert rather than a read-then-write: the row is identified by a literal `1`, so "already there"
     * is the normal case after the very first click, and `ON CONFLICT … DO UPDATE` makes that one
     * statement with no race and no reliance on catching a constraint violation ([ThreadReadRepository]'s
     * `markRead` shape). `updated_at` comes from the injected [Clock] like every other stamp in the
     * repository layer — nothing reads it today, but a preference with no idea when it was set is the
     * kind of row that becomes unanswerable exactly when someone asks.
     */
    fun setFeedView(view: FeedView) {
        jdbc.update(
            """INSERT INTO owner_pref(id, feed_view, updated_at) VALUES (1,?,?)
               ON CONFLICT(id) DO UPDATE SET feed_view = excluded.feed_view, updated_at = excluded.updated_at""",
            view.slug, clock.instant().toString(),
        )
    }

    /** The stored slug, or null when the owner has never chosen. `query(…).firstOrNull()` rather than
     *  `queryForObject`, because "no row" is the designed state and must read as null, never throw. */
    private fun storedSlug(): String? =
        jdbc.query(
            "SELECT feed_view FROM owner_pref WHERE id = 1",
            { rs, _ -> rs.getString("feed_view") },
        ).firstOrNull()
}
