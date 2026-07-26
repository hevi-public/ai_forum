package com.aiforum.repo

import com.aiforum.persona.Interests
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * One mutable interest a persona currently holds (V27 `persona_interest`, plan_docs/ambient-slice-4b.md
 * D1). [interest] is a short PROSE PHRASE ("boring technology choices"), never a tag and never a number:
 * tags are what `abilities` are, and `AmbientGate.relevance` *counts* ability tags, so a model-written tag
 * would be a model writing its own airtime. The V27 CHECKs make both halves of that a database fact — the
 * digit refusal and the 2–80 length bound — rather than a parser promise.
 *
 * [source] is provenance and is read *before* any spend: [PersonaInterestRepository.SOURCE_OWNER] is a
 * permanent skip (D3), so a member whose every phrase the owner typed costs zero LLM calls. It is the same
 * contract `persona_stance.source` carries one object over, and it is per interest rather than per member
 * on purpose — that is what makes the immutable core genuinely PER-PERSONA (requirements §6.2): Sol may pin
 * a phrase Mira leaves open.
 *
 * [updatedAt] stays a raw ISO-8601 [String] rather than an `Instant` for the same reason [Stance.updatedAt]
 * does: the column is TEXT under SQLite's dynamic typing, and callers only display it or compare it
 * verbatim. Note what this row deliberately has NO room for — no strength, no how-long-held, no rank. A
 * magnitude attached to what a member is into is comparable, therefore rankable, therefore optimisable
 * (V27 header).
 */
data class Interest(
    val personaId: String,
    val interest: String,
    val source: String,
    val updatedAt: String,
)

/**
 * The mutable half of a persona's character: the phrases it is currently into
 * (plan_docs/ambient-slice-4b.md). Shaped like [RelationStanceRepository] — plain `JdbcTemplate` + injected
 * [Clock] (no `Instant.now()`, so a fixed test clock makes `updated_at` assertable).
 *
 * Four properties callers depend on:
 * - **Every read is explicitly ordered**, by `interest` for one member and by `(persona_id, interest)` for
 *   the whole set. These rows are rendered into a generation prompt (D7), and prompt text must be
 *   byte-stable across runs or an unrelated insertion silently rewrites a prompt; rowid order would drift
 *   with every drift.
 * - **There is no aggregate of any kind** — no count, no per-member tally, no "how many members hold this".
 *   [sharedInterests] hands back rows and lets a pure Tier-0 function decide what "shared" means, because a
 *   `HAVING COUNT(*) > ?` here would be a number about members living in SQL, one refactor from being
 *   persisted, compared and ranked (V27 header, D12).
 * - **Writing a phrase and judging a member are separate statements.** [upsert]/[delete] own the phrases
 *   and their provenance; [markJudged] owns the V27 `interests_judged_at` watermark, and neither touches
 *   the other's column — see [markJudged] for what breaks when they merge.
 * - **The per-member ceiling (`max-interests`) is NOT enforced here.** SQLite cannot express "at most four
 *   rows per persona_id" in a CHECK, so the invariant is kept by the swap-only write path (one DROP per
 *   TAKE, D6), by the controller's full-set guard (D11) and by acceptance coverage. A caller that inserts
 *   without deleting will get a fifth row from this class without complaint.
 */
