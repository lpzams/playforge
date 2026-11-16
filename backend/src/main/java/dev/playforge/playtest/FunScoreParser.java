package dev.playforge.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lenient parser for the judge's reply: bare JSON, JSON in a fence, or JSON surrounded by prose. */
public final class FunScoreParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUGGESTIONS = 5;

    private FunScoreParser() {
    }

    public static FunScore parse(String text) {
        if (text == null) return FunScore.unparsed();
        JsonNode root = tryRead(text.trim());
        if (root == null) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) root = tryRead(text.substring(start, end + 1));
        }
        if (root == null || !root.path("scores").isObject()) return FunScore.unparsed();

        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dim : FunScore.DIMENSIONS) {
            JsonNode v = root.get("scores").get(dim);
            if (v != null && v.isNumber()) scores.put(dim, Math.max(1, Math.min(5, v.asInt())));
        }
        List<String> suggestions = new ArrayList<>();
        for (JsonNode s : root.path("suggestions")) {
            if (suggestions.size() < MAX_SUGGESTIONS && !s.asText("").isBlank()) suggestions.add(s.asText());
        }
        return new FunScore(true, Map.copyOf(scores), root.path("summary").asText(""), List.copyOf(suggestions));
    }

    private static JsonNode tryRead(String s) {
        try {
            JsonNode n = JSON.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
