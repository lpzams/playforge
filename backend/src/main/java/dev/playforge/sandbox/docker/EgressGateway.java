package dev.playforge.sandbox.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.BlockedRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way out of the sandbox network. Sandboxes sit on an internal Docker network (no route out); this
 * container sits on that network and on the default bridge and runs sandbox/egress/egress.mjs, an HTTPS-only
 * proxy that allows a list of hosts and logs every decision.
 */
public class EgressGateway {

    private static final Logger log = LoggerFactory.getLogger(EgressGateway.class);

    public static final String NETWORK = "playforge-sandbox";
    public static final String NAME = "playforge-egress";
    public static final String PROXY = "http://egress:3128";
    static final String LOG = "/tmp/egress.jsonl";
    static final String LABEL = "playforge.egress";
    static final String CONFIG_LABEL = "playforge.egress.config";
    private static final String SCRIPT_TARGET = "/egress/egress.mjs";
    /** Container and host clocks may differ a little; a refusal this much before the step still counts. */
    private static final Duration SKEW = Duration.ofSeconds(2);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DockerOps ops;
    private final String image;
    private final Path script;
    private final List<String> allow;
    private final String upstream;

    public EgressGateway(DockerOps ops, String image, Path script, List<String> allow, String upstream) {
        this.ops = ops;
        this.image = image;
        this.script = script.toAbsolutePath().normalize();
        this.allow = List.copyOf(allow);
        this.upstream = upstream == null ? "" : upstream.trim();
    }

    /** Makes sure the network exists and a gateway with this configuration runs. Idempotent; cheap when all is up. */
    public synchronized void ensure() {
        ops.ensureNetwork(NETWORK, true);
        String hash = configHash();
        if (!ops.findRunning(Map.of(LABEL, "app", CONFIG_LABEL, hash)).isEmpty()) return;
        ops.removeLabeled(Map.of(LABEL, "app"));
        Map<String, String> env = new HashMap<>();
        env.put("EGRESS_ALLOW", String.join(",", allow));
        if (!upstream.isEmpty()) env.put("EGRESS_UPSTREAM", upstream);
        DockerRunSpec spec = new DockerRunSpec(image, Map.of(),
                List.of(new DockerRunSpec.Mount(script.toString(), SCRIPT_TARGET)), "0.5", "128m", 64,
                Map.of(LABEL, "app", CONFIG_LABEL, hash), NAME, NETWORK, "egress", env,
                List.of("node", SCRIPT_TARGET));
        String id = ops.runDetached(spec);
        ops.connect("bridge", id);
        log.info("egress gateway started (allow: {}{})", String.join(", ", allow),
                upstream.isEmpty() ? "" : ", upstream " + upstream);
    }

    public void remove() {
        try {
            ops.removeLabeled(Map.of(LABEL, "app"));
        } catch (RuntimeException e) {
            log.warn("could not remove the egress gateway: {}", e.getMessage());
        }
    }

    /** Variables that send a sandbox's traffic through the gateway. */
    public Map<String, String> sandboxEnv() {
        return Map.of("HTTPS_PROXY", PROXY, "HTTP_PROXY", PROXY, "NO_PROXY", "localhost,127.0.0.1",
                "NODE_USE_ENV_PROXY", "1", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
    }

    /** Requests from {@code clientIp} the gateway refused since {@code since}; empty when the log can't be read. */
    public List<BlockedRequest> blocked(String clientIp, Instant since) {
        if (clientIp == null || clientIp.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        ExecResult r = ops.exec(NAME, List.of("cat", LOG), null, Duration.ofSeconds(10), new CancellationToken(), lines::add);
        if (r.exitCode() != 0) return List.of();
        return parse(lines, clientIp, since);
    }

    static List<BlockedRequest> parse(List<String> lines, String clientIp, Instant since) {
        Instant from = since.minus(SKEW);
        Map<String, BlockedRequest> byTarget = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode e;
            try {
                e = JSON.readTree(line);
            } catch (IOException ex) {
                continue;
            }
            if (e == null || !e.isObject() || e.path("allowed").asBoolean(true)) continue;
            if (!clientIp.equals(e.path("client").asText().replaceFirst("^::ffff:", ""))) continue;
            Instant ts;
            try {
                ts = Instant.parse(e.path("ts").asText());
            } catch (RuntimeException ex) {
                continue;
            }
            if (ts.isBefore(from)) continue;
            String host = e.path("host").asText();
            int port = e.path("port").asInt();
            byTarget.merge(host + ":" + port, new BlockedRequest(host, port, 1),
                    (a, b) -> new BlockedRequest(a.host(), a.port(), a.count() + 1));
        }
        return List.copyOf(byTarget.values());
    }

    private String configHash() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((image + "|" + String.join(",", allow) + "|" + upstream + "|").getBytes(StandardCharsets.UTF_8));
            sha.update(Files.readAllBytes(script));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (IOException e) {
            throw new IllegalStateException("egress gateway script not readable: " + script, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
