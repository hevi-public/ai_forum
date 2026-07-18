---
name: sqlite-spring-jdbc
description: SQLite persistence for the AI Forum Spring Boot + Kotlin app using spring-jdbc (JdbcTemplate), Flyway, and recursive CTEs — deliberately NOT Hibernate. Use this whenever working with the database layer — datasource config, profile isolation (prod/dev/test), the xerial sqlite-jdbc + WAL/busy-timeout setup, Flyway migrations on SQLite (incl. editing/repairing migrations and checksum-mismatch failures, SQLite ALTER TABLE limits), writing the recursive-CTE branch/ancestor/subtree queries for the comment tree, JdbcTemplate RowMappers, or per-scenario test-DB reset. Reach for it before adding repositories, migrations, or datasource beans so the tree queries and profile guardrails stay correct.
---

# SQLite + spring-jdbc for AI Forum

Storage is SQLite via `JdbcTemplate` + Flyway. We deliberately avoid Hibernate: it has no maintained
official SQLite dialect (community ones rot across Hibernate majors), it fights SQLite's dynamic
typing, and the core mechanism here — recursive-CTE branch retrieval (§5/§11) — is hand-written SQL
anyway, so an ORM buys nothing. `JdbcTemplate` gives full control of the CTE, deterministic SQL,
trivial fixture seeding, and zero dialect risk.

This is the persistence seam of [[bdd-tiered-testing]]; acceptance tests run against the **real** test
DB (see [[cucumber-spring-bdd]] for the reset hooks).

## Dependencies

```kotlin
implementation("org.springframework.boot:spring-boot-starter-jdbc")
implementation("org.xerial:sqlite-jdbc:3.53.2.0")
// Spring Boot 4 MODULARISED autoconfig: the starter brings flyway-core AND the spring-boot-flyway
// autoconfiguration module. Adding flyway-core alone leaves Flyway un-autoconfigured — it silently
// never runs, your tables are never created, and the only symptom is "no such table" at query time.
implementation("org.springframework.boot:spring-boot-starter-flyway")
implementation("org.flywaydb:flyway-database-nc-sqlite:12.4.0")     // ← the real SQLite module name
```

Two easy-to-get-wrong things here, both verified the hard way:
- **Use the `spring-boot-starter-flyway`**, not bare `flyway-core`. In Spring Boot 4 autoconfig is split
  into per-tech modules (`spring-boot-flyway`, `spring-boot-jdbc`, …); the starters pull the matching
  autoconfig module. A bare tech jar gives you the library but not the autoconfiguration.
- The SQLite module is **`flyway-database-nc-sqlite`** (the "nc" = native-connectors line) — there is
  **no** `flyway-database-sqlite` artifact (it 404s), and Flyway refuses SQLite without it.

When editing migrations during development, remember SQLite **WAL** leaves `-wal`/`-shm` sidecar files:
delete `the.db`, `the.db-wal`, AND `the.db-shm` together when resetting, or you'll inspect a stale DB.

## SQLite reality checks (the gotchas)

- **One writer at a time.** SQLite serializes writes. Enable **WAL** mode for concurrent readers, and
  set a **busy_timeout** so a brief lock waits instead of throwing `SQLITE_BUSY`.
- **Connection pool.** With a file DB, keep the pool small. WAL makes concurrent reads fine; writes
  still serialize. A `maximum-pool-size` of 1–5 is plenty for M1.
- **In-memory is per-connection.** A plain `:memory:` DB is private to one connection and vanishes
  when it closes — useless across a pooled app. For tests prefer a **file** DB under `build/` (easy to
  truncate/reset and to inspect on failure), or `file::memory:?cache=shared` with pool size 1.
- **Dynamic typing.** SQLite stores what you give it. Be explicit in RowMappers (`getLong`, `getString`)
  and store timestamps as ISO-8601 TEXT or epoch millis consistently.

Apply pragmas via the JDBC URL so every connection gets them:

```
jdbc:sqlite:build/aiforum-test.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
```

