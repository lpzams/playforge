package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerPlaytesterTest {

    final DockerPlaytester playtester = new DockerPlaytester();

    @Test
    void buildFailureShortCircuits() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox()
                .then(List.of("vite v5.4.11 building for production...", "error during build: Could not resolve ./missing.js"), 1, "");

        PlaytestReport r = playtester.run(sandbox, new CancellationToken());

        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("build failed:").contains("Could not resolve ./missing.js");
        assertThat(sandbox.cmds).containsExactly(List.of("npm", "run", "build"));
        assertThat(r.details().get("checks").toString()).contains("build");
    }

    @Test
    void runsHarnessAfterBuild() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox()
                .then(List.of("built in 120ms"), 0, "")
                .then(List.of("{\"playtest\":1,\"passed\":true,\"failures\":[],\"checks\":[{\"name\":\"load\",\"passed\":true,\"detail\":\"\"}],\"durationMs\":7}"), 0, "");

        PlaytestReport r = playtester.run(sandbox, new CancellationToken());

        assertThat(r.passed()).isTrue();
        assertThat(sandbox.cmds.get(1)).containsExactly("node", "/opt/playtest/run.mjs", "--dist", "/workspace/dist");
        assertThat(r.details().get("checks").toString()).contains("build").contains("load");
    }
}