@Repository
class PersonaInterestRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val mapper = RowMapper { rs, _ ->
        Interest(
            personaId = rs.getString("persona_id"),
            interest = rs.getString("interest"),
            source = rs.getString("source"),
            updatedAt = rs.getString("updated_at"),
        )
    }

    /**
     * What every read selects, in [mapper] order — and, unlike [RelationStanceRepository], also exactly what
     * [upsert] writes. There is no shorter write list to keep here because the judgment watermark lives on
     * `persona` (V27's ALTER) rather than on this table, so no statement in this class can reach it by
     * accident; [markJudged] names the other table explicitly.
     */
    private val columns = "persona_id, interest, source, updated_at"

    /**
     * Everything [personaId] is currently into, ordered by phrase. The ordering is load-bearing rather than
     * tidy: this is the list [com.aiforum.persona.InterestProse] renders into the member's prompt, and it
     * is also the list the drift parse checks a claimed TAKE against, so an unstable order would mean two
     * runs handed the same member two different prompts for no reason.
     *
     * `ORDER BY interest` sorts under the column's declared NOCASE collation (V27), so it agrees with the
     * case-insensitive identity the PRIMARY KEY enforces — "Storage engines" cannot sort away from
     * "storage engines" while being the same row.
     */
    fun of(personaId: String): List<Interest> =
        jdbc.query(
            "SELECT $columns FROM persona_interest WHERE persona_id = ? ORDER BY interest",
            mapper, personaId,
        )

    /**
     * Just the phrases [personaId] holds, in [of]'s order — the shape the prompt renderer and the parse's
     * already-held check both want, neither of which has any business knowing which phrases are pinned (D7:
     * a model that could see its own protected phrases would have a lever).
     *
     * Deliberately derived from [of] rather than given its own SELECT: two statements would be two ORDER BY
     * clauses free to disagree, and the disagreement would show up as a prompt that names the member's
     * interests in one order and refuses a TAKE by another.
     */
    fun phrasesOf(personaId: String): List<String> = of(personaId).map { it.interest }

    /**
     * Write [interest] for [personaId] with provenance [source], replacing whatever provenance that phrase
     * already carried. Upsert rather than insert-or-update-by-query because the phrase is identified by its
     * (persona_id, interest) PRIMARY KEY, so re-writing an existing phrase is the normal case — seeding
     * runs on every startup, and the owner's edit form resubmits the phrases already on the member.
     * `ON CONFLICT … DO UPDATE` makes that one statement: no read-then-write race, and no reliance on a
     * caught constraint violation.
     *
     * [source] overwrites the stored provenance deliberately — an owner typing a phrase a seed authored
     * must leave the row marked owner-authored, which is the whole mechanism of pinning (D11).
     *
     * The conflicting row's `interest` text is deliberately NOT rewritten, so a phrase keeps the casing it
     * was created with. Under the NOCASE key, `SET interest = excluded.interest` would rewrite the very
     * column that matched, which is both pointless and the one edit that could make the stored key disagree
     * with what a caller believes it wrote.
     *
     * Three inputs are rejected by the V27 CHECKs as a thrown `DataAccessException`, never a silent no-op:
     * a [source] outside the three constants below, a phrase outside 2–80 trimmed characters, and — for
     * every source except `owner` — a phrase containing a digit. The owner path must therefore validate
     * with `Interests.validate` *before* calling this (D11), because these writes run before the prompt
     * logic in `PersonaController.edit` and an exception here would cost the owner their descriptor and
     * dial edits too.
     *
     * **This statement must never touch `interests_judged_at`** — see [markJudged].
     *
     * The phrase is put through [Interests.clean] HERE, at the one door every writer comes through,
     * rather than trusted from each caller. NOCASE folds case and nothing else, so `" agents"` and
     * `"agents"` are two distinct primary keys: a caller that skipped cleaning would hand the member a
     * duplicate the DDL cannot see, which quietly breaks the one-for-one count invariant the whole
     * design rests on — the same leak NOCASE was chosen to close for casing. The drift path already
     * cleans (its verdict carries a cleaned phrase); the owner's form and the seeder are the paths that
     * would otherwise carry raw textarea and YAML whitespace straight into the key.
     */
    fun upsert(personaId: String, interest: String, source: String) {
        jdbc.update(
            """INSERT INTO persona_interest($columns) VALUES (?,?,?,?)
               ON CONFLICT(persona_id, interest) DO UPDATE SET
                   source     = excluded.source,
                   updated_at = excluded.updated_at""",
            personaId, Interests.clean(interest), source, clock.instant().toString(),
        )
    }

    /**
     * Drop one phrase from one member — the DROP half of a swap (D6), and the owner blanking a field (D11).
     * One phrase only: the member's other interests are independent and must survive. No-op if the member
     * does not hold it. Matching is case-insensitive by the column's NOCASE collation, so a caller that
     * echoes back a phrase in the casing a model wrote still deletes the row that is actually there.
     */
    fun delete(personaId: String, interest: String) {
        // Cleaned on the way in for the same reason [upsert] cleans: the stored key is the cleaned
        // phrase, so a caller echoing back raw text (the owner blanking a prefilled field, a model's
        // spelling with stray whitespace) would miss the row and silently leave the member holding it.
        jdbc.update(
            "DELETE FROM persona_interest WHERE persona_id = ? AND interest = ?",
            personaId, Interests.clean(interest),
        )
    }

    /** Every interest in the room, ordered by (persona, phrase) — the admin overview and the seeding check. */
    fun findAll(): List<Interest> =
        jdbc.query("SELECT $columns FROM persona_interest ORDER BY persona_id, interest", mapper)

    /**
     * When the drift pass last *looked at* [personaId] (V27 `persona.interests_judged_at`), or null when it
     * never has — which reads as "judge this member over all of their history, once".
     *
     * The column lives on `persona` but is read and written only here, and that placement is deliberate:
     * `PersonaRepository.update` names every owner-authored column in one statement, so a watermark carried
     * there would be stamped by an owner pressing Save on the persona form. This is a read on another
     * table, not a layering slip.
     *
     * A `query(…).firstOrNull()` rather than `queryForObject`, because an unknown id must read as null (the
     * never-judged answer) instead of throwing — the pass iterates the roster it just loaded, and a member
     * deleted in between is a race, not a bug. The mapper's type argument is spelled `String?` on purpose:
     * a NULL column read through a platform-typed mapper is the one shape where the compiler may insert a
     * not-null assertion, and an NPE here would turn the never-judged answer into a broken run.
     */
    fun judgedAt(personaId: String): String? =
        jdbc.query(
            "SELECT interests_judged_at FROM persona WHERE id = ?",
            RowMapper<String?> { rs, _ -> rs.getString("interests_judged_at") },
            personaId,
        ).firstOrNull()

    /**
     * Move [personaId]'s judgment watermark to [at], or clear it when [at] is null.
     *
     * This is the V26 lesson applied from day one instead of after a review (V27 header). `interest_change`
     * records only what MOVED, and "nothing moved" is the designed steady state here — more so than for
     * stances, because most members most weeks write nothing that would move them. Tracking changes alone
     * would leave every settled member re-buying the same LLM judgment on every run, forever. So the caller
     * stamps this on any *usable* verdict — Drifted and Unchanged alike — and deliberately leaves it alone
     * after a rejected answer or a seam failure, both of which left the evidence genuinely unjudged and
     * both of which deserve another look (D6).
     *
     * **[at] is passed in rather than read from the injected [Clock] here**, unlike [upsert]'s `updated_at`,
     * and that is load-bearing: the watermark must be the instant the evidence window was *read*, not the
     * later instant the row was written. A clock read inside this method would sit after the judgment call
     * — a minute of LLM latency later — and anything the member posted in that gap would fall behind a
     * watermark that never saw it. Vanishingly rare, permanently invisible, exactly the class of bug the
     * per-member window exists to prevent.
     *
     * Null CLEARS rather than being ignored, because that is what a revert needs: the member whose drift the
     * owner undid must be judgeable again from the very engagements that produced it (D10 — revert undoes,
     * it does not freeze).
     *
     * **Nothing else may write this column.** [upsert] and [delete] name only `persona_interest`, and
     * `PersonaRepository.update` deliberately does not name `interests_judged_at` at all. Folding it into
     * either is the kind of symmetry a later refactor calls a tidy-up, and it would break two things
     * quietly: an owner pinning a phrase on the edit form would declare the member freshly judged, muting
     * drift until brand-new engagement arrived (the owner asked to fix one phrase, not for silence); and the
     * revert path, which upserts the dropped phrase back, would re-stamp the very watermark the revert is
     * meant to move backwards. Unknown ids are a no-op — a member with no row has nothing to judge.
     */
    fun markJudged(personaId: String, at: String?) {
        jdbc.update("UPDATE persona SET interests_judged_at = ? WHERE id = ?", at, personaId)
    }

    /**
     * Every phrase in the room mapped to the ids of the members holding it, phrases in order and ids in
     * order within each phrase — the raw material for the /admin/interests room map (D12).
     *
     * **Rows, not a reduction, and the name is about the question rather than a filter.** This returns every
     * phrase including the ones exactly one member holds; deciding which of them count as *shared* (more
     * than half the roster) or *sole* (exactly one) is `TopicSpread.of`'s pure Tier-0 job. The split is the
     * guardrail, not indirection: `HAVING COUNT(*) > ?` would put a number about members into SQL, where it
     * is one "let's also store it" away from being a persisted, comparable, rankable score — and the whole
     * point of the readout is that its subject is a phrase and the members who hold it, never a magnitude
     * attached to a member (V27 header, §2.12).
     *
     * **Ids, not display names.** The caller renders names, and it already holds the roster it needs to map
     * them; a `JOIN persona` here would make the repository's return value a display concern and make the
     * one member with no interests invisible to a *count* the readout must never take anyway.
     *
     * Phrases are folded case-insensitively, agreeing with the NOCASE identity within a member (V27): two
     * members can legitimately hold "Agents" and "agents", and if those read as two phrases the room map
     * would show convergence hiding behind a capital letter — the one thing this readout exists to make
     * visible. The surviving key is the casing of the lowest-ordered holder, which the `ORDER BY` makes
     * deterministic. The linear key scan is deliberate at this size (a roster of seven times four phrases)
     * and stays honest: nothing here counts anything.
     */
    fun sharedInterests(): Map<String, List<String>> {
        val rows = jdbc.query(
            """SELECT interest, persona_id FROM persona_interest
               ORDER BY interest, persona_id""",
            { rs, _ -> rs.getString("interest") to rs.getString("persona_id") },
        )
        val holders = LinkedHashMap<String, MutableList<String>>()
        rows.forEach { (interest, personaId) ->
            val key = holders.keys.firstOrNull { it.equals(interest, ignoreCase = true) } ?: interest
            holders.getOrPut(key) { mutableListOf() } += personaId
        }
        return holders
    }

    companion object {
        /** Written by the startup seeder from the hand-authored persona config — the pass may replace it. */
        const val SOURCE_SEEDED = "seeded"

        /** Typed by the owner on the persona form: a PERMANENT skip, decided before any LLM call (D3). */
        const val SOURCE_OWNER = "owner"

        /** Taken up by the drift pass itself — subject to the digit CHECK, and free to drift again later. */
        const val SOURCE_DRIFTED = "drifted"
    }
}
