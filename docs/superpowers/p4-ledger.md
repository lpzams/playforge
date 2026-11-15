# SDD ledger — plan: docs/superpowers/plans/2026-11-15-p4-web-frontend.md
Spec: docs/superpowers/specs/2026-11-08-playforge-design.md
Branch: p4-web-frontend

## Pre-flight
- T1→T2: types.ts mirrors ProjectView/RunView incl. new createdAt/previewAvailable/startedAt/finishedAt from T1 — consistent.
- T2→T3/T4/T5: api client + RunEvent type consumed by events.ts and pages — consistent.
- T3→T5: openRunStream(runId, onEvent, onFinished, factory) + FEED_LIMIT — consistent.
- User preference (2026-11-15): UI "简洁好用" → light, simple Element Plus look (plan updated before execution).
Task 1: Ruling: "/" is forwarded to /index.html via a view controller (resource handler ignores the empty path); MockMvc only records forwards, so the test asserts forwardedUrl and the real-container behaviour is checked in Task 6 with curl — cost if wrong: none
Task 1: complete — backend suite 184 run, 0 failures; Docker ITs skipped (Docker Desktop down, change does not touch the sandbox) — re-run with Docker before final review
Task 2: Ruling: Element Plus registered globally (full import) — simplest for a local tool; build warns about a >500 kB chunk — cost if wrong: slower first load only
Task 2: Ruling: extra test "error without JSON body still becomes ApiError" — cost if wrong: none
Task 2: complete — web: npm test 5/5, npm run build OK
Task 3: complete — web: npm test 12/12 (events 7)
Task 4: Ruling: page tests use a real in-memory vue-router (spy on push) + a stubbed fetch (test-utils.ts) instead of vi.mock of modules — vi.mock of 'vue-router'/'../api/client' did not apply to the SFC's imports; the fetch-level stub also exercises the real client. The first (vi.mock) version went RED for the right reason against the placeholder page; the rewrite keeps the same assertions — cost if wrong: none
Task 4: complete — web: npm test 18/18, build OK
Task 5: Ruling: the selected run's history is loaded through the same SSE stream (server replays history, then RUN_FINISHED closes it) — no separate history endpoint needed — cost if wrong: viewing an old run opens a short-lived SSE connection
Task 5: Ruling: Check/ReviewIssue types moved to components/feed-types.ts (<script setup> cannot export); preview iframe gets ?v=<n> after each finished run to force a reload — cost if wrong: none
Task 5: Ruling: test "unknown check names are shown as-is" used 'harness', which the component deliberately maps to 试玩脚本; changed the sample name to custom_check (the test's intent) — cost if wrong: none
Task 5: complete — web: npm test 34/34, npm run build (vue-tsc) OK
Task 6: PARTIAL — verified on the real server (demo profile): / → index.html, SPA fallback, assets, /api not shadowed. Real-engine UI acceptance (snake → follow-up → cancel) and the whole-branch review DEFERRED: Docker Desktop was down and the user asked to prioritise a GitHub showcase.
Final: Ruling: P4 merged without the opus whole-branch review and without real-engine UI acceptance, at the user's request for speed — cost if wrong: UI bugs found later rather than before merge
