package com.aiforum.ambient

/**
 * Who fired an ambient tick (plan_docs/ambient-slice-1.md): the owner's hand from /admin/ambient
 * (MANUAL, always available) or the gated `@Scheduled` caller (SCHEDULED). Stored lowercased on
 * `ambient_run.source` — "trigger" would collide with a SQLite keyword, so the column is "source".
 */
enum class TickSource { MANUAL, SCHEDULED }
