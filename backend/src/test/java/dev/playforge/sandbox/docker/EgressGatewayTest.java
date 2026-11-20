package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.BlockedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EgressGatewayTest {

    /** Records the calls the gateway makes; answers findRunning / exec from fields. */
    static class Ops extends ContainerPoolTest.FakeDockerOps {
        final List<DockerRunSpec> started = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        List<String> running = List.of();
        Map<String, String> runningLabels = Map.of();
        List<String> catOutput = List.of();
        int catExit = 0;

        @Override public String runDetached(DockerRunSpec spec) {
            started.add(spec);
            log.add("run " + spec.name());
            return "gw1";
        }
        @Override public int removeLabeled(Map<String, String> labels) {
            log.add("removeLabeled " + labels);
            return 0;
        }
        @Override public List<String> findRunning(Map<String, String> labels) {
            return labels.equals(runningLabels) ? running : List.of();
        }
        @Override public void ensureNetwork(String name, boolean internal) { log.add("network " + name + " " + internal); }
        @Override public void connect(String network, String containerId) { log.add("connect " + network + " " + containerId); }
        @Override public ExecResult exec(String id, List<String> cmd, byte[] stdin, Duration t, CancellationToken c, Consumer<String> out) {
            log.add("exec " + id + " " + String.join(" ", cmd));
            catOutput.forEach(out);
            return new ExecResult(catExit, "");
        }
    }

    @TempDir
    Path dir;

    final Ops ops = new Ops();

    EgressGateway gateway(List<String> allow) throws IOException {
        Path script = dir.resolve("egress.mjs");
        if (!Files.exists(script)) Files.writeString(script, "// gateway");
        return new EgressGateway(ops, "playforge-sandbox:p4", script, allow, "");
    }

    @Test
    void ensureCreatesNetworkAndGateway() throws IOException {
        gateway(List.of("api.anthropic.com", "chatgpt.com")).ensure();

        assertThat(ops.log).containsExactly("network playforge-sandbox true", "removeLabeled {playforge.egress=app}",
                "run playforge-egress", "connect bridge gw1");
        DockerRunSpec spec = ops.started.getFirst();
        assertThat(spec.image()).isEqualTo("playforge-sandbox:p4");
        assertThat(spec.network()).isEqualTo("playforge-sandbox");
        assertThat(spec.networkAlias()).isEqualTo("egress");
        assertThat(spec.cpus()).isEqualTo("0.5");
        assertThat(spec.memory()).isEqualTo("128m");
        assertThat(spec.envFromHost()).isEmpty();
        assertThat(spec.mounts()).containsExactly(new DockerRunSpec.Mount(
                dir.resolve("egress.mjs").toAbsolutePath().toString(), "/egress/egress.mjs"));
        assertThat(spec.env()).containsEntry("EGRESS_ALLOW", "api.anthropic.com,chatgpt.com").doesNotContainKey("EGRESS_UPSTREAM");
        assertThat(spec.labels()).containsEntry("playforge.egress", "app").containsKey("playforge.egress.config");
        assertThat(spec.command()).containsExactly("node", "/egress/egress.mjs");
    }

    @Test
    void upstreamIsPassedToTheGateway() throws IOException {
        Files.writeString(dir.resolve("egress.mjs"), "// gateway");
        new EgressGateway(ops, "img", dir.resolve("egress.mjs"), List.of("a.com"), "http://host.docker.internal:7899").ensure();
        assertThat(ops.started.getFirst().env()).containsEntry("EGRESS_UPSTREAM", "http://host.docker.internal:7899");
    }

    @Test
    void ensureKeepsAMatchingGateway() throws IOException {
        EgressGateway g = gateway(List.of("a.com"));
        g.ensure();
        ops.runningLabels = ops.started.getFirst().labels();
        ops.running = List.of("gw1");
        ops.log.clear();

        g.ensure();

        assertThat(ops.log).containsExactly("network playforge-sandbox true");
    }

    @Test
    void ensureReplacesAGatewayWithOtherConfig() throws IOException {
        gateway(List.of("a.com")).ensure();
        ops.runningLabels = ops.started.getFirst().labels();
        ops.running = List.of("gw1");
        ops.log.clear();

        gateway(List.of("a.com", "b.com")).ensure();

        assertThat(ops.log).contains("removeLabeled {playforge.egress=app}", "run playforge-egress");
    }

    @Test
    void sandboxEnvPointsAtTheGateway() throws IOException {
        assertThat(gateway(List.of("a.com")).sandboxEnv()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "HTTPS_PROXY", "http://egress:3128", "HTTP_PROXY", "http://egress:3128",
                "NO_PROXY", "localhost,127.0.0.1", "NODE_USE_ENV_PROXY", "1",
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"));
    }

    static String line(String ts, String client, String host, int port, boolean allowed) {
        return "{\"ts\":\"" + ts + "\",\"client\":\"" + client + "\",\"host\":\"" + host + "\",\"port\":" + port
                + ",\"allowed\":" + allowed + "}";
    }

    @Test
    void parseKeepsRefusalsFromThisClientSinceTheStep() {
        Instant since = Instant.parse("2026-11-20T10:00:00Z");
        List<String> lines = List.of(
                line("2026-11-20T09:59:50Z", "172.30.0.5", "old.com", 443, false),
                line("2026-11-20T09:59:59Z", "172.30.0.5", "skew.com", 443, false),
                line("2026-11-20T10:00:01Z", "172.30.0.5", "evil.com", 443, false),
                line("2026-11-20T10:00:02Z", "172.30.0.5", "api.anthropic.com", 443, true),
                line("2026-11-20T10:00:03Z", "172.30.0.6", "evil.com", 443, false),
                "not json",
                line("2026-11-20T10:00:04Z", "::ffff:172.30.0.5", "evil.com", 443, false),
                line("2026-11-20T10:00:05Z", "172.30.0.5", "x.io", 80, false));

        assertThat(EgressGateway.parse(lines, "172.30.0.5", since)).containsExactly(
                new BlockedRequest("skew.com", 443, 1), new BlockedRequest("evil.com", 443, 2), new BlockedRequest("x.io", 80, 1));
    }

    @Test
    void blockedReadsTheLogInTheGateway() throws IOException {
        ops.catOutput = List.of(line("2026-11-20T10:00:01Z", "172.30.0.5", "evil.com", 443, false));
        List<BlockedRequest> blocked = gateway(List.of("a.com")).blocked("172.30.0.5", Instant.parse("2026-11-20T10:00:00Z"));

        assertThat(ops.log).contains("exec playforge-egress cat /tmp/egress.jsonl");
        assertThat(blocked).containsExactly(new BlockedRequest("evil.com", 443, 1));
    }

    @Test
    void unreadableLogMeansNothingBlocked() throws IOException {
        ops.catExit = 1;
        assertThat(gateway(List.of("a.com")).blocked("172.30.0.5", Instant.EPOCH)).isEmpty();
        assertThat(gateway(List.of("a.com")).blocked("", Instant.EPOCH)).isEmpty();
    }
}
