package dev.playforge.orchestration;

import dev.playforge.orchestration.PlanDecision.Execute;
import dev.playforge.orchestration.PlanDecision.Finish;
import dev.playforge.run.RunStatus;
import dev.playforge.run.Step;
import dev.playforge.run.StepStatus;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.playforge.run.StepStatus.DONE;
import static dev.playforge.run.StepStatus.FAILED;
import static dev.playforge.run.StepStatus.RUNNING;
import static dev.playforge.run.StepTrigger.NONE;
import static dev.playforge.run.StepTrigger.PLAYTEST;
import static dev.playforge.run.StepTrigger.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

class RunPlannerTest {

    final RunPlanner planner = new RunPlanner(3, 2);

    /** Builds a history; rounds are assigned the way the orchestrator would (from the planner itself). */
    static final class H {
        final List<Step> steps = new ArrayList<>();
        int round;

        H add(StepType type, StepTrigger trigger, StepStatus status, Boolean ok) {
            if (type == StepType.FIX) round = (int) steps.stream().filter(s -> s.getType() == StepType.FIX).count() + 1;
            Step s = new Step();
            s.setType(type);
            s.setTrigger(trigger);
            s.setStatus(status);
            s.setOk(ok);
            s.setRound(round);
            steps.add(s);
            return this;
        }

        H code() { return add(StepType.CODE, NONE, DONE, null); }
        H review(boolean clean) { return add(StepType.REVIEW, NONE, DONE, clean); }
        H fix(StepTrigger t) { return add(StepType.FIX, t, DONE, null); }
        H playtest(boolean pass) { return add(StepType.PLAYTEST, NONE, DONE, pass); }
    }

    @Test
    void emptyStartsWithCode() {
        assertThat(planner.next(List.of())).isEqualTo(new Execute(StepType.CODE, 0, NONE, false));
    }

    @Test
    void codeDoneThenReview() {
        assertThat(planner.next(new H().code().steps)).isEqualTo(new Execute(StepType.REVIEW, 0, NONE, false));
    }

    @Test
    void cleanReviewThenPlaytest() {
        assertThat(planner.next(new H().code().review(true).steps)).isEqualTo(new Execute(StepType.PLAYTEST, 0, NONE, false));
    }

    @Test
    void blockingReviewThenFix() {
        assertThat(planner.next(new H().code().review(false).steps)).isEqualTo(new Execute(StepType.FIX, 1, REVIEW, false));
    }

    @Test
    void reviewFixDoneThenReviewAgain() {
        assertThat(planner.next(new H().code().review(false).fix(REVIEW).steps))
                .isEqualTo(new Execute(StepType.REVIEW, 1, NONE, false));
    }

    @Test
    void thirdBlockingReviewGoesToPlaytest() {
        List<Step> h = new H().code().review(false).fix(REVIEW).review(false).fix(REVIEW).review(false).steps;
        assertThat(planner.next(h)).isEqualTo(new Execute(StepType.PLAYTEST, 2, NONE, false));
    }

    @Test
    void playtestPassThenCommit() {
        assertThat(planner.next(new H().code().review(true).playtest(true).steps))
                .isEqualTo(new Execute(StepType.COMMIT, 0, NONE, false));
    }

    @Test
    void playtestFailThenFix() {
        assertThat(planner.next(new H().code().review(true).playtest(false).steps))
                .isEqualTo(new Execute(StepType.FIX, 1, PLAYTEST, false));
    }

    @Test
    void playtestFixThenPlaytestSkippingReview() {
        assertThat(planner.next(new H().code().review(true).playtest(false).fix(PLAYTEST).steps))
                .isEqualTo(new Execute(StepType.PLAYTEST, 1, NONE, false));
    }

    @Test
    void playtestFixesExhaustedFails() {
        List<Step> h = new H().code().review(true)
                .playtest(false).fix(PLAYTEST).playtest(false).fix(PLAYTEST).playtest(false).steps;
        PlanDecision d = planner.next(h);
        assertThat(d).isInstanceOf(Finish.class);
        assertThat(((Finish) d).status()).isEqualTo(RunStatus.FAILED);
        assertThat(((Finish) d).reason()).containsIgnoringCase("playtest");
    }

    @Test
    void reviewFixesDoNotConsumePlaytestFixBudget() {
        List<Step> h = new H().code().review(false).fix(REVIEW).review(true).playtest(false).steps;
        assertThat(planner.next(h)).isEqualTo(new Execute(StepType.FIX, 2, PLAYTEST, false));
    }

    @Test
    void commitDoneSucceeds() {
        List<Step> h = new H().code().review(true).playtest(true).add(StepType.COMMIT, NONE, DONE, null).steps;
        PlanDecision d = planner.next(h);
        assertThat(d).isInstanceOf(Finish.class);
        assertThat(((Finish) d).status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void runningStepIsResumed() {
        List<Step> h = new H().code().add(StepType.REVIEW, NONE, RUNNING, null).steps;
        assertThat(planner.next(h)).isEqualTo(new Execute(StepType.REVIEW, 0, NONE, true));
    }

    @Test
    void failedStepFailsRun() {
        List<Step> h = new H().add(StepType.CODE, NONE, FAILED, null).steps;
        PlanDecision d = planner.next(h);
        assertThat(d).isInstanceOf(Finish.class);
        assertThat(((Finish) d).status()).isEqualTo(RunStatus.FAILED);
    }
}
