package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessTimeoutException;
import dev.playforge.sandbox.Sandbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real playtest inside the Docker sandbox: {@code npm run build}, then the Playwright harness
 * ({@code /opt/playtest/run.mjs}) against /workspace/dist.
 */
@Component
@ConditionalOnProperty(name = "playforge.sandbox.provider", havingValue = "docker")
public class DockerPlaytester implements Playtester {

    /** Removing dist first guarantees we never playtest (or export) output from an earlier build. */
    static final List<String> BUILD = List.of("sh", "-c", "rm -rf dist && npm run build");
    static final List<String> HARNESS = List.of("node", "/opt/playtest/run.mjs", "--dist", "/workspace/dist");
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration HARNESS_TIMEOUT = Duration.ofMinutes(2);
    private static final int OUTPUT_TAIL = 2000;

    @Override
    public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
        List<String> buildOut = new CopyOnWriteArrayList<>();
        ExecResult build;
        try {
            build = sandbox.exec(BUILD, null, BUILD_TIMEOUT, cancel, buildOut::add);
        } catch (ProcessTimeoutException e) {
            String msg = "build timed out after " + BUILD_TIMEOUT.toMinutes() + " min";
            return new PlaytestReport(false, List.of(msg),
                    Map.of("checks", List.of(check("build", false, msg)), "durationMs", 0));
        }
        if (build.exitCode() != 0) {
            String output = tail(String.join("\n", buildOut) + "\n" + build.stderrTail());
            Map<String, Object> buildCheck = check("build", false, output);
            return new PlaytestReport(false, List.of("build failed: " + output),
                    Map.of("checks", List.of(buildCheck), "durationMs", 0));
        }

        List<String> out = new CopyOnWriteArrayList<>();
        PlaytestReport report;
        try {
            ExecResult harness = sandbox.exec(HARNESS, null, HARNESS_TIMEOUT, cancel, out::add);
            report = PlaytestReportParser.parse(out, harness.stderrTail());
        } catch (ProcessTimeoutException e) {
            String msg = "playtest timed out after " + HARNESS_TIMEOUT.toMinutes() + " min (page unresponsive?)";
            report = new PlaytestReport(false, List.of(msg),
                    Map.of("checks", List.of(check("harness", false, msg)), "durationMs", HARNESS_TIMEOUT.toMillis()));
        }

        List<Object> checks = new ArrayList<>();
        checks.add(check("build", true, ""));
        if (report.details().get("checks") instanceof List<?> l) checks.addAll(l);
        Map<String, Object> details = new LinkedHashMap<>(report.details());
        details.put("checks", checks);
        return new PlaytestReport(report.passed(), report.failures(), details);
    }

    /** Outside /workspace so syncIn (which mirrors the project) leaves it alone. */
    static final String SCRIPT_PATH = "/tmp/playforge/play.mjs";
    static final List<String> WRITE_SCRIPT = List.of("sh", "-c", "mkdir -p /tmp/playforge && cat > " + SCRIPT_PATH);
    static final List<String> PLAY = List.of("node", "/opt/playtest/play.mjs", "--dist", "/workspace/dist", "--script", SCRIPT_PATH);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PLAY_TIMEOUT = Duration.ofMinutes(2);

    @Override
    public boolean supportsAiPlay() {
        return true;
    }

    @Override
    public AiPlayResult play(Sandbox sandbox, String script, CancellationToken cancel) {
        try {
            ExecResult write = sandbox.exec(WRITE_SCRIPT, script, WRITE_TIMEOUT, cancel, line -> { });
            if (write.exitCode() != 0) return inconclusive("could not write the play script: " + write.stderrTail().strip());
            List<String> out = new CopyOnWriteArrayList<>();
            ExecResult r = sandbox.exec(PLAY, null, PLAY_TIMEOUT, cancel, out::add);
            return AiPlayResultParser.parse(out, r.stderrTail());
        } catch (ProcessTimeoutException e) {
            return inconclusive("ai-play timed out after " + PLAY_TIMEOUT.toMinutes() + " min");
        }
    }

    private static AiPlayResult inconclusive(String error) {
        return new AiPlayResult(false, error, List.of(), List.of(), List.of(), 0);
    }

    private static Map<String, Object> check(String name, boolean passed, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", passed);
        m.put("detail", detail);
        return m;
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() <= OUTPUT_TAIL ? t : "…" + t.substring(t.length() - OUTPUT_TAIL);
    }
}
