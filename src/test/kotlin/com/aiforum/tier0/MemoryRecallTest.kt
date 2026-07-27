package com.aiforum.tier0

import com.aiforum.persona.MemoryRecall
import com.aiforum.repo.PersonaMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: [MemoryRecall], deterministic retrieval over one member's records
 * (plan_docs/persona-memory.md §2.7). The properties under guard, each its own test: matching is
 * whole-word and BINARY (an overlap count exists only transiently — more matches never rank
 * higher), the ordering is a clock with an id tiebreak, the associative hop pulls exactly one
 * parent and resolves it ONLY among `kind='record'` rows (the root can never ride the hop — §2.2's
 * construction, the review's blocking-finding fix), and the caps hold: ≤3 matched, ≤5 total.
 *
 * Fixtures build [PersonaMemory] rows directly — including, for the hop-filter case, a root row
 * and a root-parented record no repository would ever produce: the point of that test is that even
 * a row smuggled in below the repository cannot drag the root into a prompt.
 */
@Tag("tier0")
class MemoryRecallTest {

    private fun record(
        id: String,
        body: String,
        createdAt: String = "2026-01-01T12:00:00Z",
        parentId: String? = null,
        kind: String = "record",
    ) = PersonaMemory(
        id = id, personaId = "sol", parentId = parentId, kind = kind,
        body = body, source = "owner", createdAt = createdAt,
    )

    @Test
    fun `a record surfaces when one of its words appears whole in the context text`() {
        val memory = record("m1", "Watched checkpoint tuning eat a whole weekend once")
        val selected = MemoryRecall.select(listOf(memory), "The checkpoint is what stalls everyone here")
        assertEquals(listOf("m1"), selected.map { it.id })
    }

    @Test
    fun `no shared word means empty - zero, not fewer`() {
        val memory = record("m1", "Prefers boring rollout habits over clever ones")
        assertEquals(
            emptyList<PersonaMemory>(),
            MemoryRecall.select(listOf(memory), "The checkpoint is what stalls everyone here"),
        )
        assertEquals(emptyList<PersonaMemory>(), MemoryRecall.select(emptyList(), "any text at all"))
    }

    @Test
    fun `matching is whole-word, not substring`() {
        // "concat" inside "concatenate" is the SQL-LIKE failure the matcher exists to block — a
        // six-code-point word, so the word floor cannot be what saves this case.
        val memory = record("m1", "Prefers concat helpers everywhere")
        assertEquals(
            emptyList<PersonaMemory>(),
            MemoryRecall.select(listOf(memory), "we concatenate strings here"),
            "a record word must not match inside a larger context word",
        )
        // And the reverse direction: a record word does not match a PREFIX of it in context.
        val prefix = record("m2", "The sqlite3 shell has sharp corners")
        assertEquals(
            emptyList<PersonaMemory>(),
            MemoryRecall.select(listOf(prefix), "sqlite is the topic today"),
        )
    }

    @Test
    fun `matching is script-aware, the WholeWords cases carried`() {
        val accents = record("m1", "Regrets one naïve migration plan")
        assertEquals(
            listOf("m1"),
            MemoryRecall.select(listOf(accents), "a naïve approach, again").map { it.id },
        )
        assertEquals(
            emptyList<PersonaMemory>(),
            MemoryRecall.select(listOf(accents), "naïveté is different"),
            "a Latin continuation glues the edge - no whole word, no match",
        )
    }

    @Test
    fun `words under five code points never key a match`() {
        // Every word here is under the floor ("eat" 3, "once" 4, "a" 1) even though the context
        // contains them verbatim — the crude stopword rule, deterministic side pinned.
        val short = record("m1", "eat a once")
        assertEquals(
            emptyList<PersonaMemory>(),
            MemoryRecall.select(listOf(short), "eat a once more time"),
        )
        // Five code points is IN: "whole" is the shortest word that may key a resurfacing.
        val five = record("m2", "whole days lost")
        assertEquals(
            listOf("m2"),
            MemoryRecall.select(listOf(five), "a whole day of this").map { it.id },
        )
    }

    @Test
    fun `matching is binary - many overlapping words never outrank one`() {
        // The old record shares THREE words with the context; the three newer ones share one each.
        // If the transient count leaked into selection, "rich" would displace a newer record. It
        // must not: the count is discarded, the clock decides.
        val rich = record("old", "checkpoint stalls follow checkpoint tuning into checkpoint docs", "2026-01-01T09:00:00Z")
        val n1 = record("n1", "Checkpoint defaults feel untrustworthy", "2026-01-01T10:00:00Z")
        val n2 = record("n2", "Tuning sessions swallow whole evenings", "2026-01-01T11:00:00Z")
        val n3 = record("n3", "Stalls teach patience the hard way", "2026-01-01T12:00:00Z")
        val selected = MemoryRecall.select(
            listOf(rich, n1, n2, n3),
            "checkpoint tuning stalls, and the docs are silent",
        )
        assertEquals(listOf("n3", "n2", "n1"), selected.map { it.id })
    }

    @Test
    fun `over three matches keep the newest three, ids breaking a shared instant`() {
        val t = "2026-01-01T12:00:00Z"
        val a = record("a", "checkpoint memory one", t)
        val b = record("b", "checkpoint memory two", t)
        val c = record("c", "checkpoint memory three", t)
        val older = record("d", "checkpoint memory four", "2026-01-01T09:00:00Z")
        val selected = MemoryRecall.select(listOf(c, a, older, b), "checkpoint talk")
        // Same instant → ascending id is the pinned tiebreak; the genuinely older row drops first.
        assertEquals(listOf("a", "b", "c"), selected.map { it.id })
    }

    @Test
    fun `the newest-three cut compares parsed instants, not ISO strings - the whole-second anomaly`() {
        // Instant.toString() prints NO fraction on a whole second, and 'Z' (0x5A) beats '.'
        // (0x2E) in a byte compare — so the fraction-less stamp below, chronologically the
        // OLDEST of the four, sorts lexicographically as the newest. Under string comparison
        // the cut keeps it and drops s1, a genuinely newer record, at the MAX_MATCHED boundary
        // (the S4b anomaly the scribe's isAfter dodges; the close-out audit's §10.3 item 2).
        // Every stamp here is inside one second: only instant parsing tells them apart honestly.
        val whole = record("w", "checkpoint memory whole", "2026-01-01T10:00:00Z")
        val s1 = record("s1", "checkpoint memory one", "2026-01-01T10:00:00.100Z")
        val s2 = record("s2", "checkpoint memory two", "2026-01-01T10:00:00.200Z")
        val s3 = record("s3", "checkpoint memory three", "2026-01-01T10:00:00.300Z")
        val selected = MemoryRecall.select(listOf(whole, s1, s2, s3), "checkpoint talk")
        assertEquals(listOf("s3", "s2", "s1"), selected.map { it.id }, "the whole-second stamp is the oldest and must drop")
    }

    @Test
    fun `a record whose stamp will not parse survives the cut, and two of them tie-break by id`() {
        // The comparator's polarity, which nothing else here watches: `nullsLast` INSIDE
        // `compareByDescending` is what sorts an unparseable stamp FIRST — the scribe's degrade
        // posture, "a memory must not vanish from recall because its timestamp is malformed".
        // Flipped to `nullsFirst` it reads right at a glance and every other test in this file
        // stays green while the malformed row silently drops at the MAX_MATCHED boundary. That is
        // the S4b comparator-polarity gap this slice closed for BY_WINDOW_AGE, reopened here.
        val broken = record("bad", "checkpoint memory broken", "not-a-timestamp")
        val n1 = record("n1", "checkpoint memory one", "2026-01-01T10:00:00Z")
        val n2 = record("n2", "checkpoint memory two", "2026-01-01T11:00:00Z")
        val n3 = record("n3", "checkpoint memory three", "2026-01-01T12:00:00Z")
        assertEquals(
            listOf("bad", "n3", "n2"),
            MemoryRecall.select(listOf(n1, n2, broken, n3), "checkpoint talk").map { it.id },
            "the malformed stamp is kept as the newest; the genuinely oldest record drops",
        )
        // Two unparseable stamps are ordered by id, not by input order — deterministic, so the
        // prompt text is byte-stable across runs even in the degraded case.
        val alsoBroken = record("also", "checkpoint memory also broken", "")
        assertEquals(
            listOf("also", "bad", "n3"),
            MemoryRecall.select(listOf(n1, n2, broken, n3, alsoBroken), "checkpoint talk").map { it.id },
        )
    }

    @Test
    fun `NEWEST_FIRST is exported, and the scribe's letter cut leans on this exact ordering`() {
        // The comparator is public because MemoryScribeService sorts with it before taking the
        // letter alphabet; a second comparator over PersonaMemory is how two cuts come to disagree
        // about which row is newest. Pinned on the comparator ITSELF, not only through select(),
        // so the shared contract has a test of its own.
        val whole = record("w", "whole second", "2026-01-01T10:00:00Z")
        val sub = record("s", "sub second", "2026-01-01T10:00:00.100Z")
        val broken = record("b", "unparseable", "not-a-timestamp")
        assertEquals(
            listOf("b", "s", "w"),
            listOf(whole, sub, broken).sortedWith(MemoryRecall.NEWEST_FIRST).map { it.id },
            "unparseable first (so any take keeps it), then the truly newest - never the string order",
        )
    }

    @Test
    fun `a surfaced record drags its antecedent in even when the antecedent matches nothing`() {
        val parent = record("p", "Fell down a fsync rabbit hole two winters back")
        val child = record("c", "Checkpoint defaults still feel untrustworthy", parentId = "p")
        val selected = MemoryRecall.select(listOf(parent, child), "My checkpoint config keeps misbehaving")
        assertEquals(listOf("c", "p"), selected.map { it.id }, "the chain surfaces together, child first")
    }

    @Test
    fun `the hop is one hop, never a walk`() {
        // Grandparent ← parent ← child; only the child matches. The parent rides the hop; the
        // grandparent stays dormant (the deferred-aspiration non-goal, held as behaviour).
        val grand = record("g", "Started with one broken laptop drive")
        val parent = record("p", "Fell down a fsync rabbit hole afterwards", parentId = "g")
        val child = record("c", "Ended up distrusting checkpoint defaults", parentId = "p")
        val selected = MemoryRecall.select(listOf(grand, parent, child), "checkpoint questions again")
        assertEquals(listOf("c", "p"), selected.map { it.id })
    }

    @Test
    fun `the hop resolves parents only among record rows - a root-parented row never pulls the root`() {
        // §2.2's construction half, the review's blocking finding: this row shape (a record whose
        // parent is the root) is unbuildable through the repository — the fixture forges it to
        // prove that even hand SQL below the belt cannot drag the root into a prompt. The root is
        // also never a match candidate itself, however well its words fit.
        val root = record("r", "Grew up fixing checkpoint machinery on the farm", kind = "root")
        val smuggled = record("c", "Checkpoint defaults still feel untrustworthy", parentId = "r")
        val selected = MemoryRecall.select(listOf(root, smuggled), "checkpoint trouble, as usual")
        assertEquals(listOf("c"), selected.map { it.id }, "the root must ride neither the match nor the hop")
    }

    @Test
    fun `a shared antecedent is pulled once`() {
        val parent = record("p", "Fell down a fsync rabbit hole two winters back")
        val c1 = record("c1", "Checkpoint defaults still feel untrustworthy", "2026-01-01T11:00:00Z", parentId = "p")
        val c2 = record("c2", "Checkpoint docs read like fiction", "2026-01-01T12:00:00Z", parentId = "p")
        val selected = MemoryRecall.select(listOf(parent, c1, c2), "checkpoint complaints")
        assertEquals(listOf("c2", "p", "c1"), selected.map { it.id }, "dedup by id, order deterministic")
    }

    @Test
    fun `the total is capped at five even when three matches bring three parents`() {
        val p1 = record("p1", "Old tale of drives one")
        val p2 = record("p2", "Old tale of drives two")
        val p3 = record("p3", "Old tale of drives three")
        val m1 = record("m1", "checkpoint story alpha", "2026-01-01T12:00:00Z", parentId = "p1")
        val m2 = record("m2", "checkpoint story beta", "2026-01-01T11:00:00Z", parentId = "p2")
        val m3 = record("m3", "checkpoint story gamma", "2026-01-01T10:00:00Z", parentId = "p3")
        val selected = MemoryRecall.select(listOf(p1, p2, p3, m1, m2, m3), "checkpoint stories")
        assertEquals(5, selected.size, "≤3 matched + parents, ≤5 records total")
        assertEquals(listOf("m1", "p1", "m2", "p2", "m3"), selected.map { it.id })
        assertTrue(selected.none { it.id == "p3" }, "the cap drops from the end, deterministically")
    }
}
