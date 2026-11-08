package dev.playforge.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenient parser for reviewer output. Accepts bare JSON, JSON in a code fence, or JSON surrounded by prose.
 * Anything else yields {@code parsed=false} with no issues (review is advisory; playtest is the arbiter).
 */
public final class ReviewParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewParser() {
    }

    public static ReviewReport parse(String text) {
        if (text == null) return unparsed();
        JsonNode root = tryRead(text.trim());
        if (root == null) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) root = tryRead(text.substring(start, end + 1));
        }
        if (root == null || !root.path("issues").isArray()) return unparsed();

        List<ReviewIssue> issues = new ArrayList<>();
        for (JsonNode n : root.get("issues")) {
            issues.add(new ReviewIssue(
                    severity(n.path("severity").asText("")),
                    n.hasNonNull("file") ? n.get("file").asText() : null,
                    n.path("line").isInt() ? n.get("line").asInt() : null,
                    n.path("message").asText("")));
        }
        return new ReviewReport(true, List.copyOf(issues));
    }

    private static Severity severity(String s) {
        return "BLOCKER".equals(s.toUpperCase(Locale.ROOT)) ? Severity.BLOCKER : Severity.SUGGESTION;
    }

    private static JsonNode tryRead(String s) {
        try {
            JsonNode n = JSON.readTree(s);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ReviewReport unparsed() {
        return new ReviewReport(false, List.of());
    }
}
