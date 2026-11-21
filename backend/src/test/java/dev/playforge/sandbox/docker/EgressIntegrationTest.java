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

/**
 * Real Docker: sandboxes on the internal network reach the internet only through the gateway. Skipped without Docker.
 * Uses its own gateway, network and alias, so it runs safely next to a live backend.
 */
@Tag("docker")
class EgressIntegrationTest {

    static final String IMAGE = "playforge-sandbox:p4";
    static final EgressGateway.Names NAMES =
            new EgressGateway.Names("playforge-egress-it", "playforge-sandbox-it", "egress-it", "it");
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
                List.of("registry.npmmirror.com", "cdn.npmmirror.com"),
                System.getenv().getOrDefault("PLAYFORGE_EGRESS_UPSTREAM", ""), NAMES);
        DockerRunSpec spec = new DockerRunSpec(IMAGE, Map.of(), List.of(), "2", "4g", 512, Map.of("playforge.pool", "egress-it"))
                .withNetwork(gateway.network(), gateway.sandboxEnv());
        pool = new ContainerPool(ops, spec, 1, false, gateway::ensure);
        pool.start();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.shutdown();
        if (gateway != null) {
            gateway.remove();
            docker("network", "rm", NAMES.network());
        }
    }

    /** Runs a docker CLI command on the host; returns its output (stdout lines). */
    static List<String> docker(String... args) {
        List<String> out = new CopyOnWriteArrayList<>();
        List<String> cmd = new java.util.ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        new ProcessRunner().run(cmd, Map.of(), null, Duration.ofSeconds(30), new CancellationToken(), out::add);
        return out;
    }

    /** Runs a Node script (via stdin: double quotes in process arguments don't survive Windows) in the sandbox. */
    static String node(DockerSandbox s, String js) {
        List<String> out = new CopyOnWriteArrayList<>();
        s.exec(List.of("sh", "-c", "node - 2>&1"), js, Duration.ofSeconds(90), new CancellationToken(), out::add);
        return String.join("\n", out);
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
            // The script goes in on stdin: double quotes in process arguments don't survive Windows.
            String js = "fetch('https://1.1.1.1',{signal:AbortSignal.timeout(8000)}).then(()=>console.log('REACHED'),"
                    + "e=>{console.log('NO-ROUTE',e.cause?.code||e.name);process.exit(3)})";
            int exit = s.exec(List.of("sh", "-c", "env -u HTTPS_PROXY -u HTTP_PROXY -u NODE_USE_ENV_PROXY node - 2>&1"),
                    js, Duration.ofSeconds(60), new CancellationToken(), out::add).exitCode();
            assertThat(String.join("\n", out)).doesNotContain("REACHED").contains("NO-ROUTE");
            assertThat(exit).isEqualTo(3);
        }
    }

    /**
     * DNS is a channel too: if the internal network forwarded lookups to an outside resolver (CVE-2024-29018, older
     * Docker), code could leak secrets in query names. Internal names must resolve, external ones must not.
     */
    @Test
    void noExternalDnsFromTheSandbox() {
        try (DockerSandbox s = sandbox()) {
            String random = "pf-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".example.com";
            String js = """
                    const dns = require('dns');
                    const res = new dns.Resolver({ timeout: 3000, tries: 1 });
                    const look = (h) => new Promise((ok) => dns.lookup(h, (e, a) => ok('lookup ' + h + ' ' + (e ? 'FAIL ' + e.code : 'RESOLVED ' + a))));
                    const r4 = (h) => new Promise((ok) => res.resolve4(h, (e, a) => ok('resolve4 ' + h + ' ' + (e ? 'FAIL ' + e.code : 'RESOLVED ' + a))));
                    setTimeout(() => { console.log('TIMEOUT'); process.exit(0); }, 40000).unref();
                    Promise.all([look('%1$s'), look('example.com'), r4('example.com'), look('%2$s'), r4('%2$s')])
                      .then((l) => console.log(l.join('\\n')));
                    """.formatted(NAMES.alias(), random);
            String out = node(s, js);
            // Control: Docker's embedded DNS works, so the failures below are refusals, not a broken resolver.
            assertThat(out).as(out).containsPattern("lookup " + NAMES.alias() + " RESOLVED \\d");
            assertThat(out).as(out).contains("lookup example.com FAIL", "resolve4 example.com FAIL",
                    "lookup " + random + " FAIL", "resolve4 " + random + " FAIL");
        }
    }

    /** The host's own services (the backend on 8090, a Docker API on 2375) must be out of a sandbox's reach. */
    @Test
    void hostIsNotReachableFromTheSandbox() {
        String internalGw = String.join("", docker("network", "inspect", "-f",
                "{{range .IPAM.Config}}{{.Gateway}}{{end}}", NAMES.network())).trim();
        String bridgeGw = String.join("", docker("network", "inspect", "-f",
                "{{range .IPAM.Config}}{{.Gateway}}{{end}}", "bridge")).trim();
        java.util.Set<String> targets = new java.util.LinkedHashSet<>(List.of(
                "host.docker.internal:8090", "host.docker.internal:2375", "host.docker.internal:8080",
                "gateway.docker.internal:2375", "172.17.0.1:2375", "172.17.0.1:8090"));
        try (DockerSandbox s = sandbox()) {
            // An internal network may have no gateway in its IPAM config; its subnet's .1 is the host side anyway.
            String gatewayIp = ops.containerIp(NAMES.container(), NAMES.network());
            String subnetOne = gatewayIp.isEmpty() ? "" : gatewayIp.replaceFirst("\\.\\d+$", ".1");
            for (String gw : List.of(internalGw, bridgeGw, subnetOne)) {
                if (!gw.isEmpty()) targets.addAll(List.of(gw + ":2375", gw + ":2376", gw + ":8090", gw + ":22"));
            }
            StringBuilder list = new StringBuilder();
            for (String t : targets) list.append(list.isEmpty() ? "" : ",").append('\'').append(t).append('\'');
            String js = """
                    const net = require('net');
                    const probe = (t) => new Promise((ok) => {
                      const i = t.lastIndexOf(':');
                      const s = net.connect({ host: t.slice(0, i), port: Number(t.slice(i + 1)) });
                      const done = (m) => { s.destroy(); ok('PROBE ' + t + ' ' + m); };
                      s.setTimeout(4000, () => done('TIMEOUT'));
                      s.on('connect', () => done('CONNECTED'));
                      s.on('error', (e) => done('ERR ' + e.code));
                    });
                    Promise.all([%s].map(probe)).then((l) => console.log(l.join('\\n')));
                    """.formatted(list);
            String out = node(s, js);
            // Measured on Docker Desktop 29: host names don't resolve (EAI_AGAIN), 172.17.0.1 has no route, and the
            // internal subnet's .1 answers with a reset (ECONNREFUSED): the Docker host's kernel is reachable there,
            // so a host service listening on all interfaces would be too. Nothing may accept a connection.
            assertThat(out.lines().filter(l -> l.startsWith("PROBE "))).as(out).hasSize(targets.size());
            assertThat(out).as(out).doesNotContain("CONNECTED");
        }
    }

    /** The gateway sits on the bridge and could reach the host; asking it for internal addresses must get a 403. */
    @Test
    void gatewayRefusesInternalAddresses() {
        try (DockerSandbox s = sandbox()) {
            String js = """
                    const net = require('net');
                    const ask = (req, label) => new Promise((ok) => {
                      const s = net.connect(3128, '%s');
                      let b = '';
                      s.setTimeout(10000, () => { s.destroy(); ok('ASK ' + label + ' TIMEOUT'); });
                      s.on('connect', () => s.write(req));
                      s.on('data', (d) => { b += d; const i = b.indexOf('\\r\\n'); if (i >= 0) { s.destroy(); ok('ASK ' + label + ' ' + b.slice(0, i)); } });
                      s.on('error', (e) => ok('ASK ' + label + ' ERR ' + e.code));
                    });
                    const connect = (t) => ask('CONNECT ' + t + ' HTTP/1.1\\r\\nHost: ' + t + '\\r\\n\\r\\n', t);
                    Promise.all([
                      connect('host.docker.internal:443'), connect('172.17.0.1:443'), connect('gateway.docker.internal:443'),
                      connect('127.0.0.1:443'), connect('localhost:3128'), connect('169.254.169.254:443'),
                      connect('host.docker.internal:8090'), connect('[::1]:443'),
                      ask('GET http://host.docker.internal:8090/ HTTP/1.1\\r\\nHost: host.docker.internal:8090\\r\\n\\r\\n', 'GET-8090'),
                    ]).then((l) => console.log(l.join('\\n')));
                    """.formatted(NAMES.alias());
            String out = node(s, js);
            List<String> answers = out.lines().filter(l -> l.startsWith("ASK ")).toList();
            assertThat(answers).as(out).hasSize(9).allMatch(l -> l.endsWith(" 403 Forbidden"));
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
