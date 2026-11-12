package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewer runs without a CLI-enforced read-only sandbox, so after REVIEW the orchestrator must re-sync
 * the sandbox from the host to discard anything the reviewer changed.
 */
@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import({OrchestrationTestConfig.class, ReviewResyncTest.RecordingSandboxConfig.class})
class ReviewResyncTest {

    static final List<String> LOG = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingSandboxConfig {
        @Bean
        @Primary
        SandboxProvider recordingSandboxProvider(LocalSandboxProvider local) {
            return projectId -> {
                Sandbox inner = local.acquire(projectId);
                return new Sandbox() {
                    @Override public Path workdir() { return inner.workdir(); }
                    @Override public void syncIn(Path hostDir) { LOG.add("syncIn"); }
                    @Override public void syncOut(Path hostDir) { LOG.add("syncOut"); }
                    @Override public ExecResult exec(List<String> cmd, String stdin, Duration timeout,
                                                     CancellationToken cancel, Consumer<String> out) {
                        return inner.exec(cmd, stdin, timeout, cancel, out);
                    }
                    @Override public void exportDir(String rel, Path hostDir) { LOG.add("exportDir " + rel); }
                    @Override public void close() { LOG.add("close"); }
                };
            };
        }
    }

    @Autowired RunOrchestrator orchestrator;
    @Autowired RunStore store;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        playtester.reset();
        LOG.clear();
    }

    @Test
    void sandboxIsResyncedFromHostAfterEveryReview() {
        scriptedReviewer.script.add((r, s, k, c) -> {
            LOG.add("review");
            return text("{\"issues\":[]}");
        });
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        orchestrator.execute(run.getId(), new CancellationToken());

        assertThat(store.getRun(run.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        int review = LOG.indexOf("review");
        assertThat(review).isPositive();
        assertThat(LOG.subList(review + 1, LOG.size())).first().isEqualTo("syncIn");
    }
}
