# P5b: local platform in the showcase's visual language + real end-to-end acceptance

Branch: p5b-local-platform. Follows P5a (AI playtester). Also closes what P4 deferred: real-engine UI
acceptance and a whole-branch review.

## Goal

The local platform (web/) looks and reads like the showcase (light, tonal surfaces, pill controls, no badges or
emoji, process told as a story), shows the game large and sharp, and is verified end to end with the real engines.

## Pages

- **Home**: greeting headline, one large composer (auto-growing textarea, example pills, round send button,
  Ctrl+Enter), then "我的游戏": pill search + cards like the showcase (cover, short title, two-line prompt,
  one plain-text meta line; status in words, colour only for 生成中/失败).
- **Project**: the game as large as the viewport allows (aspect from `cover.json`, sharp via the hi-DPI shim) or a
  calm placeholder while there is no build; title row with 重新开始 / 全屏 / 新窗口; a composer for follow-ups that
  turns into "生成中 · 取消" while a run is active; version pills (第 1 版 …); then the selected version's story,
  live over SSE, with "完整日志" (the existing raw feed) folded below.

## Story (web/src/story/)

Pure TS, unit-tested: events → steps (segments by STEP_STARTED) → entries with actor, live status or duration,
one plain sentence, and details: review issues, failed hard checks in Chinese, the AI player's assertions and
screenshots, the judge's score and suggestions, Claude's delivery note. Same wording as the showcase.

## Backend

`PreviewController` injects the hi-DPI shim (`/hidpi.js`, shipped in web/public) into a preview's `index.html`, so
games fill their frame sharply in the platform and in "新窗口". The playtest harness serves builds itself and is
unaffected.

## Tasks

1. Story module + tests.
2. Design tokens and shell (topbar), drop Element Plus.
3. Home page + card + tests.
4. Project page (stage, composer, versions, story, raw log) + tests.
5. Preview hi-DPI injection + test.
6. Edge screenshots (desktop, phone), fix what looks wrong.
7. Real end-to-end acceptance from the UI: new game → live story → follow-up → cancel.
8. Whole-branch review (P4 + P5a + P5b) by a reviewer agent; fix findings; merge; update docs.
