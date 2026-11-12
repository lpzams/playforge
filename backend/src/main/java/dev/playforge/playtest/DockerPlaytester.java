package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
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

    static final List<String> BUILD = List.of("npm", "run", "build");
    static final List<String> HARNESS = List.of("node", "/opt/playtest/run.mjs", "--dist", "/workspace/dist");
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration HARNESS_TIMEOUT = Duration.ofMinutes(2);
    private static final int OUTPUT_TAIL = 2000;

    @Override
    public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
        List<String> buildOut = new CopyOnWriteArrayList<>();
        ExecResult build = sandbox.exec(BUILD, null, BUILD_TIMEOUT, cancel, buildOut::add);
        if (build.exitCode() != 0) {
            String output = tail(String.join("\n", buildOut) + "\n" + build.stderrTail());
            Map<String, Object> buildCheck = check("build", false, output);
            return new PlaytestReport(false, List.of("build failed: " + output),
                    Map.of("checks", List.of(buildCheck), "durationMs", 0));
        }

        List<String> out = new CopyOnWriteArrayList<>();
        ExecResult harness = sandbox.exec(HARNESS, null, HARNESS_TIMEOUT, cancel, out::add);
        PlaytestReport report = PlaytestReportParser.parse(out, harness.stderrTail());

        List<Object> checks = new ArrayList<>();
        checks.add(check("build", true, ""));
        if (report.details().get("checks") instanceof List<?> l) checks.addAll(l);
        Map<String, Object> details = new LinkedHashMap<>(report.details());
        details.put("checks", checks);
        return new PlaytestReport(report.passed(), report.failures(), details);
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
