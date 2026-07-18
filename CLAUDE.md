# AI Forum

An owner-driven brainstorming forum where hand-authored AI personas reply in a nested comment tree
(per-branch context scoping is the differentiator). Spring Boot 4.1 + Kotlin, SQLite + Flyway, JTE
SSR + htmx, Cucumber acceptance suite. Single-user PoC — no auth by design. Forked from HAIP on
2026-07-18 (`hevi-public/ai_forum`); the product direction is shifting toward AI chat.

This file is a router, not a doctrine dump. The doctrine lives where it's testable:

- **`.claude/skills/`** — execution-ready how-tos; consult BEFORE touching their areas:
  `bdd-tiered-testing` (any test — tiers, what to fake), `cucumber-spring-bdd` (acceptance/.feature
  wiring), `jte-spring-kotlin` (anything under `src/main/jte`), `sqlite-spring-jdbc` (DB layer,
  migrations, CTEs).
- **`plan_docs/`** — one design doc per feature, status header on top; the requirements spec is
  `plan_docs/ai-forum-requirements.md` (§-numbered).
- **`how-we-work/context.md`** — cross-session state: conventions, gotchas, feature-state map.
- **`how-we-work/README.md`** — the testing & organisation rationale (why it's built this way).

Non-negotiables:

1. **The gate is `./gradlew verifyAll`** — jsTest, the two `mcp/` test tasks, tiers 0–2, acceptance.
   CI runs the same command (`docker compose run --rm build`). Don't merge red; don't bypass tiers.
2. **Fakes only at the IO ports** (`LlmClient`, `ImageDescriber`, `ShortcutClient`, `GitHubClient`,
   plus `Clock`/repo fault-wrappers). Never mock a service to test a controller.
3. **Worktree sessions: never `cd` to the main-repo path** — edits/builds silently land on `main`.
   Stay in the worktree cwd; use `--no-build-cache` if a JTE template change "doesn't take".
4. **Durable learnings go to `how-we-work/context.md`** (and the matching skill/plan doc), not just
   private session memory — a second human must be able to read them.