## Flyway migrations — never edit an applied one (checksum mismatches)

Flyway records a **checksum** of every applied migration in `flyway_schema_history`. Change a
migration file's body after it has run *anywhere* and that DB fails **validate-on-migrate at startup**:

```
Migration checksum mismatch for migration version 7
-> Applied to database : -1923931933
-> Resolved locally    : 1416662186
```

Rules, all verified the hard way:

- **Never edit a migration that may have been applied** (locally, in CI, or in prod) — add a new
  `V(n+1)__….sql` instead. Editing `Vn` is only safe before it has ever run.
- **A checksum mismatch CANNOT be healed by a forward migration.** Validation runs *before* any pending
  migration, so a new `V(n+1)` never executes on the broken DB — the app won't start. The only fixes are
  to revert the file to its applied form, or **`flyway.repair()`**, which realigns the *recorded*
  checksum to the current file (it does **not** re-run migrations and does **not** touch table data).
- **Automatic repair = a temporary `FlywayMigrationStrategy`** bean that runs `repair()` then
  `migrate()`. It disables validate-on-migrate's edit-protection, so treat it as a one-off heal and
  **remove it once every live DB has booted past the fix** (we did exactly this for a V7 split, then
  deleted the bean). Default Spring Boot behaviour is migrate-only with validation on.
- **Parallel branches are a checksum trap.** Two branches each adding "V7" produce two different bodies
  for one version; whichever a DB applied first wins, and the merge silently loses the other. **`git
  fetch` + scan `db/migration/` before claiming a version number**, and reconcile deliberately on merge.
- **Stale test/dev DB symptom.** After you legitimately change a not-yet-released migration during dev,
  the persistent `build/aiforum-test.db` (or `data/aiforum-dev.db`) still holds the OLD checksum and
  fails. Delete the db **plus its `-wal`/`-shm` sidecars** to reset (same WAL rule as above).

### SQLite `ALTER TABLE` is narrow — get the column right the first time

SQLite supports only `ADD COLUMN` (plus limited rename/drop on newer versions). You **cannot** change an
existing column's type or constraints in place — adding `NOT NULL` to a populated column needs a full
**table rebuild** (create new table → `INSERT … SELECT` → drop → rename). So a column's definition is
effectively one-shot. The two `ADD COLUMN` forms differ in how they treat existing rows:

- `ADD COLUMN body TEXT` — nullable; **existing rows read `NULL`**.
- `ADD COLUMN body TEXT NOT NULL DEFAULT ''` — **existing rows backfill to `''`** (a `NOT NULL` add
  *requires* a DEFAULT).

If two lineages of the same migration disagree (nullable vs `NOT NULL DEFAULT`), one carries `NULL`s the
other can't produce — and a non-null Kotlin field reading that column NPEs. Coalesce forward with a
follow-up data migration (`UPDATE t SET col='' WHERE col IS NULL`); it's a harmless no-op on the
canonical lineage (whose column is `NOT NULL`, so no `NULL`s exist).

## Profile-isolated datasource

Three profiles, three databases. `test` must never touch prod, and backups are disabled under `test`.

`application.yml` (shared; default profile dev):
```yaml
spring:
  profiles:
    default: dev
  flyway:
    enabled: true
    locations: classpath:db/migration
```

`application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:sqlite:build/aiforum-test.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
aiforum:
  backups:
    enabled: false        # ← rail-tested: backups OFF under test
```

