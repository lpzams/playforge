package dev.playforge.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessRunner;
import dev.playforge.sandbox.docker.CliDockerOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the real playtest harness (run.mjs) in the p3 image against static fixtures. */
@Tag("docker")
class PlaytestHarnessIntegrationTest {

    static final String IMAGE = "playforge-sandbox:p3";
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void requireDocker() {
        assumeTrue(new CliDockerOps(new ProcessRunner(), "docker", System::getenv).available(), "Docker is not available");
    }

    record Run(JsonNode report, Duration elapsed) {
        boolean check(String name) {
            for (JsonNode c : report.path("checks")) if (c.path("name").asText().equals(name)) return c.path("passed").asBoolean();
            throw new AssertionError("no check named " + name + " in " + report);
        }

        String detail(String name) {
            for (JsonNode c : report.path("checks")) if (c.path("name").asText().equals(name)) return c.path("detail").asText();
            return "";
        }

        List<String> checkNames() {
            List<String> names = new ArrayList<>();
            report.path("checks").forEach(c -> names.add(c.path("name").asText()));
            return names;
        }
    }

    static Run harness(String fixture) throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        ExecResult r = new ProcessRunner().run(List.of("docker", "run", "--rm", IMAGE,
                        "node", "/opt/playtest/run.mjs", "--dist", "/opt/playtest/fixtures/" + fixture),
                Map.of(), null, Duration.ofSeconds(150), new CancellationToken(), out::add);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        String last = null;
        for (String l : out) if (l.startsWith("{\"playtest\":")) last = l;
        assertThat(last).as("report line; stdout=%s stderr=%s", out, r.stderrTail()).isNotNull();
        return new Run(JSON.readTree(last), elapsed);
    }

    @Test
    void goodFixturePasses() throws Exception {
        Run run = harness("good");
        assertThat(run.report().path("passed").asBoolean()).as(run.report().toString()).isTrue();
        assertThat(run.checkNames()).contains("load", "probe", "no_errors", "not_blank", "action:left", "action:right", "restart");
        assertThat(run.report().path("failures")).isEmpty();
    }

    /** The not-blank canvas shot is saved as the game's cover, so previews and the showcase get a thumbnail. */
    @Test
    void passingNotBlankWritesCoverIntoDist() throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        new ProcessRunner().run(List.of("docker", "run", "--rm", IMAGE, "sh", "-c",
                        "cp -r /opt/playtest/fixtures/good /tmp/g && node /opt/playtest/run.mjs --dist /tmp/g > /dev/null"
                                + " && test -s /tmp/g/cover.png && echo COVER_OK"),
                Map.of(), null, Duration.ofSeconds(150), new CancellationToken(), out::add);
        assertThat(out).contains("COVER_OK");
    }

    @Test
    void brokenFixtureReportsError() throws Exception {
        Run run = harness("broken");
        assertThat(run.report().path("passed").asBoolean()).isFalse();
        assertThat(run.check("no_errors")).isFalse();
        assertThat(run.detail("no_errors")).contains("boom");
    }

    @Test
    void noProbeFixture() throws Exception {
        Run run = harness("noprobe");
        assertThat(run.check("probe")).isFalse();
        assertThat(run.detail("probe")).contains("window.__GAME_PROBE__ not found within 10s");
    }

    @Test
    void frozenFixture() throws Exception {
        Run run = harness("frozen");
        assertThat(run.check("action:left")).isFalse();
        assertThat(run.check("probe")).isTrue();
    }

    @Test
    void blankFixture() throws Exception {
        Run run = harness("blank");
        assertThat(run.check("not_blank")).isFalse();
    }

    @Test
    void hangFixtureEndsWithinTimeLimit() throws Exception {
        Run run = harness("hang");
        assertThat(run.report().path("passed").asBoolean()).isFalse();
        assertThat(run.elapsed()).isLessThan(Duration.ofSeconds(100));
    }

    /** Template-like page styling must not make an undrawn canvas look "not blank". */
    @Test
    void blankCanvasOnStyledPageFails() throws Exception {
        Run run = harness("blank-canvas");
        assertThat(run.check("not_blank")).as(run.detail("not_blank")).isFalse();
    }

    /** Something moving on its own must not count as the controls working. */
    @Test
    void autonomousMotionWithDeadControlsFails() throws Exception {
        Run run = harness("autonomous");
        assertThat(run.check("action:left")).as(run.report().toString()).isFalse();
    }

    /** A jump that lands before key release still counts. */
    @Test
    void shortJumpPasses() throws Exception {
        Run run = harness("jump");
        assertThat(run.check("action:jump")).as(run.detail("action:jump")).isTrue();
    }

    @Test
    void loadingStatusWaitsForReady() throws Exception {
        Run run = harness("loading");
        assertThat(run.report().path("passed").asBoolean()).as(run.report().toString()).isTrue();
    }

    @Test
    void hangAtLoadEndsWithinTimeLimit() throws Exception {
        Run run = harness("hang-at-load");
        assertThat(run.report().path("passed").asBoolean()).isFalse();
        assertThat(run.elapsed()).isLessThan(Duration.ofSeconds(100));
    }

    /** The harness serves games with the preview's sandbox CSP, so storage use fails here, not only in preview. */
    @Test
    void storageUseFailsUnderPreviewSandbox() throws Exception {
        Run run = harness("storage");
        assertThat(run.report().path("passed").asBoolean()).isFalse();
        assertThat(run.report().toString()).containsIgnoringCase("localStorage");
    }

    /**
     * Found in live acceptance: a game declared {name:'restart', keys:['Space']} (Space only restarts after game
     * over). Lifecycle actions are covered by start()/restart(); playing them as controls forced Claude to break
     * the game design to pass.
     */
    @Test
    void lifecycleActionsAreNotPlayedAsControls() throws Exception {
        Run run = harness("lifecycle-action");
        assertThat(run.report().path("passed").asBoolean()).as(run.report().toString()).isTrue();
        assertThat(run.check("action:restart")).isTrue();
        assertThat(run.detail("action:restart")).contains("lifecycle");
    }

    @Test
    void absoluteAssetsFixtureFails() throws Exception {
        Run run = harness("absolute-assets");
        assertThat(run.report().path("passed").asBoolean()).isFalse();
        assertThat(!run.check("load") || !run.check("no_errors")).isTrue();
    }
}
