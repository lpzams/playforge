package dev.playforge.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Turns play.mjs output into an {@link AiPlayResult}. The report is the last line starting with {"aiplay":. */
public final class AiPlayResultParser {

    static final String MARKER = "{\"aiplay\":";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOG = 50;

    private AiPlayResultParser() {
    }

    public static AiPlayResult parse(List<String> stdoutLines, String stderrTail) {
        String line = null;
        for (String l : stdoutLines) if (l.startsWith(MARKER)) line = l;
        if (line != null) {
            try {
                JsonNode n = JSON.readTree(line);
                List<AiPlayResult.Assertion> assertions = new ArrayList<>();
                for (JsonNode a : n.path("assertions")) {
                    assertions.add(new AiPlayResult.Assertion(a.path("message").asText(""), a.path("passed").asBoolean(false)));
                }
                List<AiPlayResult.Shot> shots = new ArrayList<>();
                for (JsonNode s : n.path("shots")) {
                    shots.add(new AiPlayResult.Shot(s.path("file").asText(""), s.path("label").asText("")));
                }
                List<String> log = new ArrayList<>();
                for (JsonNode l : n.path("log")) if (log.size() < MAX_LOG) log.add(l.asText(""));
                String error = n.hasNonNull("error") ? n.get("error").asText() : null;
                return new AiPlayResult(n.path("ran").asBoolean(false), error, List.copyOf(assertions),
                        List.copyOf(shots), List.copyOf(log), n.path("durationMs").asLong(0));
            } catch (Exception ignored) {
                // fall through: a malformed report is like a missing one
            }
        }
        String tail = stderrTail == null ? "" : stderrTail.strip();
        return new AiPlayResult(false, "ai-play harness produced no report: " + tail, List.of(), List.of(), List.of(), 0);
    }
}