`application-dev.yml` is a *relative* throwaway file in the project tree
(`jdbc:sqlite:data/aiforum-dev.db`) — nuke it freely to test intermediate stages with a fresh DB.
`application-prod.yml` is a *persistent* home-directory DB for long-term work:

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${user.home}/.ai_forum/data/aiforum.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
```

Both have backups enabled. Launch prod with **`./gradlew bootRunProd`** (a `BootRun` task passing
`--spring.profiles.active=prod`); plain `bootRun` stays dev.

### Filesystem paths: absolute + `~`-hardened (the rule for ALL path config)

> Any new filesystem-path config — the DB here, but equally **backups**, exports, logs — must resolve
> to an **absolute** path and tolerate a `~`. Two traps, both real:
> 1. **Relative defaults leak into the cwd.** A bare `jdbc:sqlite:data/…` is relative to wherever the
>    process started, so prod data would land in the project dir instead of the home dir. Prod uses
>    `${user.home}` (resolved by Spring *before* the URL is read) to stay absolute.
> 2. **The JVM does NOT expand a leading `~`.** `Path.of("~/x")` makes a literal junk `~` directory in
>    the cwd (nasty to delete) and the driver opens the wrong file. So **never rely on `~` reaching the
>    filesystem.**

`DataDirectoryInitializer` (an `org.springframework.boot.EnvironmentPostProcessor`, registered in
`META-INF/spring.factories`) enforces both before any bean — Flyway, Hikari — runs. It delegates
parsing/expansion to the pure, tier0-tested `config/SqlitePath.expand(url, homeDir)` (strips the
`jdbc:sqlite:` prefix + `?…` query, expands a leading `~`/`~/…` against `user.home`, returns `null` for
in-memory/non-sqlite), then `Files.createDirectories(parent)` and — if expansion changed the URL —
**republishes** it via an `addFirst` `MapPropertySource` so the driver opens the same absolute file the
dir was created for. Net effect: add a new datasource profile and the dir is handled automatically; a
literal `~` can never create a stray dir. (This is also the fresh-checkout fix for the old
`[SQLITE_CANTOPEN] unable to open database file` — no manual `mkdir data` needed.)

**The shared `~`-expansion helper (now that a second path exists).** The backup writer (below) is the
anticipated second consumer, so the tilde logic was factored out of `expand` into a now-**public**
`SqlitePath.expandTilde(path, homeDir)`: a pure function that resolves a *leading* `~` / `~/…` against
`homeDir` and passes everything else (`~user`, a mid-path `~`, absolute/relative paths, `/` and `\`)
through untouched. `expand` calls it internally; the backup component calls it directly. Don't
hand-roll tilde handling for any third path — reuse `expandTilde`, and keep the `ProfileGuard`
assertion (below) plus its `config_guardrails` rail scenario as the contract.

### The profile guard (rail-tested config)

Config drifts silently, so assert it. A small bean validates the wiring at startup, and a rail
scenario ([[cucumber-spring-bdd]]) checks it from the outside:

```kotlin
@Component
class ProfileGuard(
    env: Environment,
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${aiforum.backups.enabled}") private val backups: Boolean,
) {
    init {
        if (env.activeProfiles.contains("test")) {
            require("test" in url) { "test profile must use the test DB, got: $url" }
            require(!backups) { "backups must be disabled under the test profile" }
        }
    }
}
```

## Online backups: `VACUUM INTO` snapshots (the `enabled` flag finally does something)

The `aiforum.backups.enabled` flag had long implied snapshots but never performed them — a single
home-directory DB with no recovery point is the biggest real data-loss risk for a PoC the owner cares
about. `backup/SqliteBackup` closes that gap with **`VACUUM INTO '<dest>'`**, NOT a file copy:

- **Why `VACUUM INTO`, not `Files.copy`.** Copying a live WAL database can capture a torn page set
  (committed data still in `-wal`, half-checkpointed). `VACUUM INTO` runs through the same Hikari
  connection pool the app uses and writes a **clean, consistent single-file** copy of the committed
  state — no checkpoint dance, no `-wal`/`-shm` sidecars in the snapshot. It *requires* the destination
  not already exist, so the writer uses a UTC-timestamped filename (`aiforum-yyyyMMdd'T'HHmmss'Z'.db`)
  and suffixes `-1`, `-2`, … if two land in the same clock-second.
- **Gating mirrors the test-isolation rule.** The component is `@Component @Profile("!test")` +
  `@ConditionalOnProperty(prefix = "aiforum.backups", name = ["enabled"], havingValue = "true")`, so it
  **never wires under `test`** (which sets `enabled: false`, enforced by `ProfileGuard`) — a test run
  can't write into the real `~/.ai_forum` store. A sibling `SchedulingConfig` carries the *same* gate plus
  `@EnableScheduling`, so the test context never even starts a scheduler thread.
- **Reuses the shared path helper + injected `Clock`.** The destination dir
  (`aiforum.backups.dir`, default `~/.ai_forum/backup`) is `~`-expanded via `SqlitePath.expandTilde` (the
  second consumer that helper was extracted for) and `Files.createDirectories`'d. The only time source
  is the injected `Clock` (no `Instant.now()`), so the filename timestamp is pinnable in tests — the
  same seam discipline as everywhere else.
- **Schedule + retention.** One snapshot ~5s after startup (`@Scheduled(initialDelay …, fixedDelay =
  Long.MAX_VALUE)`) plus a daily cron (`@Scheduled(cron = "0 30 3 * * *")`). Retention keeps the newest
  `aiforum.backups.keep` (default 7) snapshots and prunes older ones each run — filenames sort
  lexically == chronologically because of the ISO timestamp. Backups are **best-effort**: a failure
  logs at ERROR (`event=backup.failed`) but never takes the app down (`backup.ok` / `backup.pruned` are
  the other event ids — see [[bdd-tiered-testing]] on log-as-contract).

`application.yml` (prod/dev have backups on; `application-test.yml` forces them off):
```yaml
aiforum:
  backups:
    enabled: true
    dir: ~/.ai_forum/backup   # ~ expanded via SqlitePath.expandTilde, never reaching disk literally
    keep: 7               # newest N snapshots kept; older ones pruned each run
