package dev.playforge.orchestration;

import dev.playforge.event.RunEvent;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class RunExecutorTest {

    @Autowired RunStore store;
    @Autowired RunExecutor executor;
    @Autowired RunEventPublisher events;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        playtester.reset();
    }

    Run newRun() {
        return store.createRun(store.createProject("t", "p").getId(), "p");
    }

    RunStatus status(Run run) {
        return store.getRun(run.getId()).getStatus();
    }

    @Test
    void duplicateSubmitIgnored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        scriptedCoder.script.add((r, s, k, c) -> {
            release.await(10, TimeUnit.SECONDS);
            return text("done");
        });
        Run run = newRun();
        executor.submit(run.getId());
        executor.submit(run.getId());
        assertThat(executor.isActive(run.getId())).isTrue();
        release.countDown();

        await().atMost(Duration.ofSeconds(20)).until(() -> status(run) == RunStatus.SUCCEEDED);
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isActive(run.getId()));
        assertThat(scriptedCoder.calls).hasSize(1);
    }

    @Test
    void cancelRunningRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        scriptedCoder.script.add((r, s, k, c) -> {
            started.countDown();
            CountDownLatch cancelled = new CountDownLatch(1);
            c.onCancel(cancelled::countDown);
            cancelled.await(10, TimeUnit.SECONDS);
            c.throwIfCancelled();
            return text("unreachable");
        });
        Run run = newRun();
        executor.submit(run.getId());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.cancel(run.getId())).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> status(run) == RunStatus.CANCELLED);
    }

    @Test
    void cancelQueuedRun() {
        Run run = newRun();
        assertThat(executor.cancel(run.getId())).isTrue();
        assertThat(status(run)).isEqualTo(RunStatus.CANCELLED);
        assertThat(events.history(run.getId(), 0)).extracting(RunEvent::type).containsExactly(RunEventType.RUN_FINISHED);
    }

    @Test
    void cancelUnknownRunReturnsFalse() {
        assertThat(executor.cancel(987_654_321L)).isFalse();
    }

    @Test
    void cancelFinishedRunReturnsFalse() {
        Run run = newRun();
        store.finishRun(run.getId(), RunStatus.SUCCEEDED, null, "abc");
        assertThat(executor.cancel(run.getId())).isFalse();
    }

    @Test
    void submitTerminalRunIgnored() throws Exception {
        Run run = newRun();
        store.finishRun(run.getId(), RunStatus.CANCELLED, null, null);
        executor.submit(run.getId());
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isActive(run.getId()));
        assertThat(status(run)).isEqualTo(RunStatus.CANCELLED);
        assertThat(scriptedCoder.calls).isEmpty();
    }
}
