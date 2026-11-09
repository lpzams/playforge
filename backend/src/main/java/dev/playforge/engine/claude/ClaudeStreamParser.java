package dev.playforge.engine.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.engine.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses one line of {@code claude -p --output-format stream-json --verbose}. Format: see
 * src/test/resources/fixtures/README.md. Bad or unknown lines yield no events.
 */
public final class ClaudeStreamParser {

    static final Set<String> FILE_TOOLS = Set.of("Write", "Edit", "MultiEdit", "NotebookEdit");
    private static final ObjectMapper JSON = new ObjectMapper();

    public List<StreamEvent> parse(String line) {
        JsonNode n = read(line);
        if (n == null) return List.of();
        List<StreamEvent> out = new ArrayList<>();
        switch (n.path("type").asText()) {
            case "assistant" -> {
                for (JsonNode c : n.path("message").path("content")) {
                    switch (c.path("type").asText()) {
                        case "text" -> out.add(new StreamEvent.Text(c.path("text").asText()));
                        case "tool_use" -> {
                            String name = c.path("name").asText();
                            JsonNode input = c.path("input");
                            String path = FILE_TOOLS.contains(name) && input.hasNonNull("file_path")
                                    ? input.get("file_path").asText()
                                    : FILE_TOOLS.contains(name) && input.hasNonNull("notebook_path")
                                    ? input.get("notebook_path").asText() : null;
                            out.add(new StreamEvent.ToolCall(name, StreamEvent.summarize(input.toString()), path));
                        }
                        default -> { }
                    }
                }
            }
            case "user" -> {
                for (JsonNode c : n.path("message").path("content")) {
                    if (c.path("type").asText().equals("tool_result")) {
                        out.add(new StreamEvent.ToolResult(StreamEvent.summarize(contentText(c.path("content"))),
                                c.path("is_error").asBoolean(false)));
                    }
                }
            }
            case "result" -> {
                JsonNode u = n.path("usage");
                if (u.isObject()) {
                    long in = u.path("input_tokens").asLong() + u.path("cache_creation_input_tokens").asLong()
                            + u.path("cache_read_input_tokens").asLong();
                    out.add(new StreamEvent.Usage(in, u.path("output_tokens").asLong()));
                }
                boolean isError = n.path("is_error").asBoolean(false);
                String text = n.hasNonNull("result") ? n.get("result").asText() : n.path("subtype").asText("");
                out.add(new StreamEvent.Final(text, isError));
            }
            default -> { }
        }
        return out;
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.hasNonNull("text")) sb.append(part.get("text").asText());
        }
        return sb.toString();
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
