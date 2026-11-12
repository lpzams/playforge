package dev.playforge.playtest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaytestReportParserTest {

    @Test
    void takesLastReportLine() {
        PlaytestReport r = PlaytestReportParser.parse(List.of(
                "npm warn x",
                "{\"playtest\":1,\"passed\":false,\"failures\":[\"old\"],\"checks\":[],\"durationMs\":1}",
                "log",
                "{\"playtest\":1,\"passed\":true,\"failures\":[],\"checks\":[{\"name\":\"load\",\"passed\":true,\"detail\":\"\"}],\"durationMs\":5}"),
                "");
        assertThat(r.passed()).isTrue();
        assertThat(r.failures()).isEmpty();
        assertThat(r.details()).containsEntry("durationMs", 5);
        assertThat((List<?>) r.details().get("checks")).hasSize(1);
    }

    @Test
    void failuresAreCarried() {
        PlaytestReport r = PlaytestReportParser.parse(List.of(
                "{\"playtest\":1,\"passed\":false,\"failures\":[\"probe: window.__GAME_PROBE__ not found within 10s\"],\"checks\":[],\"durationMs\":9}"), "");
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).containsExactly("probe: window.__GAME_PROBE__ not found within 10s");
    }

    @Test
    void noReportLineIsFailure() {
        PlaytestReport r = PlaytestReportParser.parse(List.of("Error: Cannot find module"), "stack...");
        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("playtest harness produced no report").contains("stack...");
    }

    @Test
    void malformedReportIsFailure() {
        PlaytestReport r = PlaytestReportParser.parse(List.of("{\"playtest\":1,\"passed\":tr"), "");
        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("playtest harness produced no report");
    }
}
