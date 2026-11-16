package dev.playforge.orchestration;

import dev.playforge.playtest.AiPlayResult;
import dev.playforge.review.ReviewIssue;
import dev.playforge.run.Markup;

import java.util.List;
import java.util.stream.Collectors;

/** Prompts sent to the coder, reviewer, player and judge agents. User text is embedded verbatim. */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    private static final String GAME_RULES = """
            Project rules:
            - The working directory is a Vite project. A new project starts from a small working starter game
              (index.html, src/main.js, src/probe-sdk.js, vite.config.js); replace the game, keep the structure.
              Dependencies are preinstalled (vite, phaser); you may `import Phaser from 'phaser'`.
            - Keep each concern in its own module under src/.
            - Do not change vite.config.js: it must keep base: './' (the game is served from a sub-path).
            - Every game MUST call installProbe() from src/probe-sdk.js (window.__GAME_PROBE__, version 1):
              getState() -> { status: 'loading'|'ready'|'playing'|'paused'|'gameover', score: number,
                              entities: [{ id, type, x, y }] }, actions: [{ name, keys: [...] }] (KeyboardEvent.code
              names such as 'ArrowLeft', 'Space'), start(), restart(). Use probeEvent(type, data) for game events.
            - Keep the probe in sync with the real game state. An automated playtest builds the game
              (npm run build), presses every declared action's keys and expects the player's entities (position
              or count) or the status to change, checks the canvas is not blank, that there are no console
              errors, and that restart() returns to 'ready' or 'playing'.
            - Only list in-game controls in actions (move, jump, shoot...): keys that act while the status is
              'playing'. Starting and restarting are covered by start()/restart(), not by actions.
            - Listen for keyboard input with window.addEventListener('keydown'/'keyup') (the playtest does not focus
              any element). Make short actions (jump, shoot) visible in the probe while the key is held.
            - The game runs in a sandboxed origin: localStorage, sessionStorage, IndexedDB and cookies throw.
              Keep all state in memory.
            - Run `npm run build` before you finish and fix any error it reports.
            """;

    public static String code(String instruction) {
        return """
                You are building a small browser game.

                Player request (verbatim):
                <<<
                %s
                >>>

                %s
                Implement the request completely in the current directory.
                """.formatted(instruction, GAME_RULES);
    }

    /**
     * Appended to coder prompts of a request with marked areas.
     * @param imagePath where the marked screenshot is in the sandbox, or null when it could not be provided
     */
    public static String markup(Markup m, String imagePath) {
        StringBuilder sb = new StringBuilder("""

                The player pointed at areas of the running game. Canvas: %d×%d px, the coordinate space of the
                game code (x to the right, y down from the top-left corner).
                """.formatted(m.canvas().width(), m.canvas().height()));
        for (int i = 0; i < m.boxes().size(); i++) {
            int[] p = m.boxes().get(i).pixels(m.canvas());
            sb.append("- Box %d: x %d–%d, y %d–%d%n".formatted(i + 1, p[0], p[2], p[1], p[3]));
        }
        sb.append(imagePath != null
                ? """
                  A screenshot with these numbered red boxes is at %s (it may be scaled up from the canvas size).
                  Open it with the Read tool before changing anything. The request may refer to a box by its
                  number (for example "框 1" or "1"). The red boxes are annotations, not part of the game.
                  """.formatted(imagePath)
                : "The screenshot could not be provided; rely on the coordinates.\n");
        sb.append("Change what the request asks for in and around these areas; keep the rest of the game as it is.\n");
        return sb.toString();
    }

    public static String fixFromReview(String instruction, List<ReviewIssue> blockers) {
        String issues = blockers.stream()
                .map(i -> "- " + (i.file() == null ? "" : i.file() + (i.line() == null ? "" : ":" + i.line()) + " ") + i.message())
                .collect(Collectors.joining("\n"));
        return """
                A code reviewer found blocking problems in the game you are building.

                Original request (verbatim):
                <<<
                %s
                >>>

                Blocking issues:
                %s

                %s
                Fix these issues with minimal, targeted changes. Do not rewrite unrelated files.
                """.formatted(instruction, issues, GAME_RULES);
    }

    public static String fixFromPlaytest(String instruction, List<String> failures) {
        String list = failures.stream().map(f -> "- " + f).collect(Collectors.joining("\n"));
        return """
                An automated playtest of the game you are building failed.

                Original request (verbatim):
                <<<
                %s
                >>>

                Playtest failures:
                %s

                %s
                Find the cause of each failure and fix it with targeted changes.
                """.formatted(instruction, list, GAME_RULES);
    }

    public static String review(String instruction, String diff) {
        return """
                You are reviewing changes to a small browser game. Do not modify any files.

                Original request (verbatim):
                <<<
                %s
                >>>

                Changes (unified diff):
                %s

                Check correctness, whether the request is fulfilled, and whether window.__GAME_PROBE__ is implemented
                and kept in sync with the game state.

                Reply with ONLY this JSON (no other text):
                {"issues":[{"severity":"BLOCKER|SUGGESTION","file":"path","line":123,"message":"..."}]}
                BLOCKER = the game is broken, the request is not fulfilled, or the probe API is missing/wrong.
                SUGGESTION = nice-to-have improvement. Return {"issues":[]} when there is nothing to report.
                """.formatted(instruction, diff.isBlank() ? "(no changes)" : truncateDiff(diff));
    }

    /** The AI player: reads the game and writes a playthrough that the harness runs and records. */
    public static String player(String instruction) {
        return """
                You are a QA player for a small browser game in the current directory. Do NOT modify any files.

                Player request (verbatim):
                <<<
                %s
                >>>

                First read the game's source (start with src/) to learn the probe state (window.__GAME_PROBE__:
                status, score, entities), the declared actions and their keys, and how the game reacts to them.

                Then write ONE JavaScript module that plays a real round of this game the way a player would and
                checks the acceptance criteria the request implies (for example: the controls move the player,
                scoring works, losing ends the game, restart starts a new round). It runs in Node, not in the page,
                with this API:

                export default async function play(t) {
                  // t.actions                    -> [{ name, keys }]
                  // await t.state()              -> { status, score, entities: [{ id, type, x, y }] }
                  // await t.start(); await t.restart();
                  // await t.press(keys, ms)      hold KeyboardEvent.code keys ('ArrowLeft' or ['Space']), default 150 ms
                  // await t.down(keys); await t.up(keys); await t.wait(ms)
                  // await t.waitFor(state => boolean, ms)  -> true if it became true within ms (default 3000)
                  // t.expect(condition, message) records an assertion; the message says what SHOULD happen
                  // await t.shot(label)          screenshot (at most 4): take them at interesting moments
                  // t.log(message)
                }

                Rules:
                - Assert only what the request asks for or what any game of this kind must do. Prefer
                  t.waitFor with generous timeouts over fixed waits; never assert exact timings or positions.
                - Write 3 to 8 assertions; write each message as a short sentence in Simplified Chinese that says
                  what should happen (for example "按 → 键后篮子向右移动").
                - Finish within 40 seconds. Do not import anything and do not touch the DOM.

                Reply with ONLY the module in a ```js code block.
                """.formatted(instruction);
    }

    /** The judge: scores how fun the game is from the playthrough's screenshots. Report only. */
    public static String judge(String instruction, List<AiPlayResult.Shot> shots, List<AiPlayResult.Assertion> assertions,
                               List<String> log) {
        String shotList = shots.stream().map(s -> "- /workspace/dist/" + s.file() + " (" + s.label() + ")")
                .collect(Collectors.joining("\n"));
        String checks = assertions.isEmpty() ? "- (none)" : assertions.stream()
                .map(a -> "- [" + (a.passed() ? "passed" : "failed") + "] " + a.message()).collect(Collectors.joining("\n"));
        String notes = log.isEmpty() ? "- (none)" : log.stream().map(l -> "- " + l).collect(Collectors.joining("\n"));
        return """
                You are judging how fun a small browser game is, from screenshots of an automated playthrough.
                Do NOT modify any files.

                Player request (verbatim):
                <<<
                %s
                >>>

                Screenshots (open each one with your Read tool, in this order):
                %s

                What the automated player checked:
                %s

                Playthrough notes:
                %s

                Score each dimension from 1 (poor) to 5 (great):
                - fit: does the game match the request?
                - difficulty: is the challenge reasonable (neither trivial nor impossible)?
                - visuals: is it readable and pleasant to look at?
                - feedback: does the game clearly react to the player (score, effects, game-over screen)?

                Reply with ONLY this JSON (no other text); write summary and suggestions in Simplified Chinese:
                {"scores":{"fit":3,"difficulty":3,"visuals":3,"feedback":3},"summary":"一句话评价","suggestions":["至多 3 条具体改进建议"]}
                """.formatted(instruction, shotList, checks, notes);
    }

    /** Keeps review prompts within model context: diffs longer than this are cut. */
    static final int MAX_REVIEW_DIFF_CHARS = 60_000;

    private static String truncateDiff(String diff) {
        if (diff.length() <= MAX_REVIEW_DIFF_CHARS) return diff;
        return diff.substring(0, MAX_REVIEW_DIFF_CHARS) + "\n[diff truncated]";
    }
}
