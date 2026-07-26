package com.aiforum.service

import com.aiforum.config.InterestDriftProperties
import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.GenerationState
import com.aiforum.dto.Snippet
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.persona.InterestDrift
import com.aiforum.persona.InterestDriftPrompts
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.Interest
import com.aiforum.repo.InterestChangeRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Who fired a drift pass: the owner's hand from /admin/interests (MANUAL, always available) or the gated
 * `@Scheduled` caller (SCHEDULED). A separate enum from `EvolutionSource` and `TickSource` rather than a
 * shared one, for the reason [InterestDriftProperties] has its own prefix: the three loops are
 * independently switchable, and nothing should be able to pass one loop's source into another's by
 * accident.
 *
 * **Never persisted, and specifically never as a row in `ambient_run`.** `AmbientRunRepository.count()`
 * drives the ambient tick's post/comment parity AND its round-robin author index, so an extra row per
 * drift run would silently change which member posts which article — a correctness constraint on a table
 * this pass has no business touching, not a matter of taste. The source reaches the log and nothing else.
 */
enum class DriftSource { MANUAL, SCHEDULED }

/**
 * The interest-drift pass (plan_docs/ambient-slice-4b.md): read what a member has actually been writing
 * since the pass last looked, ask the model whether that has moved the member on from one of the topics
 * it is drawn to, and swap one phrase for one — auto-applied, never queued for approval, every change
 * captured in `interest_change` so the owner can read set-down→taken-up afterwards and revert what made
 * the room worse.
 *
 * This is `StanceEvolutionService`'s shape (S4a) applied to the mutable half of a member's *character*
 * rather than to its opinion of a neighbour, which raises the stakes on four promises:
 *
 * - **The immutable core never moves (I1).** `descriptor`, `abilities`, `dials` and `system_prompt` have
 *   exactly one writer, `PersonaRepository.update`, and this class does not call it — it does not even
 *   take a `PersonaPromptRefresher`. There is no write path to bend, which is stronger than a rule about
 *   not using one, and the Tier-2 fake whose `update` fails the test if invoked is what keeps it that way.
 * - **What the owner typed never moves (I1, per member).** An interest whose `source` is
 *   [PersonaInterestRepository.SOURCE_OWNER] is skipped BEFORE any judgment, so a member whose every
 *   phrase the owner pinned is also a member this pass stops spending money on. Enforced twice —
 *   qualification and again at the judgment site (see [driftMember]) — and a third time in the parse,
 *   which refuses a verdict that merely *names* a pinned phrase.
 * - **The count never grows (I3).** One phrase down for every phrase up, in one transaction. Convergence
 *   would need displacement, and every displacement is a DROP line in the log with an undo beside it.
 * - **No number enters what a member is into (I2).** [InterestDrift.parse] refuses a digit-bearing
 *   answer at the one place a number could arrive — the model's output — and V27's CHECK backstops it.
 *   Nothing here counts, ranks or aggregates anything: members are qualified by having *produced*
 *   engagements, and the audit cites those engagements as prose plus ids, never as a tally.
 *
 * ## What one run may cost, and what holds it down
 *
 * One LLM call per member the pass actually judges, and nothing else — no recompose (D7), no second
 * pass, no dispatcher call. At the seeded roster of seven that is a worst case of seven calls per weekly
 * run, and the realistic case is lower because three skips are decided before any spend:
 *
 * - **The per-member watermark** (`persona.interests_judged_at`, V27). A member is judged only on
 *   engagements newer than the last judgment that *landed*, and a judgment lands whether it moved a
 *   phrase or left the member standing. Binding the window to the audit table alone is the cost defect
 *   S4a shipped and had to fix in `bed019fe`: the judge is instructed to answer NONE when nothing moved,
 *   "nothing moved" is the overwhelming majority verdict here by construction, and it writes no audit
 *   row — so every settled member would re-buy the same judgment every week, forever.
 * - **The three free skips come first** — below the engagement floor, no interests at all, every
 *   interest owner-pinned — all decided in [candidates], before the cap, so a skip can never eat the
 *   budget of a member that would actually have been judged.
 * - **One pass at a time.** [drift] is single-flight: the manual button runs the whole pass on the
 *   request thread with a 60s ceiling per judgment, so an owner who gives up waiting and clicks again
 *   would otherwise start a second pass over the same members — every judgment paid for twice, and a
 *   second audit row whose "before" is the first pass's "after".
 *
 * ## Ordering is load-bearing twice
 *
 * The audit row is written BEFORE the delete and the insert, and all four statements — with the
 * watermark stamp — are ONE transaction. `persona_interest` keeps no history, so a swap not captured
 * first is unrevertable: the dropped phrase and its provenance would exist nowhere in the system. But an
 * audit row committed alone is the same wound from the other side — it would claim a swap that never
 * happened AND stand as that member's history entry.
 *
 * Candidates are ordered by WINDOW AGE, oldest first, so a cap rotates. Sorting by name would give an
 * alphabetically early member that keeps coming back unchanged, refused or rate-limited a permanent
 * claim on the budget, and the members behind it would never be judged — not "later", never.
 *
 * ## What deliberately does NOT happen here
 *
 * No prompt is recomposed (D7, and the opposite of S4a's fan-out at
 * `src/main/kotlin/com/aiforum/service/StanceEvolutionService.kt:220-221`). Interests are injected into a
 * generation at settle time from this very table, never baked into `persona.system_prompt`, so a drift is
 * live on the member's next reply with no composition bought and nothing to go stale. That is why this
 * class has no composer, no refresher, and no reason to acquire one.
 */
