# P5a: AI playtester (AI plays a round + judge scores the fun)

Spec: docs/superpowers/specs/2026-11-08-playforge-design.md, decision 7 in docs/decisions.md.
Branch: p5a-ai-playtester

## Goal

After the scripted hard checks, an AI player writes a Playwright playthrough for this game and request, the
platform runs it and records the assertions itself, and a judge model rates the screenshots on fixed
dimensions. Hard checks and AI-play assertions are gates; the fun score is report-only.

## Flow of a PLAYTEST step

1. **Script.** Reuse the script cached in an earlier PLAYTEST step of the same run; otherwise ask the PLAYER
   engine (Claude) for one. The player may read the source but must not edit it: `syncIn` afterwards
   discards any change. A failed or unusable reply skips AI play for this step (logged), never fails it.
2. **Hard checks.** Unchanged (`Playtester.run`: build + run.mjs). Fail → the step fails as before.
3. **AI play.** Only if the hard checks passed and a script exists: `Playtester.play` writes the script to
   `/tmp/playforge/play.mjs` in the sandbox and runs `/opt/playtest/play.mjs`, which loads the game, gives
   the script a small API, and prints `{"aiplay":1,...}` with assertions and screenshots
   (`dist/playtest/NN-label.png`, shipped with the build).
4. **Gate.** `passed = hard.passed && (ai == null || ai.inconclusive || ai.allPassed || fixesExhausted)`.
   Inconclusive = the script threw or made no assertions: a bad script never fails a good game. When the
   playtest fix budget is used up, the objective hard checks arbitrate alone (`aiPlayWaived`).
5. **Judge.** Only when passed and screenshots exist: JUDGE engine (Claude, multimodal) reads the
   screenshots, gets the request and the AI-play log, replies JSON scores (fit, difficulty, visuals,
   feedback; 1–5) + summary + suggestions (Chinese). Best effort: failures are logged. `syncIn` after.
6. Events: `PLAYTEST_RESULT` gains `aiPlay` (ran, inconclusive, assertions, shots, log, waived, skipped);
   new `FUN_SCORE` event. The step output keeps the script (cache) and the fun score.

The fix prompt receives AI-play failures as `ai-play: <assertion>` lines.

## Script API (`export default async function play(t)`)

`t.actions`, `t.state()`, `t.start()`, `t.restart()`, `t.press(keys, ms=150)`, `t.down(keys)`, `t.up(keys)`,
`t.wait(ms)`, `t.waitFor(fn(state), ms=3000) -> bool`, `t.expect(cond, message)`, `t.shot(label)` (max 4),
`t.log(msg)`. 45 s budget. The harness adds automatic start/end screenshots.

## Tasks

1. **Engine roles.** `AgentRole` += PLAYER, JUDGE; `AgentEngine.supports(role)` (default: `role()`);
   Claude supports CODER/PLAYER/JUDGE; `Engines(coder, reviewer, player, judge)` with player/judge defaulting
   to the coder; `EngineRegistry.player()/judge()`; `callEngine` takes the role. Tests: registry + props.
2. **Results and parsers.** `AiPlayResult` (+ parser of the last `{"aiplay":1}` line), `FunScore`
   (+ lenient parser), `PlayScripts.extract` (fenced js block or bare module; must contain
   `export default`). Unit tests.
3. **Harness.** `sandbox/playtest/play.mjs`; `Playtester.supportsAiPlay()/play()`; `DockerPlaytester.play`.
   Tests: DockerPlaytesterTest (command sequence), harness IT on the `good` fixture (Docker).
4. **Prompts.** `PromptTemplates.player(instruction)`, `judge(instruction, shots, log)`,
   `fixFromPlaytest` unchanged (failures already carry `ai-play:`). Tests.
5. **Orchestrator.** PLAYTEST flow above, `FUN_SCORE` event, config
   `playforge.playtest.ai-play` / `playforge.playtest.judge` (default true). Tests in RunOrchestratorTest:
   pass+judge, AI failure → fix → script reused, waived when fixes exhausted, inconclusive passes,
   player failure skips, no AI support → no player call.
6. **UI.** Local web `PlaytestChecklist`: AI-play assertions, screenshots, fun score. Showcase: story,
   replay and "平台拦下了什么" show the AI round and the score.
7. **Image + real run.** Rebuild `playforge-sandbox:p4` (play.mjs), run the Docker ITs, generate the showcase
   games again with real engines, export, deploy, verify with Edge screenshots.
