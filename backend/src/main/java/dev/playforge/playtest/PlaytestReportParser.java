package dev.playforge.playtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns run.mjs output into a {@link PlaytestReport}. The report is the last line starting with {"playtest":. */
public final class PlaytestReportParser {

    static final String MARKER = "{\"playtest\":";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private PlaytestReportParser() {
    }

    @SuppressWarnings("unchecked")
    public static PlaytestReport parse(List<String> stdoutLines, String stderrTail) {
        String line = null;
        for (String l : stdoutLines) if (l.startsWith(MARKER)) line = l;
        if (line != null) {
            try {
                Map<String, Object> m = JSON.readValue(line, MAP);
                List<String> failures = new ArrayList<>();
                if (m.get("failures") instanceof List<?> fs) fs.forEach(f -> failures.add(String.valueOf(f)));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("checks", m.getOrDefault("checks", List.of()));
                details.put("durationMs", m.getOrDefault("durationMs", 0));
                return new PlaytestReport(Boolean.TRUE.equals(m.get("passed")), failures, details);
            } catch (Exception ignored) {
                // fall through: treat a malformed report like a missing one
            }
        }
        String tail = stderrTail == null ? "" : stderrTail.strip();
        return new PlaytestReport(false, List.of("playtest harness produced no report: " + tail),
                Map.of("checks", List.of(), "durationMs", 0));
    }
}
