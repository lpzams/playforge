package dev.playforge.engine.claude;

import dev.playforge.engine.StreamEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeStreamParserTest {

    final ClaudeStreamParser parser = new ClaudeStreamParser();

    List<StreamEvent> parseFixture() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/fixtures/claude-stream.jsonl"), StandardCharsets.UTF_8)
                .stream().flatMap(l -> parser.parse(l).stream()).toList();
    }

    @Test
    void parsesFixtureEndToEnd() throws Exception {
        List<StreamEvent> events = parseFixture();

        assertThat(events).filteredOn(e -> e instanceof StreamEvent.ToolCall).singleElement()
                .satisfies(e -> {
                    StreamEvent.ToolCall call = (StreamEvent.ToolCall) e;
                    assertThat(call.name()).isEqualTo("Write");
                    assertThat(call.filePath()).isEqualTo("/workspace/hello.js");
                });
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.ToolResult).hasSize(1);
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.Text)
                .extracting(e -> ((StreamEvent.Text) e).text()).contains("done");
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.Usage).singleElement()
                .satisfies(e -> {
                    StreamEvent.Usage u = (StreamEvent.Usage) e;
                    assertThat(u.inputTokens()).isPositive();
                    assertThat(u.outputTokens()).isPositive();
                });
        assertThat(events.getLast()).isEqualTo(new StreamEvent.Final("done", false));
    }

    @Test
    void garbageLinesIgnored() {
        for (String line : List.of("", "not json", "{\"type\":\"unknown\"}", "{\"type\":\"assi", "[1,2]")) {
            assertThat(parser.parse(line)).as(line).isEmpty();
        }
    }

    @Test
    void toolInputSummaryTruncated() {
        String big = "x".repeat(5000);
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"" + big + "\"}}]}}";
        StreamEvent.ToolCall call = (StreamEvent.ToolCall) parser.parse(line).getFirst();
        assertThat(call.inputSummary().length()).isLessThanOrEqualTo(500);
        assertThat(call.filePath()).isNull();
    }

    @Test
    void errorResultWithoutTextUsesSubtype() {
        List<StreamEvent> events = parser.parse("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}");
        assertThat(events.getLast()).isEqualTo(new StreamEvent.Final("error_max_turns", true));
    }
}
