package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.engine.EngineException;
import dev.playforge.event.RunEvent;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.playtest.PlaytestReport;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.run.Step;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;
import dev.playforge.workspace.GitWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class RunOrchestratorTest {

    static final String BLOCKER = "{\"issues\":[{\"severity\":\"BLOCKER\",\"file\":\"main.js\",\"message\":\"missing restart\"}]}";

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
    }

    Run newRun() {
        return store.createRun(store.createProject("t", "躲陨石").getId(), "躲陨石");
    }

    Run execute(Run run) {
        orchestrator.execute(run.getId(), new CancellationToken());
        return store.getRun(run.getId());
    }

    List<StepType> stepTypes(Run run) {
        return store.listSteps(run.getId()).stream().map(Step::getType).toList();
    }

    List<RunEventType> eventTypes(Run run) {
        return events.history(run.getId(), 0).stream().map(RunEvent::type).toList();
    }

    @Test
    void happyPath() throws Exception {
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stepTypes(run)).containsExactly(StepType.CODE, StepType.REVIEW, StepType.PLAYTEST, StepType.COMMIT);
        try (GitWorkspace ws = GitWorkspace.openOrInit(props.workspaceDir().resolve(run.getProjectId()))) {
            assertThat(run.getResultCommit()).isNotBlank().isEqualTo(ws.head().orElseThrow());
        }
        List<RunEventType> types = eventTypes(run);
        assertThat(types.getFirst()).isEqualTo(RunEventType.RUN_STARTED);
        assertThat(types.getLast()).isEqualTo(RunEventType.RUN_FINISHED);
        assertThat(types).contains(RunEventType.FILE_CHANGED, RunEventType.REVIEW_RESULT, RunEventType.PLAYTEST_RESULT);
    }

    @Test
    void reviewerSeesCoderChangesInDiff() {
        Run run = execute(newRun());
        assertThat(scriptedReviewer.calls).singleElement()
                .satisfies(r -> assertThat(r.prompt()).contains("game-1.js"));
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void reviewBlockerThenFixed() {
        scriptedReviewer.script.add((r, s, k, c) -> text(BLOCKER));
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stepTypes(run)).containsExactly(StepType.CODE, StepType.REVIEW, StepType.FIX, StepType.REVIEW,
                StepType.PLAYTEST, StepType.COMMIT);
        assertThat(scriptedCoder.calls.get(1).prompt()).contains("missing restart");
    }

    @Test
    void reviewNeverClearsStillCommitsIfPlaytestPasses() {
        for (int i = 0; i < 3; i++) scriptedReviewer.script.add((r, s, k, c) -> text(BLOCKER));
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stepTypes(run).stream().filter(t -> t == StepType.REVIEW).count()).isEqualTo(3);
    }

    @Test
    void unparseableReviewProceeds() {
        scriptedReviewer.script.add((r, s, k, c) -> text("LGTM"));
        Run run = execute(newRun());

        assertThat(eventTypes(run)).contains(RunEventType.REVIEW_UNPARSEABLE);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void retryableErrorRetriesThenSucceeds() {
        scriptedCoder.script.add((r, s, k, c) -> { throw new EngineException("rate limited", true, null); });
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(eventTypes(run).stream().filter(t -> t == RunEventType.RETRY_SCHEDULED).count()).isEqualTo(1);
    }

    @Test
    void retriesExhaustedFailsRun() {
        for (int i = 0; i < 4; i++) {
            scriptedCoder.script.add((r, s, k, c) -> { throw new EngineException("rate limited", true, null); });
        }
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(scriptedCoder.calls).hasSize(4); // 1 attempt + 3 retries
    }

    @Test
    void nonRetryableErrorFailsRun() {
        scriptedCoder.script.add((r, s, k, c) -> { throw new EngineException("bad credentials", false, null); });
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("bad credentials");
        assertThat(eventTypes(run).getLast()).isEqualTo(RunEventType.RUN_FINISHED);
    }

    @Test
    void cancelDuringStep() throws Exception {
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
        CancellationToken token = new CancellationToken();
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> orchestrator.execute(run.getId(), token));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        token.cancel();
        f.get(10, TimeUnit.SECONDS);

        assertThat(store.getRun(run.getId()).getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(scriptedReviewer.calls).isEmpty();
        assertThat(eventTypes(run).getLast()).isEqualTo(RunEventType.RUN_FINISHED);
    }

    @Test
    void playtestFailuresReachFixPrompt() {
        playtester.script.add(new PlaytestReport(false, List.of("no canvas"), Map.of()));
        Run run = execute(newRun());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        Step fix = store.listSteps(run.getId()).stream().filter(s -> s.getType() == StepType.FIX).findFirst().orElseThrow();
        assertThat(fix.getTrigger()).isEqualTo(StepTrigger.PLAYTEST);
        assertThat(scriptedCoder.calls.getLast().prompt()).contains("no canvas");
        assertThat(playtester.calls).hasValue(2);
    }

    @Test
    void promptPassedToEngineUnchanged() {
        String instruction = "做一个\"躲陨石\"游戏\n第二行 C:\\path 'x' $HOME `cmd`";
        Run run = store.createRun(store.createProject("t", instruction).getId(), instruction);
        execute(run);
        assertThat(scriptedCoder.calls.getFirst().prompt()).contains(instruction);
    }
}
