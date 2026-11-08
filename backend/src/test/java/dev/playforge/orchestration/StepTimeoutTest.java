package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;

/** The orchestrator must enforce stepTimeout even when an engine or playtester ignores its cancellation token. */
@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted",
        "playforge.orchestration.step-timeout=500ms"})
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class StepTimeoutTest {

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
    }

    Run executeWithin10s() throws Exception {
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        CompletableFuture.runAsync(() -> orchestrator.execute(run.getId(), new CancellationToken()))
                .get(10, TimeUnit.SECONDS);
        return store.getRun(run.getId());
    }

    static void hang() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // deliberately ignores interruption and the token, like a stuck external process
            }
        }
    }

    @Test
    void hungEngineTimesOut() throws Exception {
        scriptedCoder.script.add((r, s, k, c) -> {
            hang();
            return text("too late");
        });
        Run run = executeWithin10s();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("timed out");
    }

    @Test
    void hungPlaytesterTimesOut() throws Exception {
        playtester.onRun = StepTimeoutTest::hang;
        Run run = executeWithin10s();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("timed out");
    }
}
