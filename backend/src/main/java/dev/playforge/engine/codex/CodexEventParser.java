package dev.playforge.engine.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.engine.StreamEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code codex exec --json} lines. Stateful (one instance per run): the final text is the last
 * agent_message, emitted when the turn completes. Format: see src/test/resources/fixtures/README.md.
 * Error events ({@code error}, {@code turn.failed}) are not yet confirmed by a recorded sample.
 */
public final class CodexEventParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private String lastAgentMessage = "";

    private String lastError;

    /** Message of the last {@code error} event, or null. Errors are not terminal: Codex may recover. */
    public String lastError() {
        return lastError;
    }

    public List<StreamEvent> parse(String line) {
        JsonNode n = read(line);
        if (n == null) return List.of();
        List<StreamEvent> out = new ArrayList<>();
        JsonNode item = n.path("item");
        switch (n.path("type").asText()) {
            case "item.started" -> {
                if (item.path("type").asText().equals("command_execution")) {
                    out.add(new StreamEvent.ToolCall("shell", StreamEvent.summarize(item.path("command").asText()), null));
                }
            }
            case "item.completed" -> {
                switch (item.path("type").asText()) {
                    case "agent_message" -> {
                        lastAgentMessage = item.path("text").asText();
                        out.add(new StreamEvent.Text(lastAgentMessage));
                    }
                    case "command_execution" -> {
                        boolean failed = !item.path("status").asText().equals("completed")
                                || item.path("exit_code").asInt(0) != 0;
                        out.add(new StreamEvent.ToolResult(
                                StreamEvent.summarize(item.path("aggregated_output").asText()), failed));
                    }
                    default -> { }
                }
            }
            case "turn.completed" -> {
                JsonNode u = n.path("usage");
                out.add(new StreamEvent.Usage(u.path("input_tokens").asLong(), u.path("output_tokens").asLong()));
                out.add(new StreamEvent.Final(lastAgentMessage, false));
            }
            case "turn.failed" -> out.add(new StreamEvent.Final(n.path("error").path("message").asText("turn failed"), true));
            case "error" -> {
                lastError = n.path("message").asText("error");
                out.add(new StreamEvent.Text("[codex error] " + lastError));
            }
            default -> { }
        }
        return out;
    }

    private static JsonNode read(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(line);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