@Service
class InterestDriftService(
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val interests: PersonaInterestRepository,
    private val changes: InterestChangeRepository,
    private val llm: LlmClient,
    // The audit row, the two interest writes and the watermark stamp are one unit of work — and so are
    // the four statements of a revert. Injected explicitly rather than annotating a method
    // `@Transactional`: the write path is a private method reached by self-invocation, and a Spring proxy
    // sees neither — the annotation would compile, read as a guarantee, and do absolutely nothing
    // (StanceEvolutionService.kt:109-113).
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val props: InterestDriftProperties,
) {

    // Never `javaClass`: the class-literal form is what LogCapture binds to, and a subclassed or proxied
    // instance would otherwise log under a name no test is listening on (D15).
    private val log = LoggerFactory.getLogger(InterestDriftService::class.java)

    /**
     * Whether a pass is in flight, for the single-flight guard in [drift]. An [AtomicBoolean] rather than
     * a lock because a second caller must NOT queue behind the first: waiting would hand the owner
     * exactly the second pass they must not get, just later.
     */
    private val running = AtomicBoolean(false)

    /**
     * One member that survived qualification: worth a judgment, with the engagements its own window
     * admits. [judgedAt] is null both for a never-judged member and for one whose stored stamp could not
     * be read — the same reading in both cases, "judge it over all of its history", which is the safe
     * direction (it costs one judgment; the other direction hides evidence forever).
     *
     * The [persona] snapshot is carried for ORDERING and for the prompt's own material (name, character).
     * It is deliberately NOT the authority on whether the member may be written — see [driftMember].
     */
    private data class Candidate(
        val persona: PersonaRepository.Persona,
        val judgedAt: Instant?,
        val evidence: List<PersonaExchange>,
    )

    /**
     * One cited engagement, decoded from an [InterestChange][com.aiforum.repo.InterestChange]'s `cited`
     * field — enough for the audit page to render a `/threads/{threadId}#reply-{commentId}` permalink
     * beside the snapshotted prose. See [renderCited] for the wire format and why the prose is stored
     * rather than re-read.
     */
    data class CitedEngagement(val commentId: String, val threadId: String, val snippet: String)

    /**
     * Run one pass and return how many members drifted. A second concurrent caller does NOTHING and
     * returns 0 — see [running]: the manual trigger is a synchronous POST over a pass with no cap by
     * default, so "the browser gave up, click it again" is the normal way a second pass starts, and until
     * the first pass stamps its watermarks the second one re-judges the very same members.
     *
     * The whole body is wrapped in `try/catch (Exception)` — narrowed from `Throwable`, so a genuine JVM
     * Error still propagates, and never `runCatching`, which catches `Throwable` and would keep the run
     * spending LLM calls on a JVM that is already broken — and it never rethrows. A rate limit at 04:30
     * on a Sunday has to be a recorded outcome rather than an unhandled scheduled-task failure. This catch
     * covers the reads that FRAME the run (roster, watermarks, engagement query); each member carries its
     * own catch below, so one member whose repository write fails costs one member rather than every
     * member still queued behind it.
     *
     * **The clock is read before the engagement query, never after.** That instant becomes the watermark
     * of every member this run judges, and the ordering matters: reading it afterwards would leave a
     * comment posted during the query behind a watermark that never saw it, and this window is the only
     * thing that decides whether an engagement is ever judged. Read first, and the worst case is an
     * engagement judged twice.
     */
    fun drift(source: DriftSource): Int {
        if (!running.compareAndSet(false, true)) {
            log.warn("event=interest.drift.skipped source={} reason=already-running", source.name.lowercase())
            return 0
        }
        var changed = 0
        log.info("event=interest.drift.start source={}", source.name.lowercase())
        try {
            val readAt = clock.instant().toString()
            // Persona-ness is decided against the ROSTER, never by the shape of an id: "owner", "system"
            // and `gh:`-prefixed GitHub authors are excluded by simply not being on it, the same call
            // ReplyTreeAssembler and StanceEvolutionService.kt:184 make. Iterating the roster rather than
            // the engagement rows is that filter: an author who is not a member can never become a
            // candidate, because nothing looks the author up.
            val roster = personas.findAll()
            // Every member's window, read ONCE before the engagement query so ordering and narrowing share
            // one consistent view. Read through PersonaInterestRepository rather than off a `Persona`
            // projection deliberately: the column's reader and its writer staying on one object is what
            // keeps `PersonaRepository.update` — which names every owner-authored column in one statement
            // — from ever being tidied into stamping it, which would mute drift on every owner save.
            val windows = roster.associate { it.id to windowOf(it.id) }
            val queue = candidates(comments.exchangesSince(coarseFloor(roster, windows)), roster, windows)
            queue.take(memberCap()).forEach { candidate ->
                // One bad member costs ONE member. `driftMember` writes (audit, delete, insert,
                // watermark), and a locked DB or a constraint violation on the third member must not
                // abandon the fourth and everything after it — the pass would look like it finished,
                // having silently done a fraction of its work. The LLM seam has its own guard inside
                // `judge`; this one is for the storage side.
                try {
                    if (driftMember(candidate, readAt)) changed++
                } catch (e: Exception) {
                    log.error(
                        "event=interest.member.failed persona={} reason={}",
                        candidate.persona.id, e.message ?: e.javaClass.simpleName, e,
                    )
                }
            }
            log.info(
                // `candidates`, not `judged`: a member can still be skipped at the judgment site by the
                // mid-pass re-read, and a finish line that claimed judgments it did not buy would make the
                // one number an operator uses to sanity-check the bill wrong in the expensive direction.
                "event=interest.drift.finished source={} candidates={} changed={}",
                source.name.lowercase(), minOf(queue.size, memberCap()), changed,
            )
        } catch (e: Exception) {
            log.error(
                "event=interest.drift.failed source={} reason={}",
                source.name.lowercase(), e.message ?: e.javaClass.simpleName, e,
            )
        } finally {
            // In a `finally`, so a fault on the framing reads cannot leave the guard latched and every
            // later pass — including the scheduled Sunday one — returning 0 for the lifetime of the JVM.
            running.set(false)
        }
        return changed
    }

    /**
     * Undo one audited swap: put the dropped phrase back with its ORIGINAL provenance, remove the phrase
     * that was taken up, stamp the audit row, and give back the window the judgment took. Returns whether
     * anything was undone — false for an unknown id or one already reverted (the `reverted_at` null IS the
     * double-revert guard, enforced again in SQL). No LLM call is made or needed: every value being
     * restored was captured at the time.
     *
     * **All four statements are ONE transaction**, unlike S4a's revert
     * (`StanceEvolutionService.kt:263-272`, which is untransacted with a characterised residual). That
     * residual was justified because each fault there left a recoverable state on a single row; this
     * revert restores TWO rows plus two stamps, and a half-restored interest set is a member holding three
     * phrases where the owner authored four — an invariant break, not an inconvenience.
     *
     * **Restoring the source is not bookkeeping.** Put the text back without it and a seeded phrase comes
     * home labelled `drifted`, so the next pass reads a lie about who wrote it. Restoring `owner` likewise
     * keeps an owner-authored phrase permanently out of this pass's reach, which is the contract the
     * column exists for.
     *
     * **Revert undoes; it does not freeze.** A reverted seeded phrase goes back to `seeded` and is free to
     * drift again — deliberately (D10). A revert that also pinned the phrase would quietly turn one
     * disagreement into a permanent opt-out; the owner who wants a topic fixed for good types it on the
     * persona form, which stamps `owner` and puts it out of reach.
     *
     * **And the window is undone with it.** The rejected judgment stamped `interests_judged_at`, so
     * leaving that stamp would keep the engagements the owner just disagreed about permanently out of
     * reach — the member would be free to drift in principle and blind to its own words in practice. The
     * watermark is therefore moved back to [InterestChangeRepository.lastStandingChangeAt], the newest
     * change that STILL stands: exactly the evidence this judgment consumed is reopened and nothing older,
     * because everything before the previous surviving change was already acted on. Null — no surviving
     * change — clears it, which reads as "judge this member over all of their history", the same state a
     * never-judged member is in. Read AFTER [InterestChangeRepository.markReverted] so the row being
     * undone no longer counts as standing; before it, the query would hand back this very change's stamp
     * and the revert would reopen nothing.
     *
     * Nothing is recomposed, because nothing went stale: the interest block is re-read from this table on
     * every generation (D7).
     */
    fun revert(changeId: Long): Boolean {
        val change = changes.find(changeId)
        if (change == null) {
            log.warn("event=interest.revert.skipped change={} reason=unknown-change", changeId)
            return false
        }
        if (change.revertedAt != null) {
            log.info("event=interest.revert.skipped change={} reason=already-reverted", changeId)
            return false
        }
        // A SUPERSEDED change cannot be undone, and undoing it half-way is worse than refusing.
        // Reverting restores `dropped` and removes `takenUp` — but if a later drift already moved
        // `takenUp` on, the delete is a no-op while the upsert still lands, so the member ENDS UP WITH
        // ONE MORE interest than it started with: A -> B, then B -> C, then revert the first change and
        // the member holds A and C. That breaks the one-for-one count invariant through the owner's own
        // control surface, and the surface offers a Revert button on every unreverted row in the log.
        // The audit row stays un-reverted and readable; what it cannot do is pretend to be undoable.
        if (interests.phrasesOf(change.personaId).none { it.equals(change.takenUp, ignoreCase = true) }) {
            log.warn(
                "event=interest.revert.skipped change={} persona={} reason=superseded",
                changeId, change.personaId,
            )
            return false
        }
        transactions.execute {
            interests.delete(change.personaId, change.takenUp)
            interests.upsert(change.personaId, change.dropped, change.droppedSource)
            changes.markReverted(changeId)
            interests.markJudged(change.personaId, changes.lastStandingChangeAt(change.personaId))
        }
        log.info(
            "event=interest.reverted change={} persona={} restored={} source={}",
            changeId, change.personaId, change.dropped, change.droppedSource,
        )
        return true
    }

    /**
     * OLDEST WINDOW FIRST — the member nobody has looked at in longest goes to the front of the queue,
     * never-judged members (null) ahead of everything, with the persona id as the deterministic tiebreak
     * so a capped run is still reproducible.
     *
     * Sorting by roster order or by name is what a cap turns into starvation: `take(cap)` takes the first
     * N, and an alphabetically early member that keeps coming back unchanged, refused or rate-limited
     * would hold the same claim on the budget on every run, so the members behind it are never judged —
     * not "later", never. Age-ordered, the cap rotates: whatever a run judges is stamped, drops to the
     * back, and the next run reaches further down.
     */
    private val byWindowAge: Comparator<Candidate> =
        compareBy<Candidate, Instant?>(nullsFirst<Instant>()) { it.judgedAt }
            .thenBy { it.persona.id }

    /**
     * The members worth judging this run, oldest window first (see [byWindowAge]).
     *
     * Evidence is the member's OWN words: engagements are grouped by `fromAuthor`, and the addressee is
     * deliberately NOT required to be a roster member. What a member wrote is evidence about that member
     * whoever it was aimed at, so dropping a reply addressed to an ingested `gh:` author would hide real
     * evidence — and nothing from the other side reaches the prompt anyway, because `towardBody` is never
     * rendered (see [engagementsOf]). The from-side filter is structural: this iterates the roster, so an
     * author who is not a member is never looked up at all.
     *
     * **Each member carries its own window**, its own `persona.interests_judged_at` (V27). The engagement
     * history is read once — coarsely bounded by [coarseFloor] — and narrowed PER MEMBER in memory. A
     * single global watermark would let one member's success disinherit every member that failed, was
     * capped out, or came back unusable in the same run; the cost of filtering in memory is a SQLite read
     * on a single-user forum, and the cost of getting the boundary wrong is a member that silently never
     * drifts from the words that should have moved it.
     *
     * **The three free skips are decided here, not after the cap.** A member below the engagement floor,
     * one with no interests at all and one whose every interest the owner pinned all cost nothing to
     * reject — a `phrasesOf`-shaped read answers all three — so letting them occupy a `take(cap)` slot
     * would spend real budget on members that were never going to be judged. The quiet-member case is
     * filtered before either skip is logged: a member with nothing new to say has not been skipped, it
     * simply had no question to ask, and a log line per member per run for that would bury the two that
     * matter.
     */
    private fun candidates(
        engagements: List<PersonaExchange>,
        roster: List<PersonaRepository.Persona>,
        windows: Map<String, Instant?>,
    ): List<Candidate> {
        val minimum = maxOf(1, props.minEngagements)
        val byMember = engagements.groupBy { it.fromAuthor }
        return roster.mapNotNull { member ->
            val window = windows[member.id]
            val evidence = byMember[member.id].orEmpty().filter { isAfter(it.createdAt, window) }
            if (evidence.size < minimum) return@mapNotNull null
            val held = interests.of(member.id)
            when {
                held.isEmpty() -> {
                    // Drift is opt-in PER MEMBER: with nothing authored there is nothing to swap, so an
                    // owner who gave this member no interests pays nothing even with the scheduler on.
                    log.info("event=interest.skipped persona={} reason=no-interests", member.id)
                    null
                }

                held.none { isOpen(it) } -> {
                    // The never-clobber contract, enforced before the judgment AND before the cap, so it
                    // costs nothing to hold and takes nothing from anyone else.
                    log.info("event=interest.skipped persona={} reason=all-owner-authored", member.id)
                    null
                }

                else -> Candidate(member, window, evidence)
            }
        }.sortedWith(byWindowAge)
    }

    /**
     * Judge and (maybe) swap one member's interests; returns whether anything actually moved. Every
     * branch that declines to move something is logged with its own reason — a pass that quietly does
     * nothing is indistinguishable from a broken one at four-thirty on a Sunday morning.
     *
     * ## Which verdicts close the window, and why the split is exactly here
     *
     * [readAt] is stamped for a USABLE verdict — [InterestDrift.Verdict.Drifted] and
     * [InterestDrift.Verdict.Unchanged] alike. "I read these engagements and they did not move this
     * member" is a complete answer about that evidence, and paying for the same answer again next week,
     * and every week after that, is the defect V27's watermark exists to close from day one: the judge is
     * *instructed* to answer NONE when nothing moved, so unchanged is the steady state of most members
     * most weeks, and it writes no audit row to advance the window with.
     *
     * A REJECTED answer and a seam failure deliberately stamp nothing. In both cases we never learned what
     * the judge thought of this evidence — the model returned a digit, or the provider was busy — so the
     * engagements are genuinely unjudged and get another look next run. That retry is the whole reason the
     * window is per-member, and it is why the stamp is written here at the judgment site rather than
     * inferred from "an LLM call happened for this member".
     *
     * ## One transaction around the swap
     *
     * Audit FIRST (the dropped phrase AND its provenance AND the evidence), then the delete, then the
     * insert, then the stamp — and all four commit together or not at all. An audit row that committed
     * alone would show the owner a swap that never happened AND would stand as this member's history
     * entry; a delete that committed without its insert would cost the member an interest outright, which
     * is I3 broken in the direction nobody is watching.
     *
     * [InterestChangeRepository.record] is itself `@Transactional`, and that composes rather than
     * competes: default propagation is REQUIRED, so it JOINS this transaction instead of opening a second
     * — which is exactly what its own KDoc needs, since its insert and its `last_insert_rowid()` read must
     * stay on one pooled connection or the owner's Revert button ends up wired to another writer's row.
     */
    private fun driftMember(candidate: Candidate, readAt: String): Boolean {
        val member = candidate.persona
        // RE-READ THE MEMBER'S INTERESTS HERE, and do not judge or write from the queue's snapshot. The
        // snapshot decided this member's ORDER and its WINDOW — questions about the run as a whole, which
        // want one consistent view — but "may the pass still rewrite this member" is a PERMISSION, and a
        // permission expires. The pass is synchronous, uncapped by default and spends up to a minute per
        // judgment, so the owner is watching a hung tab for as long as it runs, and the persona form in
        // another tab is where they go while they wait. Trusting the snapshot turns that wait into a window
        // where the pass deletes a phrase the owner pinned seconds earlier and writes the model's phrase in
        // its place — with the audit row citing the PRE-EDIT provenance, so Revert restores a `seeded` row
        // over what the owner typed and their own words are gone from a table that keeps no history.
        // Unrecoverable, not merely wrong. This is the defect S4a shipped and fixed in `bed019fe`
        // (StanceEvolutionService.kt:501-519); one re-read is what makes the never-clobber promise true.
        //
        // Residual, stated rather than implied: an owner edit landing inside the judgment call itself is
        // still overwritten. That is a sixty-second race rather than a whole-pass one, and closing it
        // properly wants a conditional write (`DELETE … WHERE source <> 'owner'`) rather than a re-read.
        val held = interests.of(member.id)
        if (held.isEmpty()) {
            // The owner blanked every field mid-pass, or the member was deleted while the pass ran.
            log.info("event=interest.skipped persona={} reason=no-interests-mid-pass", member.id)
            return false
        }
        val openRows = held.filter { isOpen(it) }
        if (openRows.isEmpty()) {
            // Pinned after the queue was built. Skipped BEFORE the judgment, so it costs nothing.
            log.info("event=interest.skipped persona={} reason=all-owner-authored-mid-pass", member.id)
            return false
        }
        val open = openRows.map { it.interest }
        val pinned = held.filterNot { isOpen(it) }.map { it.interest }
        // ONE list feeds both the prompt and the citation, so the audit shows the owner exactly the
        // evidence the model was given rather than a superset of it (S4a cites the untruncated list, which
        // on a first run over a long history names engagements the judge never read).
        val shown = candidate.evidence.takeLast(MAX_EVIDENCE_ENGAGEMENTS)
        val raw = judge(member, pinned, open, shown) ?: return false
        return when (val verdict = InterestDrift.parse(raw, open, pinned)) {
            is InterestDrift.Verdict.Drifted -> {
                // `first` rather than a fallback: `parse` hands back the STORED spelling out of the very
                // list built from these rows, so a miss is a broken invariant rather than a data
                // condition — and a guessed provenance is precisely what makes a later revert restore a
                // lie. The per-member catch in `drift` turns the impossible case into one skipped member
                // and a logged error, which is the right trade against writing a mislabelled row.
                val droppedRow = openRows.first { it.interest == verdict.dropped }
                val id = transactions.execute {
                    val recorded = changes.record(
                        personaId = member.id,
                        dropped = droppedRow.interest,
                        droppedSource = droppedRow.source,
                        takenUp = verdict.takenUp,
                        cited = renderCited(shown),
                    )
                    interests.delete(member.id, droppedRow.interest)
                    interests.upsert(member.id, verdict.takenUp, PersonaInterestRepository.SOURCE_DRIFTED)
                    interests.markJudged(member.id, readAt)
                    // The generated id travels out of the transaction because the log line — and the
                    // owner's revert link, downstream of it — needs the row that was just written.
                    recorded
                }
                log.info(
                    "event=interest.changed change={} persona={} dropped={} taken={}",
                    id, member.id, droppedRow.interest, verdict.takenUp,
                )
                true
            }

            is InterestDrift.Verdict.Rejected -> {
                // Not an error: the run continues to the next member, and the window stays OPEN so the
                // same engagements are re-judged next time. The raw answer is kept in the line so an
                // operator can see WHY nothing moved — the PersonaRouter split between "the seam broke"
                // and "the seam answered something we cannot use".
                log.warn(
                    "event=interest.judge.rejected persona={} reason={} raw={}",
                    member.id, verdict.reason, raw.trim(),
                )
                false
            }

            InterestDrift.Verdict.Unchanged -> {
                // A settled member must produce no audit row, or the owner's history page fills with
                // entries recording that nothing happened — but it MUST close the window, or this member
                // re-buys this same judgment on every run for as long as the forum runs. One statement, so
                // it needs no transaction of its own.
                interests.markJudged(member.id, readAt)
                log.info("event=interest.unchanged persona={}", member.id)
                false
            }
        }
    }

    /**
     * One drift judgment on the single shared [LlmClient] seam — no second IO boundary — tagged with the
     * synthetic [InterestDriftPrompts] identity so a spy, or the router, can tell a judgment apart from a
     * reply (the [com.aiforum.persona.LlmPromptComposer] pattern).
     *
     * **The blinkers are the convergence guardrail, and they are enforced by what is not passed.** The
     * instruction is built from this member's own name, own character, own interests and own words; no
     * roster, no other member's phrases, no count of anything. There is therefore no cross-member channel
     * for the room to converge through and nothing population-shaped for a model to optimise against —
     * `/admin/interests`' room map exists precisely because convergence is made visible to the OWNER while
     * staying invisible to every model.
     *
     * The instruction goes through [ContextAssembler.assemble] rather than a hand-built `ContextComment`,
     * so the owner-vote firewall keeps holding for this caller too: the "no vote signal reached the model"
     * guarantee is asserted by spying on what the seam received, and a caller that assembled its own
     * context would quietly sit outside it.
     *
     * Blocking `generate(request, CancellationToken())` with a fresh token and no sink: passing a sink
     * would emit AG-UI events with `runId = ""` at an SSE layer with no drafting node to route them to. A
     * seam failure returns null — logged as a SEAM failure, distinct from an unusable answer — and the
     * member is left exactly as it was, watermark included, so the next run tries the same evidence again.
     * That is the whole point of the distinction: a rate limit is not a verdict.
     *
     * `try/catch (Exception)` rather than `runCatching`, which catches `Throwable`: a `StackOverflowError`
     * or an `OutOfMemoryError` must not be swallowed as a routine judgment failure and leave the pass
     * spending LLM calls on a JVM that is already broken.
     */
    private fun judge(
        member: PersonaRepository.Persona,
        pinned: List<String>,
        open: List<String>,
        evidence: List<PersonaExchange>,
    ): String? {
        val instruction = InterestDriftPrompts.instruction(
            member = member.name,
            character = member.descriptor,
            pinned = pinned,
            open = open,
            engagements = engagementsOf(evidence),
        )
        val request = LlmRequest(
            context = ContextAssembler.assemble(InterestDriftPrompts.SYSTEM, listOf(judgmentTurn(instruction))),
            persona = PersonaRef(InterestDriftPrompts.JUDGE_ID, InterestDriftPrompts.JUDGE_NAME),
            timeout = JUDGE_TIMEOUT,
        )
        return try {
            llm.generate(request, CancellationToken()).text
        } catch (e: Exception) {
            log.warn(
                "event=interest.judge.failed persona={} reason={}",
                member.id, e.message ?: e.javaClass.simpleName,
            )
            null
        }
    }

    /**
     * The engagements as the judge reads them: the room they were said in, and the member's OWN body, one
     * line each and truncated.
     *
     * **`towardBody` is deliberately dropped.** On `exchangesSince`'s top-level branch — which is the
     * branch the ambient loop produces most often — it is `thread.body`, and for an ambient article thread
     * that is the article summary plus its URL: fetched, untrusted text. What the member said, and the
     * room it said it in, is the signal; what it was answering is not, and it is not worth handing a
     * judgment a paragraph from the open internet to be steered by.
     *
     * The thread title is truncated for the same reason it is included: it comes from a feed we do not
     * control, so it is bounded here rather than trusted to be a headline.
     */
    private fun engagementsOf(evidence: List<PersonaExchange>): List<InterestDriftPrompts.Engagement> =
        evidence.map {
            InterestDriftPrompts.Engagement(
                room = Snippet.oneLine(it.threadTitle, EVIDENCE_ROOM_CHARS),
                body = Snippet.oneLine(it.body, EVIDENCE_BODY_CHARS),
            )
        }

    /**
     * The judging turn as a synthetic comment, the shape [ContextAssembler] takes. Attributed to `owner`
     * for the same reason the composer's spec turn is: the prompt renderer labels the speaker, and this
     * text is instructions from the system, not a forum member's post.
     */
    private fun judgmentTurn(instruction: String) = Comment(
        id = "judgment",
        threadId = "",
        parentId = null,
        authorId = "owner",
        body = instruction,
        state = GenerationState.POSTED,
        failureCategory = null,
        depth = 0,
    )

    /** Owner-authored phrases are the per-member immutable core; everything else is open to drift. */
    private fun isOpen(interest: Interest): Boolean =
        interest.source != PersonaInterestRepository.SOURCE_OWNER

    /** The per-run member budget, clamped at the use site: 0 (and anything below) means unlimited. */
    private fun memberCap(): Int = if (props.maxPersonasPerRun > 0) props.maxPersonasPerRun else Int.MAX_VALUE

    /**
     * Is this engagement newer than the member's watermark — the EXACT per-member test, deliberately done
     * on parsed [Instant]s rather than on the ISO strings.
     *
     * `Instant.toString()` drops trailing zeros and prints no fraction at all on a whole second, so
     * `"…:08Z"` sorts AFTER `"…:08.4Z"` lexicographically while being earlier in time. A watermark that
     * lands on a whole second — which a fixed test clock does every time, and a real one does once in a
     * while — would therefore hide every sub-second engagement in that same second, permanently: the next
     * watermark is later still, so those engagements are never judged by anyone. Comparing instants costs
     * one parse per row and cannot go wrong.
     *
     * A null window (never judged, or an unreadable stamp) admits everything, and an engagement whose own
     * stamp cannot be parsed is KEPT rather than dropped — evidence the model might have acted on must not
     * vanish because a timestamp is malformed. Strict `isAfter`, so an engagement sitting exactly on the
     * watermark is not judged twice.
     */
    private fun isAfter(createdAt: String, window: Instant?): Boolean {
        if (window == null) return true
        val at = parsedOrNull(createdAt) ?: return true
        return at.isAfter(window)
    }

    /**
     * A coarse SQL floor for the single engagement read: the OLDEST watermark on the roster, and only when
     * every member has one. One never-judged member legitimately needs all-time history, and a floor above
     * it would hide exactly the evidence that member exists to be judged on.
     *
     * This is a read-size optimisation and nothing else — [isAfter] still decides per member what each
     * judgment actually sees, so the floor may only ever be too generous. Without it every run
     * materialises every persona-to-persona engagement the forum has ever produced, bodies included,
     * however narrow the actual windows are.
     *
     * **Why the floor may stay a lexicographic SQL comparison** (`c.created_at > ?`) while the per-member
     * test may not: the anomaly above only misorders stamps INSIDE one second, when one of them prints no
     * fraction. So the floor is dropped a whole second and truncated to a second boundary, which puts it
     * strictly below every engagement it must keep in the fixed-width part of the format, where
     * lexicographic and chronological order are the same thing. A coarse filter is allowed to return a few
     * rows too many; it is not allowed to lose one.
     */
    private fun coarseFloor(
        roster: List<PersonaRepository.Persona>,
        windows: Map<String, Instant?>,
    ): String? {
        if (roster.isEmpty()) return null
        val oldest = roster.map { windows[it.id] ?: return null }.minOrNull() ?: return null
        return oldest.minusSeconds(FLOOR_MARGIN_SECONDS).truncatedTo(ChronoUnit.SECONDS).toString()
    }

    /**
     * The stored watermark as an [Instant], or null when the member has never been judged — or when the
     * stored text cannot be read as an instant. A malformed stamp is a run-wide hazard if it throws (one
     * bad row would take the whole pass down from inside a framing read), and treating it as "never
     * judged" is the recoverable reading: the member is judged over all its history once and the next
     * stamp is written by us, so the row heals itself.
     */
    private fun windowOf(personaId: String): Instant? = interests.judgedAt(personaId)?.let { stamp ->
        parsedOrNull(stamp).also {
            if (it == null) log.warn("event=interest.window.unreadable persona={} stamp={}", personaId, stamp)
        }
    }

    private fun parsedOrNull(stamp: String): Instant? =
        try {
            Instant.parse(stamp)
        } catch (e: DateTimeParseException) {
            log.debug("event=interest.stamp.unparseable stamp={} reason={}", stamp, e.message)
            null
        }

    companion object {
        /**
         * Generous but bounded: a judgment is a two-line answer over a handful of comments, and an
         * unattended 04:30 run must not hang a scheduler thread all night on a wedged backend.
         */
        private val JUDGE_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * How many engagements one judgment may see, and how much of each. Enough to read what a member
         * kept coming back to, bounded because a member's window starts at all-time: the first run on an
         * established forum would otherwise paste every comment that member ever wrote into a single
         * prompt — and so would every run for a member whose judgments keep failing or coming back
         * refused, since neither closes the window (by design; both deserve another look). The most RECENT
         * engagements are kept, which are also the ones that describe what the member is into NOW, so the
         * cap costs the judgment nothing it wanted.
         */
        private const val MAX_EVIDENCE_ENGAGEMENTS = 12
        private const val EVIDENCE_BODY_CHARS = 400

        /** A thread title is fetched text on an ambient article thread, so it is bounded rather than trusted. */
        private const val EVIDENCE_ROOM_CHARS = 120

        /**
         * How far below the oldest watermark the coarse SQL floor is dropped — see [coarseFloor]. One
         * whole second, because that is the exact width of the lexicographic anomaly it has to clear: a
         * fraction-less stamp sorts after every sub-second stamp of the same second. Enlarging it only
         * widens an over-generous pre-filter; shrinking it below a second silently loses rows.
         */
        private const val FLOOR_MARGIN_SECONDS = 1L

        /** Field separator inside one cited record — see [parseCited] for why a tab is safe. */
        private const val CITED_SEPARATOR = "\t"

        /**
         * Snapshot the engagements a judgment was made from, one record per line as
         * `commentId <TAB> threadId <TAB> snippet`.
         *
         * **Text, not a foreign key** (the `comment_quote.quoted_text` precedent): `comment.body` is
         * mutable in place — an owner edit, a revision switch — so citing by id alone would let the
         * evidence change under the audit record, and deleting a thread would orphan the row. Storing the
         * prose means the owner always reads what was actually judged; the ids are kept beside it so the
         * page can still offer a permalink, rendered defensively for a comment that has since gone.
         *
         * The snippet is cut at [EVIDENCE_BODY_CHARS], the SAME length the prompt used, so the audit
         * carries byte-for-byte what the model read rather than a differently-truncated echo of it. How
         * much of that a row *displays* is the admin controller's decision, made on the way out.
         *
         * Every engagement the judgment saw is cited, never a sample and never a count of them: the audit
         * IS the control over an auto-applied change, so it has to show the evidence the model was given.
         *
         * A tab is a safe separator because [Snippet.oneLine] flattens the body's markdown and collapses
         * every whitespace run to a single space, so no tab or newline survives into the third field; the
         * two ids are UUIDs. Splitting is limited to three fields anyway, so a stray separator would land
         * harmlessly inside the snippet rather than shifting the record.
         */
        fun renderCited(engagements: List<PersonaExchange>): String =
            engagements.joinToString("\n") {
                listOf(it.commentId, it.threadId, Snippet.oneLine(it.body, EVIDENCE_BODY_CHARS))
                    .joinToString(CITED_SEPARATOR)
            }

        /**
         * Decode a stored `cited` field back into renderable records — the reader half of [renderCited],
         * kept here so the writer and anything reading it back cannot drift into two formats.
         *
         * Malformed records are DROPPED rather than thrown on: this parses stored data on a read path
         * whose whole job is to let the owner review what happened, and a single odd row from an older
         * format must not take the history page down with it. (A page that would rather render a malformed
         * line as unlinked evidence than lose it can split the raw text itself — `StanceAdminController`
         * makes exactly that call for its own view model.)
         */
        fun parseCited(cited: String): List<CitedEngagement> =
            cited.lineSequence()
                .filter { it.isNotBlank() }
                .map { it.split(CITED_SEPARATOR, limit = 3) }
                .filter { it.size == 3 }
                .map { CitedEngagement(it[0], it[1], it[2]) }
                .toList()
    }
}
