import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildGhArgs, assertReadOnly, TOOLS, TOOLS_BY_NAME } from '../tools.mjs';

test('every tool builds a guarded read-only argv with minimal input', () => {
  // Minimal-but-valid input per tool so build() succeeds.
  const minimal = {
    gh_repo_view: {},
    gh_issue_list: {},
    gh_issue_view: { issue: '12' },
    gh_pr_list: {},
    gh_pr_view: {},
    gh_pr_diff: {},
    gh_pr_checks: {},
    gh_release_list: {},
    gh_release_view: {},
    gh_run_list: {},
    gh_run_view: { run: '999' },
    gh_search: { type: 'repos', query: 'spring boot' },
    gh_api_get: { endpoint: 'user' },
  };
  for (const tool of TOOLS) {
    const argv = buildGhArgs(tool.name, minimal[tool.name]);
    assert.ok(Array.isArray(argv) && argv.length > 0, `${tool.name} built an argv`);
    // assertReadOnly already ran inside buildGhArgs; re-running must be idempotent.
    assert.doesNotThrow(() => assertReadOnly(argv), `${tool.name} passes the guard`);
  }
});

test('issue_list composes filters into flags', () => {
  const argv = buildGhArgs('gh_issue_list', {
    repo: 'hevi-public/ai_forum',
    state: 'closed',
    limit: 5,
    labels: ['bug', 'p1'],
    author: 'octocat',
  });
  assert.deepEqual(argv, [
    'issue', 'list',
    '--repo', 'hevi-public/ai_forum',
    '--state', 'closed',
    '--limit', '5',
    '--label', 'bug',
    '--label', 'p1',
    '--author', 'octocat',
  ]);
});

test('search passes type and query positionally', () => {
  const argv = buildGhArgs('gh_search', { type: 'code', query: 'JdbcTemplate', limit: 3 });
  assert.deepEqual(argv, ['search', 'code', 'JdbcTemplate', '--limit', '3']);
});

test('gh_api_get forces an explicit GET and maps params to query fields', () => {
  const argv = buildGhArgs('gh_api_get', {
    endpoint: 'repos/hevi-public/ai_forum/commits',
    params: { per_page: 50, sha: 'main' },
  });
  assert.deepEqual(argv, [
    'api', '--method', 'GET', 'repos/hevi-public/ai_forum/commits',
    '-f', 'per_page=50',
    '-f', 'sha=main',
  ]);
});

test('gh_api_get rejects the graphql endpoint', () => {
  assert.throws(() => buildGhArgs('gh_api_get', { endpoint: 'graphql' }), /read-only|not allowed/i);
  assert.throws(() => buildGhArgs('gh_api_get', { endpoint: 'graphql/foo' }), /read-only|not allowed/i);
});

test('inputs that start with - are rejected (no flag injection)', () => {
  assert.throws(() => buildGhArgs('gh_issue_view', { issue: '--repo' }), /must not start with '-'/);
  assert.throws(() => buildGhArgs('gh_search', { type: 'repos', query: '--limit' }), /must not start with '-'/);
});

test('malformed repo is rejected', () => {
  assert.throws(() => buildGhArgs('gh_repo_view', { repo: 'not-a-repo' }), /OWNER\/REPO/);
  assert.throws(() => buildGhArgs('gh_issue_list', { repo: 'a/b/c' }), /OWNER\/REPO/);
});

test('out-of-range and wrong-typed values are rejected', () => {
  assert.throws(() => buildGhArgs('gh_issue_list', { limit: 0 }), /between/);
  assert.throws(() => buildGhArgs('gh_issue_list', { limit: 9999 }), /between/);
  assert.throws(() => buildGhArgs('gh_issue_list', { state: 'pending' }), /one of/);
  assert.throws(() => buildGhArgs('gh_run_view', { run: 5 }), /must be a string/);
});

test('required fields are enforced', () => {
  assert.throws(() => buildGhArgs('gh_issue_view', {}), /required/);
  assert.throws(() => buildGhArgs('gh_run_view', {}), /required/);
  assert.throws(() => buildGhArgs('gh_search', { type: 'repos' }), /required/);
});

test('unknown tool name is rejected', () => {
  assert.throws(() => buildGhArgs('gh_pr_merge', { pr: '1' }), /unknown tool/);
});

test('assertReadOnly blocks a hand-crafted mutating argv', () => {
  assert.throws(() => assertReadOnly(['pr', 'merge', '1']), /not an allowed read command/);
  assert.throws(() => assertReadOnly(['issue', 'create']), /not an allowed read command/);
  assert.throws(() => assertReadOnly(['repo', 'delete', 'a/b']), /not an allowed read command/);
  assert.throws(() => assertReadOnly(['gist', 'create']), /not allowed/);
});

test('assertReadOnly blocks a non-GET gh api call', () => {
  assert.throws(() => assertReadOnly(['api', '--method', 'POST', 'user']), /GET/);
  assert.throws(() => assertReadOnly(['api', '--method', 'GET', 'user', '-F', 'x=1']), /request-body/);
  assert.throws(() => assertReadOnly(['api', 'user']), /explicit GET/);
});

test('tool registry is consistent', () => {
  assert.equal(TOOLS.length, TOOLS_BY_NAME.size);
  for (const t of TOOLS) {
    assert.equal(typeof t.description, 'string');
    assert.equal(t.inputSchema.type, 'object');
    assert.equal(typeof t.build, 'function');
  }
});