```

## Schema + recursive CTEs (the heart of it)

The comment tree is a self-referencing table; branch context is a recursive CTE. Migration
`V1__schema.sql`:

```sql
CREATE TABLE comment (
    id         TEXT PRIMARY KEY,
    thread_id  TEXT NOT NULL,
    parent_id  TEXT REFERENCES comment(id),
    author_id  TEXT NOT NULL,
    body       TEXT NOT NULL,
    state      TEXT NOT NULL,            -- DRAFTING|POSTED|FAILED|CANCELLED
    depth      INTEGER NOT NULL,
    created_at TEXT NOT NULL             -- ISO-8601
);
CREATE INDEX idx_comment_parent ON comment(parent_id);
CREATE INDEX idx_comment_thread ON comment(thread_id);
```

#### Two later index migrations worth knowing

- **V16 `persona_slug_unique` — a UNIQUE index that has to dedupe first.** `findBySlug` assumes a slug
  is unique (it `firstOrNull()`s), so `V16__persona_slug_unique.sql` enforces it — but a bare
  `CREATE UNIQUE INDEX` would **fail on any DB that already holds duplicates**, and crucially on the
  empty-slug case (`slug` was added in V5 with `DEFAULT ''`, so every pre-V5 row carries `''` — a
  built-in duplicate the moment there are ≥2). The pattern is **dedupe-before-index**, in one migration
  so it's clean on a fresh DB and also heals a messy existing one: (1) `UPDATE … SET slug = id WHERE
  slug IS NULL OR slug = ''` (the PK is already unique); (2) deterministically suffix the remaining
  non-empty duplicates with a window function — `ROW_NUMBER() OVER (PARTITION BY slug ORDER BY rowid)`,
  keep `rn = 1`, append `-2`/`-3`/… to the rest; (3) finally `CREATE UNIQUE INDEX idx_persona_slug`.
  The matching **insert-time** suffixing lives in `PersonaRepository` (`nextFreeSlug`): it reads the
  small set of slugs sharing the base (`slug = ? OR slug LIKE '<base>-%'`) and computes the first free
  `base-2`/`base-3`/… *before* the INSERT, so a new row never trips the index — collision is resolved by
  query, not caught from a constraint violation. (General rule: when you add a UNIQUE index over a
  populated column, ship the dedupe in the same migration AND give the writer a collision-free insert
  path.)
- **V17 `comment_thread_order_idx` — a composite read index (pure perf).** The dominant comment read
  (`threadComments`, `growableLeaves`) is `WHERE thread_id = ? ORDER BY depth, created_at`. The V1
  single-column `idx_comment_thread` serves the *filter* but leaves SQLite to sort matched rows in a
  temp B-tree on every read. `CREATE INDEX idx_comment_thread_order ON comment(thread_id, depth,
  created_at)` matches the key order to *equality column first, then the ORDER BY columns*, so the
  planner walks it already-sorted — no separate sort step. No schema/behaviour change, so no backfill
  and no acceptance test. Note this leaves the V1 `idx_comment_thread` **redundant** (its
  `thread_id`-only key is a prefix of the new composite); the migration keeps it rather than dropping it
  (dropping is a separate, riskier change), so just be aware it's there and superseded.

**Ancestor path** (root → node) — the branch-only scope:

```sql
WITH RECURSIVE ancestors(id, parent_id, body, depth) AS (
    SELECT id, parent_id, body, depth FROM comment WHERE id = :nodeId
    UNION ALL
    SELECT c.id, c.parent_id, c.body, c.depth
    FROM comment c JOIN ancestors a ON c.id = a.parent_id
)
SELECT * FROM ancestors ORDER BY depth;     -- root first
```

**Subtree** (node → all descendants) — for depth-budget growth and full-thread assembly:

```sql
WITH RECURSIVE subtree(id, parent_id, body, depth) AS (
    SELECT id, parent_id, body, depth FROM comment WHERE id = :rootId
    UNION ALL
    SELECT c.id, c.parent_id, c.body, c.depth
    FROM comment c JOIN subtree s ON c.parent_id = s.id
)
SELECT * FROM subtree ORDER BY depth;
```

**Siblings** (under the current parent) — for the include-siblings toggle:

```sql
SELECT * FROM comment WHERE parent_id = :parentId AND id <> :nodeId ORDER BY created_at;
```

### Bound every recursive CTE with a depth counter (the cycle guard)

The tree is *meant* to be acyclic (the app never writes a cycle), but a recursive CTE that walks
`parent_id` has no inherent terminator — a single corrupt `parent_id` (a manual fix-up, a future bug, a
restore from a bad dump) makes the walk loop **forever**, hanging the request thread. So every
recursive CTE in `CommentRepository` carries an explicit `lvl` counter and a `lvl < 10000` bound on the
recursive arm (10000 is far above any real thread depth). The bound is a separate `lvl` column —
**distinct from the stored `depth`** (which the cycle would corrupt too) — incremented per hop:

```sql
WITH RECURSIVE ancestors(id, lvl) AS (
    SELECT id, 0 FROM comment WHERE id = :nodeId
    UNION ALL
    SELECT c.parent_id, a.lvl + 1
    FROM comment c JOIN ancestors a ON c.id = a.id
    WHERE c.parent_id IS NOT NULL AND a.lvl < 10000   -- ← terminates a corrupt cycle
)
SELECT cm.* FROM comment cm JOIN ancestors an ON cm.id = an.id ORDER BY cm.depth;
```

All three tree-walking CTEs carry it — `ancestorPath`, `descendantCount`, and the
`subtreeIdsDeepestFirst` behind `deleteSubtree`. It's a termination guarantee, not a correctness one: a
healthy tree never approaches the bound, and a cyclic one returns/throws promptly instead of spinning.
[[bdd-tiered-testing]] pins this with a forged-2-cycle `assertTimeoutPreemptively` test.

## Repository with NamedParameterJdbcTemplate

```kotlin
@Repository
class JdbcCommentRepository(private val jdbc: NamedParameterJdbcTemplate) : CommentRepository {

