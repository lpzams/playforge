package dev.playforge.engine.codex;

import dev.playforge.engine.StreamEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexEventParserTest {

    List<StreamEvent> parseFixture(String name) throws Exception {
        CodexEventParser parser = new CodexEventParser();
        return Files.readAllLines(Path.of("src/test/resources/fixtures/" + name), StandardCharsets.UTF_8)
                .stream().flatMap(l -> parser.parse(l).stream()).toList();
    }

    @Test
    void parsesFixtureFinalMessage() throws Exception {
        List<StreamEvent> events = parseFixture("codex-exec.jsonl");

        assertThat(events.getLast()).isEqualTo(new StreamEvent.Final("{\"issues\":[]}", false));
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.Usage).singleElement()
                .isEqualTo(new StreamEvent.Usage(28449, 64));
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.ToolCall).singleElement()
                .satisfies(e -> assertThat(((StreamEvent.ToolCall) e).inputSummary()).contains("cat hello.js"));
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.ToolResult).singleElement()
                .isEqualTo(new StreamEvent.ToolResult("console.log('hi');", false));
    }

    @Test
    void failedCommandIsErrorToolResult() throws Exception {
        List<StreamEvent> events = parseFixture("codex-exec-bwrap-failure.jsonl");
        assertThat(events).filteredOn(e -> e instanceof StreamEvent.ToolResult).singleElement()
                .satisfies(e -> assertThat(((StreamEvent.ToolResult) e).isError()).isTrue());
    }

    @Test
    void garbageLinesIgnored() {
        CodexEventParser parser = new CodexEventParser();
        for (String line : List.of("", "not json", "{\"type\":\"unknown\"}", "{\"type\":\"item.comp", "[1,2]")) {
            assertThat(parser.parse(line)).as(line).isEmpty();
        }
    }

    @Test
    void errorEventsBecomeErrorFinal() {
        assertThat(new CodexEventParser().parse("{\"type\":\"error\",\"message\":\"stream error: rate limit\"}"))
                .containsExactly(new StreamEvent.Final("stream error: rate limit", true));
        assertThat(new CodexEventParser().parse("{\"type\":\"turn.failed\",\"error\":{\"message\":\"boom\"}}"))
                .containsExactly(new StreamEvent.Final("boom", true));
    }
}
