package com.aiforum.tier1.infra

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Tier-1: proves Flyway actually *upgrades an existing older database forward*, not just builds an empty
 * one from scratch (which is all the from-scratch context-load boot exercises). We migrate a temp DB to
 * an intermediate version (V3 — before persona.model/slug/color_index existed), seed rows on that old
 * schema, then migrate to latest and assert the old data survived and the new columns picked up their
 * migration DEFAULT / backfill. This is the guarantee long-term prod data depends on every schema bump.
 */
@Tag("tier1")
class MigrationPipelineTest {

    private fun flyway(url: String, target: String?) =
        Flyway.configure()
            .dataSource(url, null, null)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
            .load()

    @Test
    fun `migrates an older database forward, preserving data and backfilling new columns`(@TempDir tmp: Path) {
        val url = "jdbc:sqlite:${tmp.resolve("aiforum.db")}"

        // 1. Bring the DB up to the intermediate V3 schema only.
        flyway(url, "3").migrate()

        // 2. Seed two persona rows against the OLD (pre-V4/V5/V6) schema — no model/slug/color columns yet.
        //    Also seed a title-only thread to prove it survives the body-adding bumps (V7/V8).
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO persona (id, name, handle, system_prompt) VALUES " +
                        "('Ada', 'Ada', 'ada', 'You are Ada.'), " +
                        "('Bob', 'Bob', 'bob', 'You are Bob.')",
                )
                st.executeUpdate(
                    "INSERT INTO thread (id, title, created_at) VALUES ('T1', 'Old thread', '2026-01-01T00:00:00Z')",
                )
                // A comment on the old schema (pre-V9, no `starred` column) — proves V9's NOT NULL
                // DEFAULT 0 leaves existing comments unstarred after the upgrade.
                st.executeUpdate(
                    "INSERT INTO comment (id, thread_id, author_id, body, state, depth, created_at) VALUES " +
                        "('C1', 'T1', 'Ada', 'Old comment', 'POSTED', 0, '2026-01-01T00:00:00Z')",
                )
            }
        }

        // 3. Upgrade the EXISTING db to the latest schema (Flyway applies the pending V4–V29).
        flyway(url, null).migrate()

        // 4. The old rows survived, and the new columns carry their migration default / backfill.
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, model, slug, color_index, abilities, dials FROM persona ORDER BY rowid").use { rs ->
                    rs.next()
                    assertEquals("Ada", rs.getString("id"), "the pre-existing row must survive the upgrade")
                    assertEquals("", rs.getString("model"), "V4 DEFAULT '' applies to the pre-existing row")
                    // V5's DEFAULT '' applied here, but V16 (the last migration in this run) deduped the
                    // two empty slugs by falling them back to their unique id — asserted in detail below.
                    assertEquals("Ada", rs.getString("slug"), "V16 rewrote V5's empty slug to the unique id")
                    assertEquals(0, rs.getInt("color_index"), "V6 backfills colour slots in rowid order")
                    assertEquals("[]", rs.getString("abilities"), "V10 DEFAULT '[]' applies to the pre-existing row")
                    assertEquals("{}", rs.getString("dials"), "V10 DEFAULT '{}' applies to the pre-existing row")
                    rs.next()
                    assertEquals("Bob", rs.getString("id"))
                    assertEquals(1, rs.getInt("color_index"), "the second row gets the next colour slot")
                }

                // V16 enforces a UNIQUE slug. Both seeded rows reached V16 with slug = '' (V5's DEFAULT),
                // a built-in duplicate; V16's dedupe-before-index step rewrote them to distinct, non-empty
                // values (the empty-slug rows fall back to their id) so CREATE UNIQUE INDEX could apply.
                st.executeQuery("SELECT slug FROM persona ORDER BY rowid").use { rs ->
                    rs.next()
                    val adaSlug = rs.getString("slug")
                    rs.next()
                    val bobSlug = rs.getString("slug")
                    assertEquals("Ada", adaSlug, "V16 fell the empty slug back to the unique id")
                    assertEquals("Bob", bobSlug, "V16 fell the empty slug back to the unique id")
                    org.junit.jupiter.api.Assertions.assertNotEquals(
                        adaSlug, bobSlug, "V16 must leave the two rows with distinct slugs",
                    )
                }

                // The pre-existing thread survived; the canonical V7's NOT NULL DEFAULT '' gives it ''.
                // (V8's NULL→'' backfill is a no-op on this canonical chain — NULLs are only possible on the
                // legacy nullable-V7 lineage, where the column carries no NOT NULL constraint.)
                st.executeQuery("SELECT body FROM thread WHERE id = 'T1'").use { rs ->
                    rs.next()
                    assertEquals("", rs.getString("body"), "the pre-existing thread reads '' after V7/V8")
                }

                // The pre-existing comment survived; V9's NOT NULL DEFAULT 0 left it unstarred, V11's
                // nullable updated_at leaves it NULL (never edited), and V12's nullable reasoning_leak
                // reads NULL (no leak) — both new nullable columns are absent on a row that predates them.
                st.executeQuery("SELECT starred, updated_at, reasoning_leak FROM comment WHERE id = 'C1'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("starred"), "V9 DEFAULT 0 applies to the pre-existing comment")
                    assertEquals(null, rs.getString("updated_at"), "V11 leaves the pre-existing comment unedited (NULL)")
                    assertEquals(null, rs.getString("reasoning_leak"), "V12's nullable column reads NULL for the pre-existing comment")
                }

                // The pre-existing thread is likewise unedited after V11.
                st.executeQuery("SELECT updated_at FROM thread WHERE id = 'T1'").use { rs ->
                    rs.next()
                    assertEquals(null, rs.getString("updated_at"), "V11 leaves the pre-existing thread unedited (NULL)")
                }

                // V27's nullable ALTER, asserted on a row that predates it — which is this class's whole
                // point. NULL means "the interest drift pass has never looked at this member", i.e. judge
                // them over all of their history, once. A NOT NULL DEFAULT here (the only other shape
                // ADD COLUMN offers) would have declared every pre-existing member freshly judged at upgrade
                // time and muted drift for them until brand-new engagement arrived.
                st.executeQuery("SELECT interests_judged_at FROM persona WHERE id = 'Ada'").use { rs ->
                    rs.next()
                    assertEquals(
                        null, rs.getString("interests_judged_at"),
                        "V27's nullable column reads NULL for the pre-existing persona",
                    )
                }

                // V28's nullable ALTER, same argument one slice on: NULL means "the memory scribe has
                // never looked at this member" — bounded by the 90-day evidence horizon rather than an
                // all-time read (plan_docs/persona-memory.md §2.6). A NOT NULL DEFAULT here would have
                // declared every pre-existing member freshly consolidated at upgrade time and muted the
                // pass for them until brand-new engagement arrived.
                st.executeQuery("SELECT memory_judged_at FROM persona WHERE id = 'Ada'").use { rs ->
                    rs.next()
                    assertEquals(
                        null, rs.getString("memory_judged_at"),
                        "V28's nullable column reads NULL for the pre-existing persona",
                    )
                }

                // V29 adds a NEW TABLE rather than a column, and the house question for that shape is what
                // it reads as on a database that already existed: empty. `owner_pref` ships NO seed row on
                // purpose (plan_docs/ambient-slice-6.md D4) — absence IS FeedView.DEFAULT — so an upgraded
                // forum opens on the thread-card view exactly like a fresh one, and every pre-S6 front-page
                // scenario keeps its Gherkin. A seed row here would be this migration quietly choosing a
                // view for a forum whose owner never touched the toggle.
                st.executeQuery("SELECT COUNT(*) AS n FROM owner_pref").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("n"), "V29 seeds no preference row — absence is the default view")
                }

                // V30 is the same new-table shape, and reads the same way on an upgraded forum: empty. It
                // is the tool-call trace (issue #15), so "empty" is also the honest account of every turn
                // that ran BEFORE this migration existed — there is nothing to backfill, because the data
                // was never captured. A seeded or inferred row here would be inventing history.
                st.executeQuery("SELECT COUNT(*) AS n FROM generation_tool_call").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("n"), "V30 seeds nothing — pre-#15 turns genuinely have no trace")
                }

                // flyway_schema_history records the full V1..V30 chain as applied (V20 thread.author_id +
                // V21 ambient_run landed with the ambient loop; V22 added ambient_run.action for S2's
                // comment action; V23 added the article_seen dedupe registry for S5's feed source,
                // plan_docs/ambient-slice-5.md; V24 added persona_stance, S3's qualitative relation graph,
                // plan_docs/ambient-slice-3.md; V25 added stance_change, S4a's append-only audit trail for
                // the stances V24 introduced — the only control on a pass that auto-applies,
                // plan_docs/ambient-slice-4a.md; V26 added persona_stance.judged_at, the per-edge
                // last-judged watermark, because an "unchanged" judgment is the steady state of a settled
                // pair and writes no audit row by design — so the audit table alone left that pair buying
                // an LLM judgment on every run, forever; V27 added persona_interest, interest_change and
                // persona.interests_judged_at — S4b's mutable interests with per-interest provenance, their
                // append-only audit trail, and the per-member last-judged watermark, which V26's lesson made
                // part of the first commit here rather than a post-review fix,
                // plan_docs/ambient-slice-4b.md; V28 added persona_memory (the private per-member memory
                // tree with its composite same-persona parent FK and owner-only root), memory_change (the
                // append-only audit trail carrying the pre-query read_at) and persona.memory_judged_at —
                // the per-member consolidation watermark, plan_docs/persona-memory.md; V29 added owner_pref
                // — S6's one-global-row persisted front-page view, seedless so that absence is the default
                // — plus the two partial `state='POSTED'` comment indexes the feed's reads want, neither of
                // which V17's (thread_id, depth, created_at) can serve, plan_docs/ambient-slice-6.md; V30
                // added generation_tool_call — issue #15's audit trail for the tools a generation reached
                // for, the sibling of the stance/interest/memory audit tables, deliberately FK-free on
                // run_id so an UNSAVEABLE turn's trace survives with a NULL comment_id; a turn that died
                // at the LLM seam records nothing at all, which V30's header now states rather than
                // overclaims).
                // Bump this with every migration — it is the check that a new migration actually RUNS
                // against an old database rather than only a fresh one.
                st.executeQuery("SELECT MAX(CAST(version AS INTEGER)) AS v FROM flyway_schema_history").use { rs ->
                    rs.next()
                    assertEquals(30, rs.getInt("v"), "the latest migration (V30) should be recorded as applied")
                }
            }
        }
    }
}