    private val mapper = RowMapper { rs, _ ->
        Comment(
            id = rs.getString("id"),
            parentId = rs.getString("parent_id"),
            body = rs.getString("body"),
            depth = rs.getInt("depth"),
        )
    }

    override fun ancestorPath(nodeId: String): List<Comment> =
        jdbc.query(ANCESTOR_SQL, mapOf("nodeId" to nodeId), mapper)

    override fun subtree(rootId: String): List<Comment> =
        jdbc.query(SUBTREE_SQL, mapOf("rootId" to rootId), mapper)
}
```

Inject the repository by constructor everywhere (the seam discipline from [[bdd-tiered-testing]]).

## `@Transactional` on the multi-statement writes (atomicity over single-writer SQLite)

Several repo methods issue **several `jdbc.update`s that must succeed or fail as a unit**, and SQLite
auto-commits each statement on its own. A crash or `SQLITE_BUSY` part-way through would leave the DB
half-mutated — a subtree half-cut, a revision history half-seeded. So those methods are annotated
`@Transactional` (`org.springframework.transaction.annotation`):

- `CommentRepository.deleteSubtree` / `deleteByThread` — the votes + revisions + attachments batch
  DELETEs plus the per-id deepest-first comment-DELETE loop are one unit.
- `CommentRepository.editBody` — the seed-idx0 + addRevision + selectRevision writes are one unit (a
  half-built revision history is the failure mode).
- `GenerationService.regenerate` — same revision-history triplet, after the (non-transactional) LLM
  call returns. The on-failure early return happens *before* any write, so the model call holds no
  write lock (SQLite defers the lock to the first statement).

**The wiring fact that makes this work — and the thing to not break:**

- Spring Boot **autoconfigures** a `DataSourceTransactionManager` bound to the app's single
  `DataSource`. There is **no custom `DataSource` bean and no explicit
  `@EnableTransactionManagement`** — Boot's `TransactionAutoConfiguration` supplies both the manager and
  the annotation-driven proxy infrastructure. Don't add a hand-rolled manager or a redundant
  `@EnableTransactionManagement`; you'd risk a second manager binding a different DataSource and silently
  defeating rollback.
- `@Transactional` only takes effect **on calls that go through the Spring proxy** — i.e. an *external*
  caller (a controller, a service, a test) invoking the bean method. A method calling another
  `@Transactional` method on `this` bypasses the proxy. Here that's fine and deliberate: `editBody`'s
  internal calls to `addRevision`/`selectRevision` are *plain* helpers that simply run inside the
  transaction `editBody` already opened. Keep the boundary on the entry point, not on the helpers.

[[bdd-tiered-testing]] documents the forced-rollback Tier-1 test that *proves* this boundary is live.

## Per-scenario reset

Cheapest reliable approach: `DELETE FROM` every table (children before parents, or
`PRAGMA foreign_keys=off` around it) then re-apply `db/fixtures/test-fixtures.sql`. `Flyway.clean()` +
`migrate()` per scenario also works but is slower; truncate+seed is preferred. The hook lives in
[[cucumber-spring-bdd]]'s `DatabaseResetHooks`.

## Verify

- `./gradlew tier1` runs `JdbcCommentRepositoryTest` against the real test DB, asserting the
  ancestor/subtree CTEs return the right nodes in depth order.
- App boots under `test` and Flyway applies migrations to `build/aiforum-test.db` (delete the file to
  start clean).
- The `ProfileGuard` throws if the test profile is ever pointed at a non-test URL or has backups on.
- `tier0` runs `SqlitePathTest` (pure `~`-expansion); `tier1` runs `DataDirectoryInitializerTest`
  (dir-creation + `~`/republish) and `MigrationPipelineTest` (Flyway upgrades an *older* DB forward:
  migrate to V3, seed rows, migrate to latest, assert data survives + new-column DEFAULT/backfill).
- `./gradlew bootRunProd` boots the prod profile against `~/.ai_forum/data/aiforum.db` (persistent; created
  on first run) — confirm no stray `~` dir appears in the project.
