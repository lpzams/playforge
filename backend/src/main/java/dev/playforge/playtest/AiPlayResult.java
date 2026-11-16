package dev.playforge.playtest;

import java.util.List;

/**
 * Outcome of an AI playthrough, recorded by the harness (not reported by the model).
 *
 * @param ran        the script ran to completion; false when it threw or the harness produced no report
 * @param shots      screenshots, paths relative to the build output (dist)
 * @param durationMs wall time of the playthrough
 */
public record AiPlayResult(boolean ran, String error, List<Assertion> assertions, List<Shot> shots,
                           List<String> log, long durationMs) {

    public record Assertion(String message, boolean passed) {}

    public record Shot(String file, String label) {}

    /** Nothing to judge the game by: the script broke or asserted nothing. Never fails a game. */
    public boolean inconclusive() {
        return !ran || assertions.isEmpty();
    }

    public boolean allPassed() {
        return !inconclusive() && assertions.stream().allMatch(Assertion::passed);
    }

    /** Failed assertions as playtest failures for the fix prompt; empty when inconclusive. */
    public List<String> failures() {
        if (inconclusive()) return List.of();
        return assertions.stream().filter(a -> !a.passed()).map(a -> "ai-play: " + a.message()).toList();
    }
}
