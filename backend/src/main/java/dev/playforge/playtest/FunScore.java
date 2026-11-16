package dev.playforge.playtest;

import java.util.List;
import java.util.Map;

/**
 * The judge's opinion of how fun the game is. Report only: it never gates a run.
 *
 * @param scores 1–5 per dimension: fit (matches the request), difficulty, visuals, feedback (response to input)
 */
public record FunScore(boolean parsed, Map<String, Integer> scores, String summary, List<String> suggestions) {

    public static final List<String> DIMENSIONS = List.of("fit", "difficulty", "visuals", "feedback");

    public static FunScore unparsed() {
        return new FunScore(false, Map.of(), "", List.of());
    }
}
