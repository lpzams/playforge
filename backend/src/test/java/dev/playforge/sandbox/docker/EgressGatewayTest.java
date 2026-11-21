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


    static String refused(String client, String host) {
        return line("2026-11-20T10:00:01Z", client, host, 443, false);
    }

    @Test
    void parseKeepsRefusalsFromThisClientOnly() {
        List<String> lines = List.of(
                refused("172.30.0.5", "evil.com"),
                line("2026-11-20T10:00:02Z", "172.30.0.5", "api.anthropic.com", 443, true),
                refused("172.30.0.6", "evil.com"),
                "not json",
                refused("::ffff:172.30.0.5", "evil.com"),
                line("2026-11-20T10:00:05Z", "172.30.0.5", "x.io", 80, false));

        assertThat(EgressGateway.parse(lines, "172.30.0.5")).containsExactly(
                new BlockedRequest("evil.com", 443, 2), new BlockedRequest("x.io", 80, 1));
    }

    @Test
    void parseShowsOnlyTheMainDomainOfPlausibleNames() {
        // Whatever the sandbox puts in a host name ends up in the published story: no room for a smuggled secret.
        List<String> lines = List.of(
                refused("172.30.0.5", "telemetry.tracker.example.com"),
                refused("172.30.0.5", "sk-ant-oat01-abcdefghijklmnopqrstuvwxyz0123456789.evil.com"),
                refused("172.30.0.5", "abcdefghijklmnopqrstuvwxyz0123456789abcdef.com"),
                refused("172.30.0.5", "SECRET TOKEN"),
                refused("172.30.0.5", "(invalid)"),
                refused("172.30.0.5", "10.0.0.8"));

        assertThat(EgressGateway.parse(lines, "172.30.0.5")).containsExactly(
                new BlockedRequest("example.com", 443, 1), new BlockedRequest("evil.com", 443, 1),
                new BlockedRequest("(invalid)", 443, 3), new BlockedRequest("10.0.0.8", 443, 1));
    }

    @Test
    void parseListsAtMostTwentyTargets() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) lines.add(refused("172.30.0.5", "host" + i + ".com"));
        assertThat(EgressGateway.parse(lines, "172.30.0.5")).hasSize(20);
    }

    @Test
    void blockedReadsOnlyLinesAfterTheCursor() throws IOException {
        ops.catOutput = List.of("7", refused("172.30.0.5", "evil.com"));
        EgressGateway.Slice slice = gateway(List.of("a.com")).blocked("172.30.0.5", 6);

        assertThat(ops.log).anyMatch(l -> l.startsWith("exec playforge-egress sh -c") && l.contains("tail -n +7 /tmp/egress.jsonl"));
        assertThat(slice.blocked()).containsExactly(new BlockedRequest("evil.com", 443, 1));
        assertThat(slice.next()).isEqualTo(7);
    }

    @Test
    void aRecreatedGatewayLogIsReadFromTheStart() throws IOException {
        ops.catOutput = List.of("2"); // the log is shorter than the cursor: the gateway was recreated
        EgressGateway.Slice slice = gateway(List.of("a.com")).blocked("172.30.0.5", 50);

        assertThat(ops.log).anyMatch(l -> l.contains("tail -n +1 /tmp/egress.jsonl"));
        assertThat(slice.next()).isLessThan(50);
    }

    @Test
    void lineCountReadsTheLogLength() throws IOException {
        ops.catOutput = List.of("12");
        assertThat(gateway(List.of("a.com")).lineCount()).isEqualTo(12);
    }

    @Test
    void unreadableLogMeansNothingBlocked() throws IOException {
        ops.catExit = 1;
        EgressGateway g = gateway(List.of("a.com"));
        assertThat(g.blocked("172.30.0.5", 3).blocked()).isEmpty();
        assertThat(g.blocked("172.30.0.5", 3).next()).isEqualTo(3);
        assertThat(g.blocked("", 3).blocked()).isEmpty();
    }

    @Test
    void aGatewayThatCannotReachTheInternetIsRemoved() throws IOException {
        Ops failing = new Ops() {
            @Override public void connect(String network, String containerId) {
                throw new IllegalStateException("connect failed");
            }
            @Override public void remove(String id) { log.add("remove " + id); }
        };
        Path script = dir.resolve("egress.mjs");
        Files.writeString(script, "// gateway");
        EgressGateway g = new EgressGateway(failing, "img", script, List.of("a.com"), "");

        org.assertj.core.api.Assertions.assertThatThrownBy(g::ensure).hasMessageContaining("connect failed");
        assertThat(failing.log).contains("remove gw1");
    }
}
