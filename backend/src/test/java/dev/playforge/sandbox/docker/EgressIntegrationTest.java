package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ProcessRunner;
import dev.playforge.sandbox.BlockedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Docker: sandboxes on the internal network reach the internet only through the gateway. Skipped without Docker. */
@Tag("docker")
class EgressIntegrationTest {

    static final String IMAGE = "playforge-sandbox:p4";
    static CliDockerOps ops;
    static EgressGateway gateway;
    static ContainerPool pool;

    @TempDir
    Path host;

    @BeforeAll
    static void setUp() {
        ops = new CliDockerOps(new ProcessRunner(), "docker", System::getenv);
        assumeTrue(ops.available(), "Docker is not available");
        gateway = new EgressGateway(ops, IMAGE, Path.of("../sandbox/egress/egress.mjs"),
                List.of("registry.npmmirror.com", "cdn.npmmirror.com"), System.getenv().getOrDefault("PLAYFORGE_EGRESS_UPSTREAM", ""));
        DockerRunSpec spec = new DockerRunSpec(IMAGE, Map.of(), List.of(), "2", "4g", 512, Map.of("playforge.pool", "egress-it"))
                .withNetwork(EgressGateway.NETWORK, gateway.sandboxEnv());
        pool = new ContainerPool(ops, spec, 1, false, gateway::ensure);
        pool.start();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.shutdown();
        if (gateway != null) gateway.remove();
    }

    DockerSandbox sandbox() {
        return new DockerSandbox(pool.acquire(Duration.ofSeconds(90)), pool, ops, gateway);
    }

    static int sh(DockerSandbox s, String script, List<String> out) {
        return s.exec(List.of("sh", "-c", script + " 2>&1"), null, Duration.ofSeconds(120), new CancellationToken(), out::add).exitCode();
    }

    @Test
    void allowedHostThroughTheGateway() {
        try (DockerSandbox s = sandbox()) {
            List<String> out = new CopyOnWriteArrayList<>();
            int exit = sh(s, "npm view vite@5 version --registry https://registry.npmmirror.com/ | tail -1", out);
            assertThat(exit).as(String.join("\n", out)).isZero();
            assertThat(String.join("\n", out)).containsPattern("5\\.\\d+\\.\\d+");
        }
    }

    @Test
    void otherHostIsRefusedAndReported() {
        try (DockerSandbox s = sandbox()) {
            Instant start = Instant.now();
            List<String> out = new CopyOnWriteArrayList<>();
            int exit = sh(s, "npm view vite version --registry https://example.com/ --fetch-retries 0", out);
            assertThat(exit).isNotZero();
            List<BlockedRequest> blocked = s.blockedSince(start);
            assertThat(blocked).extracting(BlockedRequest::host).contains("example.com");
            assertThat(blocked).allMatch(b -> b.port() == 443);
        }
    }

    @Test
    void noRouteWithoutTheProxy() {
        try (DockerSandbox s = sandbox()) {
            List<String> out = new CopyOnWriteArrayList<>();
            int exit = sh(s, "env -u HTTPS_PROXY -u HTTP_PROXY -u NODE_USE_ENV_PROXY node -e "
                    + "\"fetch('https://1.1.1.1',{signal:AbortSignal.timeout(8000)}).then(()=>console.log('REACHED'),e=>{console.log('NO-ROUTE',e.cause?.code||e.name);process.exit(3)})\"", out);
            assertThat(String.join("\n", out)).doesNotContain("REACHED").contains("NO-ROUTE");
            assertThat(exit).isEqualTo(3);
        }
    }

    @Test
    void hardCheckPlaytestStillLoadsTheLocalGame() {
        try (DockerSandbox s = sandbox()) {
            s.syncIn(host); // empty project: the sandbox seeds the template
            List<String> out = new CopyOnWriteArrayList<>();
            assertThat(sh(s, "npx vite build >/dev/null", out)).as(String.join("\n", out)).isZero();
            out.clear();
            sh(s, "node /opt/playtest/run.mjs --dist /workspace/dist", out);
            String report = out.stream().filter(l -> l.startsWith("{\"playtest\":")).reduce((a, b) -> b).orElse("");
            assertThat(report).as(String.join("\n", out)).contains("\"name\":\"load\",\"passed\":true");
        }
    }
}
