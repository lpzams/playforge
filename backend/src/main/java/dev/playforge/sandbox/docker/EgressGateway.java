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
    static final String LOG = "/tmp/egress.jsonl";
    static final String LABEL = "playforge.egress";
    static final String CONFIG_LABEL = "playforge.egress.config";
    private static final String SCRIPT_TARGET = "/egress/egress.mjs";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DockerOps ops;
    private final String image;
    private final Path script;
    private final List<String> allow;
    private final String upstream;
    private final Names names;

    /**
     * Docker names of one gateway: its container, its internal network, the alias sandboxes reach it by, and the
     * value of its {@link #LABEL}. Tests pass their own so they never touch the app's gateway.
     */
    public record Names(String container, String network, String alias, String labelValue) {
        public static final Names APP = new Names(NAME, NETWORK, "egress", "app");
    }

    public EgressGateway(DockerOps ops, String image, Path script, List<String> allow, String upstream) {
        this(ops, image, script, allow, upstream, Names.APP);
    }

    public EgressGateway(DockerOps ops, String image, Path script, List<String> allow, String upstream, Names names) {
        this.names = names;
        this.ops = ops;
        this.image = image;
        this.script = script.toAbsolutePath().normalize();
        this.allow = List.copyOf(allow);
        this.upstream = upstream == null ? "" : upstream.trim();
    }

    /** Makes sure the network exists and a gateway with this configuration runs. Idempotent; cheap when all is up. */
    public synchronized void ensure() {
        ops.ensureNetwork(names.network(), true);
        String hash = configHash();
        if (!ops.findRunning(Map.of(LABEL, names.labelValue(), CONFIG_LABEL, hash)).isEmpty()) return;
        ops.removeLabeled(Map.of(LABEL, names.labelValue()));
        Map<String, String> env = new HashMap<>();
        env.put("EGRESS_ALLOW", String.join(",", allow));
        if (!upstream.isEmpty()) env.put("EGRESS_UPSTREAM", upstream);
        DockerRunSpec spec = new DockerRunSpec(image, Map.of(),
                List.of(new DockerRunSpec.Mount(script.toString(), SCRIPT_TARGET)), "0.5", "128m", 64,
                Map.of(LABEL, names.labelValue(), CONFIG_LABEL, hash), names.container(), names.network(), names.alias(), env,
                List.of("node", SCRIPT_TARGET));
        String id = ops.runDetached(spec);
        try {
            ops.connect("bridge", id);
        } catch (RuntimeException e) {
            // Without the bridge it can't reach anything, yet it carries the right label: never leave it running.
            ops.remove(id);
            throw e;
        }
        log.info("egress gateway started (allow: {}{})", String.join(", ", allow),
                upstream.isEmpty() ? "" : ", upstream " + upstream);
    }

    /** The internal network sandboxes join to use this gateway. */
    public String network() {
        return names.network();
    }

    public void remove() {
        try {
            ops.removeLabeled(Map.of(LABEL, names.labelValue()));
        } catch (RuntimeException e) {
            log.warn("could not remove the egress gateway: {}", e.getMessage());
        }
    }

    /** Variables that send a sandbox's traffic through the gateway. */
    public Map<String, String> sandboxEnv() {
        String proxy = "http://" + names.alias() + ":3128";
        return Map.of("HTTPS_PROXY", proxy, "HTTP_PROXY", proxy, "NO_PROXY", "localhost,127.0.0.1",
                "NODE_USE_ENV_PROXY", "1", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
    }

    /**
     * Refusals read from the log after line {@code next}: what a sandbox's later steps start from. Positions in the
     * log, not timestamps, so the container and host clocks never have to agree.
     */
    public record Slice(List<BlockedRequest> blocked, int next) {}

    /** Number of lines in the gateway's log: a new sandbox's starting position. 0 when it can't be read. */
    public int lineCount() {
        List<String> out = new ArrayList<>();
        ExecResult r = ops.exec(names.container(), List.of("sh", "-c", "f=" + LOG + "; touch $f; wc -l < $f"), null,
                Duration.ofSeconds(10), new CancellationToken(), out::add);
        return r.exitCode() == 0 && !out.isEmpty() ? count(out.getFirst()) : 0;
    }

    /** Requests from {@code clientIp} refused after line {@code from}; nothing (and the same position) when unreadable. */
    public Slice blocked(String clientIp, int from) {
        if (clientIp == null || clientIp.isBlank()) return new Slice(List.of(), from);
        List<String> out = read(from);
        if (out == null) return new Slice(List.of(), from);
        int total = count(out.getFirst());
        if (total < from) { // the gateway was recreated: its log starts over
            from = 0;
            out = read(0);
            if (out == null) return new Slice(List.of(), 0);
        }
        List<String> lines = out.subList(1, out.size());
        return new Slice(parse(lines, clientIp), from + lines.size());
    }

    /** First line: the log's length; then every line after {@code from}. Null when the command fails. */
    private List<String> read(int from) {
        List<String> out = new ArrayList<>();
        ExecResult r = ops.exec(names.container(), List.of("sh", "-c",
                        "f=" + LOG + "; touch $f; wc -l < $f; tail -n +" + (from + 1) + " " + LOG), null,
                Duration.ofSeconds(10), new CancellationToken(), out::add);
        return r.exitCode() == 0 && !out.isEmpty() ? out : null;
    }

    private static int count(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final java.util.regex.Pattern NAME_LIKE = java.util.regex.Pattern.compile("[a-z0-9.-]{1,253}");
    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    /** Targets listed per step; the rest are dropped (the full detail stays in the gateway's own log). */
    static final int MAX_TARGETS = 20;

    static List<BlockedRequest> parse(List<String> lines, String clientIp) {
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
            String host = publicName(e.path("host").asText());
            int port = e.path("port").asInt();
            String key = host + ":" + port;
            if (!byTarget.containsKey(key) && byTarget.size() >= MAX_TARGETS) continue;
            byTarget.merge(key, new BlockedRequest(host, port, 1),
                    (a, b) -> new BlockedRequest(a.host(), a.port(), a.count() + 1));
        }
        return List.copyOf(byTarget.values());
    }

    /**
     * What may appear in the (published) run story: the main domain of a plausible name, e.g. "*.example.com" for
     * "telemetry.tracker.example.com". Untrusted code chooses the host, so a smuggled secret must not survive.
     */
    static String publicName(String host) {
        String h = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        if (IPV4.matcher(h).matches()) return h;
        if (!NAME_LIKE.matcher(h).matches()) return "(invalid)";
        String[] labels = h.split("\\.");
        if (labels.length < 2) return "(invalid)";
        String a = labels[labels.length - 2];
        String b = labels[labels.length - 1];
        if (a.isEmpty() || b.isEmpty() || a.length() > 30 || b.length() > 30) return "(invalid)";
        // A subdomain keeps a "*." so "ab.chatgpt.com" never reads as the allowed "chatgpt.com".
        return (labels.length > 2 ? "*." : "") + a + "." + b;
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
