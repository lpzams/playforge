package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ProcessTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerPlaytesterTest {

    static final List<String> BUILD = List.of("sh", "-c", "rm -rf dist && npm run build");

    final DockerPlaytester playtester = new DockerPlaytester();

    @Test
    void buildFailureShortCircuits() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox()
                .then(List.of("vite v5.4.11 building for production...", "error during build: Could not resolve ./missing.js"), 1, "");

        PlaytestReport r = playtester.run(sandbox, new CancellationToken());

        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("build failed:").contains("Could not resolve ./missing.js");
        assertThat(sandbox.cmds).containsExactly(BUILD);
        assertThat(r.details().get("checks").toString()).contains("build");
    }

    /** A stale dist from an earlier build must never be playtested or exported. */
    @Test
    void buildCommandClearsStaleDist() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox().then(List.of(), 1, "");
        playtester.run(sandbox, new CancellationToken());
        assertThat(sandbox.cmds.getFirst()).isEqualTo(BUILD);
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

    /** Timeouts are playtest failures (fed to the FIX loop), not crashed steps. */
    @Test
    void buildTimeoutIsAFailedReport() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox().thenThrow(new ProcessTimeoutException("docker timed out after 180000 ms"));

        PlaytestReport r = playtester.run(sandbox, new CancellationToken());

        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("build timed out");
        assertThat(sandbox.cmds).hasSize(1);
    }

    @Test
    void harnessTimeoutIsAFailedReport() {
        SequencedExecSandbox sandbox = new SequencedExecSandbox()
                .then(List.of("built"), 0, "")
                .thenThrow(new ProcessTimeoutException("docker timed out after 120000 ms"));

        PlaytestReport r = playtester.run(sandbox, new CancellationToken());

        assertThat(r.passed()).isFalse();
        assertThat(r.failures().getFirst()).startsWith("playtest timed out");
        assertThat(r.details().get("checks").toString()).contains("build");
    }
}
