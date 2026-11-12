package dev.playforge.orchestration;

import dev.playforge.review.ReviewIssue;

import java.util.List;
import java.util.stream.Collectors;

/** Prompts sent to the coder and reviewer agents. User text is embedded verbatim. */
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

    /** Keeps review prompts within model context: diffs longer than this are cut. */
    static final int MAX_REVIEW_DIFF_CHARS = 60_000;

    private static String truncateDiff(String diff) {
        if (diff.length() <= MAX_REVIEW_DIFF_CHARS) return diff;
        return diff.substring(0, MAX_REVIEW_DIFF_CHARS) + "\n[diff truncated]";
    }
}
