package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CliDockerOpsTest {

    /** Records invocations instead of starting processes. */
    static final class RecordingRunner extends ProcessRunner {
        final List<List<String>> cmds = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        String stdout = "abc123\n";

        @Override
        public ExecResult run(List<String> cmd, Map<String, String> extraEnv, byte[] stdin, Duration timeout,
                              CancellationToken cancel, Consumer<String> stdoutLine) {
            cmds.add(cmd);
            envs.add(extraEnv);
            stdins.add(stdin);
            stdout.lines().forEach(stdoutLine);
            return new ExecResult(0, "");
        }

        @Override
        public ExecResult runBinary(List<String> cmd, InputStream stdin, OutputStream out, Duration timeout,
                                    CancellationToken cancel) {
            cmds.add(cmd);
            return new ExecResult(0, "");
        }
    }

    static final DockerRunSpec SPEC = new DockerRunSpec("playforge-sandbox:p2",
            Map.of("CLAUDE_CODE_OAUTH_TOKEN", "PLAYFORGE_CLAUDE_TOKEN"),
            List.of(new DockerRunSpec.Mount("C:\\Users\\泽泽\\.codex\\auth.json", "/mnt/creds/codex/auth.json")),
            "2", "4g", 512, Map.of("playforge.pool", "app"));

    final RecordingRunner runner = new RecordingRunner();
    final CliDockerOps ops = new CliDockerOps(runner, "docker",
            name -> name.equals("PLAYFORGE_CLAUDE_TOKEN") ? "secret-token-123" : null);

    @Test
    void runCommandPassesTokenByNameOnly() {
        String id = ops.runDetached(SPEC);

        assertThat(id).isEqualTo("abc123");
        List<String> cmd = runner.cmds.getFirst();
        assertThat(cmd).noneMatch(a -> a.contains("secret-token-123"));
        assertThat(String.join(" ", cmd)).contains("-e CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(runner.envs.getFirst()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "secret-token-123");
    }

    @Test
    void runCommandHasResourceLimitsAndReadonlyMount() {
        ops.runDetached(SPEC);
        String cmd = String.join(" ", runner.cmds.getFirst());
        assertThat(cmd).startsWith("docker run -d")
                .contains("--cpus 2").contains("--memory 4g").contains("--pids-limit 512")
                .contains("--mount type=bind,source=C:\\Users\\泽泽\\.codex\\auth.json,target=/mnt/creds/codex/auth.json,readonly")
                .endsWith("playforge-sandbox:p2");
    }

    @Test
    void runCommandHasPoolLabel() {
        ops.runDetached(SPEC);
        assertThat(String.join(" ", runner.cmds.getFirst())).contains("--label playforge.pool=app");
    }

    @Test
    void removeLabeledListsThenRemoves() {
        runner.stdout = "id1\nid2\n";
        int removed = ops.removeLabeled(Map.of("playforge.pool", "app"));

        assertThat(removed).isEqualTo(2);
        assertThat(runner.cmds.get(0)).containsExactly("docker", "ps", "-aq", "--filter", "label=playforge.pool=app");
        assertThat(runner.cmds.get(1)).containsExactly("docker", "rm", "-f", "id1", "id2");
    }

    @Test
    void removeLabeledWithNothingToRemoveRunsNoRm() {
        runner.stdout = "";
        assertThat(ops.removeLabeled(Map.of("playforge.pool", "app"))).isZero();
        assertThat(runner.cmds).hasSize(1);
    }

    @Test
    void missingHostVariableIsNotPassed() {
        CliDockerOps noToken = new CliDockerOps(runner, "docker", name -> null);
        noToken.runDetached(SPEC);
        assertThat(runner.envs.getFirst()).doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
    }

    @Test
    void copyInSeedsTemplate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path host) {
        ops.copyIn("abc123", host);
        String script = String.join(" ", runner.cmds.getFirst());
        assertThat(script).contains("cp -an /template/. /workspace/").contains("/template/node_modules").doesNotContain("cp -a /template/. ");
    }

    @Test
    void exportRejectsPathsOutsideWorkspace(@org.junit.jupiter.api.io.TempDir java.nio.file.Path host) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ops.copyDirOut("abc123", "../etc", host))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ops.copyDirOut("abc123", "/etc", host))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execUsesWorkspaceAndStdin() {
        byte[] stdin = "做一个\"游戏\"".getBytes(StandardCharsets.UTF_8);
        ops.exec("abc123", List.of("claude", "-p"), stdin, Duration.ofSeconds(5), new CancellationToken(), l -> { });

        assertThat(runner.cmds.getFirst()).containsExactly("docker", "exec", "-i", "-w", "/workspace", "abc123", "claude", "-p");
        assertThat(runner.stdins.getFirst()).isEqualTo(stdin);
    }
}
