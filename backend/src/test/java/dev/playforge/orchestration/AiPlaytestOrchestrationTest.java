package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.EngineException;
import dev.playforge.event.RunEvent;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.playtest.AiPlayResult;
import dev.playforge.playtest.PlaytestReport;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.run.Step;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester.failingPlay;
import static dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester.passingPlay;
import static dev.playforge.orchestration.ScriptedEngine.DEFAULT_PLAY_SCRIPT;
import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;

/** PLAYTEST step with the AI player and judge: scripted engines and playtester (max-playtest-fixes = 2). */
@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class AiPlaytestOrchestrationTest {

    @Autowired RunOrchestrator orchestrator;
    @Autowired RunStore store;
    @Autowired RunEventPublisher events;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedEngine scriptedPlayer;
    @Autowired ScriptedEngine scriptedJudge;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        scriptedPlayer.reset();
        scriptedJudge.reset();
        playtester.reset();
        playtester.aiPlay = true;
    }

    Run run() {
        Run run = store.createRun(store.createProject("t", "接金币").getId(), "接金币：左右移动篮子接住金币");
        orchestrator.execute(run.getId(), new CancellationToken());
        return store.getRun(run.getId());
    }

    List<RunEvent> events(Run run, RunEventType type) {
        return events.history(run.getId(), 0).stream().filter(e -> e.type() == type).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> aiPlayOf(RunEvent playtestResult) {
        return (Map<String, Object>) playtestResult.payload().get("aiPlay");
    }

    List<Step> steps(Run run, StepType type) {
        return store.listSteps(run.getId()).stream().filter(s -> s.getType() == type).toList();
    }

    @Test
    void passingPlaythroughIsJudgedAndReported() {
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(scriptedPlayer.calls).singleElement().satisfies(r -> assertThat(r.prompt()).contains("接金币：左右移动篮子接住金币"));
        assertThat(playtester.playedScripts).containsExactly(DEFAULT_PLAY_SCRIPT);
        assertThat(scriptedJudge.calls).singleElement()
                .satisfies(r -> assertThat(r.prompt()).contains("/workspace/dist/playtest/01-start.png").contains("caught 2 coins"));

        Map<String, Object> ai = aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast());
        assertThat(ai).containsEntry("ran", true).containsEntry("inconclusive", false).containsEntry("waived", false);
        assertThat(ai.get("assertions").toString()).contains("coins can be caught");
        assertThat(ai.get("script").toString()).contains("export default");

        RunEvent fun = events(run, RunEventType.FUN_SCORE).getFirst();
        assertThat(fun.payload().get("scores").toString()).contains("fit=4");
        assertThat(fun.payload()).containsEntry("summary", "能玩");
        assertThat(steps(run, StepType.PLAYTEST).getLast().getOutputJson()).contains("funScore").contains("export default");
    }

    @Test
    void failedAssertionsGoToTheFixPromptAndTheScriptIsReused() {
        playtester.plays.add(failingPlay("coins should be catchable"));
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        Step fix = steps(run, StepType.FIX).getFirst();
        assertThat(fix.getTrigger()).isEqualTo(StepTrigger.PLAYTEST);
        assertThat(scriptedCoder.calls.getLast().prompt()).contains("ai-play: coins should be catchable");
        assertThat(scriptedPlayer.calls).hasSize(1);
        assertThat(playtester.playedScripts).hasSize(2).containsOnly(DEFAULT_PLAY_SCRIPT);
        assertThat(scriptedJudge.calls).hasSize(1);
    }

    @Test
    void whenFixesRunOutTheHardChecksArbitrate() {
        for (int i = 0; i < 3; i++) playtester.plays.add(failingPlay("score shows on screen"));
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(steps(run, StepType.FIX)).hasSize(2);
        Map<String, Object> ai = aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast());
        assertThat(ai).containsEntry("waived", true);
        assertThat(events(run, RunEventType.PLAYTEST_RESULT).getLast().payload()).containsEntry("passed", true);
    }

    @Test
    void anInconclusivePlaythroughNeverFailsTheGame() {
        playtester.plays.add(new AiPlayResult(false, "t.jump is not a function", List.of(), List.of(), List.of(), 10));
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(steps(run, StepType.FIX)).isEmpty();
        assertThat(aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast()))
                .containsEntry("inconclusive", true).containsEntry("error", "t.jump is not a function");
        assertThat(scriptedJudge.calls).isEmpty(); // nothing to look at
    }

    @Test
    void hardCheckFailureSkipsAiPlayForThatRound() {
        playtester.script.add(new PlaytestReport(false, List.of("no canvas"), Map.of()));
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(playtester.calls).hasValue(2);
        assertThat(playtester.playedScripts).hasSize(1); // only after the fix, when the hard checks pass
        assertThat(scriptedCoder.calls.getLast().prompt()).contains("no canvas").doesNotContain("ai-play:");
    }

    @Test
    void playerFailureSkipsAiPlayButNotTheRun() {
        scriptedPlayer.script.add((r, s, k, c) -> { throw new EngineException("player crashed", false, null); });
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(playtester.playedScripts).isEmpty();
        assertThat(aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast()).get("skipped").toString()).contains("player crashed");
    }

    @Test
    void unusablePlayerReplySkipsAiPlay() {
        scriptedPlayer.script.add((r, s, k, c) -> text("I would press the arrow keys."));
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(playtester.playedScripts).isEmpty();
        assertThat(aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast()).get("skipped").toString()).contains("no playable script");
    }

    /** Not only engine errors: anything going wrong around the AI round must leave a passing game passing. */
    @Test
    void anyPlayerOrPlayCrashLeavesAPassingGamePassing() {
        scriptedPlayer.script.add((r, s, k, c) -> { throw new IllegalStateException("no PLAYER engine named demo"); });
        assertThat(run().getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        reset();
        playtester.playThrows = new IllegalStateException("docker exec failed");
        Run run = run();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(aiPlayOf(events(run, RunEventType.PLAYTEST_RESULT).getLast()))
                .containsEntry("inconclusive", true).containsEntry("error", "docker exec failed");
    }

    @Test
    void judgeRuntimeFailureIsReportOnly() {
        scriptedJudge.script.add((r, s, k, c) -> { throw new IllegalStateException("judge broke"); });
        Run run = run();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events(run, RunEventType.FUN_SCORE)).isEmpty();
    }

    /** The playthrough and the judge are AI-controlled text published as system events: redact them too. */
    @Test
    void aiPlayAndFunScoreEventsAreRedacted() {
        String secret = OrchestrationTestConfig.TEST_SECRET;
        playtester.plays.add(new AiPlayResult(true, null, List.of(new AiPlayResult.Assertion("key " + secret, true)),
                List.of(new AiPlayResult.Shot("playtest/01-start.png", "start")), List.of("env " + secret), 10));
        scriptedJudge.script.add((r, s, k, c) -> text("{\"scores\":{\"fit\":3},\"summary\":\"leak " + secret + "\",\"suggestions\":[]}"));
        Run run = run();
        assertThat(events.history(run.getId(), 0).toString()).doesNotContain(secret);
    }

    @Test
    void judgeFailureIsReportOnly() {
        scriptedJudge.script.add((r, s, k, c) -> { throw new EngineException("judge crashed", false, null); });
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events(run, RunEventType.FUN_SCORE)).isEmpty();
    }

    @Test
    void withoutAiSupportNoPlayerIsAsked() {
        playtester.aiPlay = false;
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(scriptedPlayer.calls).isEmpty();
        assertThat(scriptedJudge.calls).isEmpty();
        assertThat(events(run, RunEventType.PLAYTEST_RESULT).getLast().payload()).doesNotContainKey("aiPlay");
    }

    @Test
    void passingPlayHelperIsWhatTheDefaultPlaytesterReturns() {
        assertThat(passingPlay().allPassed()).isTrue();
    }
}
