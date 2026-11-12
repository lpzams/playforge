package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.event.RunEvent;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.playtest.PlaytestReport;
import dev.playforge.process.ExecResult;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import dev.playforge.sandbox.local.LocalSandboxProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** After a passing playtest the build output is exported for /preview; failures there never fail the run. */
@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import({OrchestrationTestConfig.class, PreviewExportTest.RecordingSandboxConfig.class})
class PreviewExportTest {

    static final List<String> EXPORTS = new CopyOnWriteArrayList<>();
    static volatile boolean failExport;

    @TestConfiguration
    static class RecordingSandboxConfig {
        @Bean
        @Primary
        SandboxProvider exportRecordingSandboxProvider(LocalSandboxProvider local) {
            return projectId -> {
                Sandbox inner = local.acquire(projectId);
                return new Sandbox() {
                    @Override public Path workdir() { return inner.workdir(); }
                    @Override public void syncIn(Path hostDir) { }
                    @Override public void syncOut(Path hostDir) { }
                    @Override public ExecResult exec(List<String> c, String s, Duration t, CancellationToken k, Consumer<String> o) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public void exportDir(String rel, Path hostDir) {
                        if (failExport) throw new IllegalStateException("no such directory in sandbox: dist");
                        EXPORTS.add(rel + " -> " + hostDir);
                    }
                    @Override public void close() { }
                };
            };
        }
    }

    @Autowired RunOrchestrator orchestrator;
    @Autowired RunStore store;
    @Autowired RunEventPublisher events;
    @Autowired PlayforgeProperties props;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        playtester.reset();
        EXPORTS.clear();
        failExport = false;
    }

    Run execute() {
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        orchestrator.execute(run.getId(), new CancellationToken());
        return store.getRun(run.getId());
    }

    @Test
    void passingPlaytestExportsDistToPreviewDir() {
        Run run = execute();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(EXPORTS).containsExactly("dist -> " + props.previewDir().resolve(run.getProjectId()));
    }

    @Test
    void failingPlaytestDoesNotExport() {
        for (int i = 0; i < 3; i++) playtester.script.add(new PlaytestReport(false, List.of("probe: missing"), Map.of()));
        Run run = execute();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(EXPORTS).isEmpty();
    }

    @Test
    void exportFailureDoesNotFailRun() {
        failExport = true;
        Run run = execute();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events.history(run.getId(), 0)).extracting(RunEvent::type).contains(RunEventType.LOG);
    }

    @Test
    void playtestResultEventCarriesChecks() {
        playtester.script.add(new PlaytestReport(true, List.of(),
                Map.of("checks", List.of(Map.of("name", "load", "passed", true, "detail", "")), "durationMs", 3)));
        Run run = execute();
        RunEvent e = events.history(run.getId(), 0).stream().filter(x -> x.type() == RunEventType.PLAYTEST_RESULT).findFirst().orElseThrow();
        assertThat(e.payload()).containsKey("checks");
        assertThat(e.payload().get("checks").toString()).contains("load");
    }
}
