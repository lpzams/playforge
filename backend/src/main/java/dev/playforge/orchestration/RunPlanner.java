package dev.playforge.orchestration;

import dev.playforge.orchestration.PlanDecision.Execute;
import dev.playforge.orchestration.PlanDecision.Finish;
import dev.playforge.run.RunStatus;
import dev.playforge.run.Step;
import dev.playforge.run.StepStatus;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;

import java.util.List;

/**
 * Pure state machine: given a run's step history, decides the next step. No I/O, so a restarted
 * process resumes simply by asking again. Rules: design spec section 5.1.
 */
public final class RunPlanner {

    private final int maxReviewRounds;
    private final int maxPlaytestFixes;

    public RunPlanner(int maxReviewRounds, int maxPlaytestFixes) {
        this.maxReviewRounds = maxReviewRounds;
        this.maxPlaytestFixes = maxPlaytestFixes;
    }

    public PlanDecision next(List<Step> history) {
        if (history.isEmpty()) return new Execute(StepType.CODE, 0, StepTrigger.NONE, false);

        Step last = history.getLast();
        if (last.getStatus() == StepStatus.RUNNING) {
            return new Execute(last.getType(), last.getRound(), last.getTrigger(), true);
        }
        if (last.getStatus() == StepStatus.FAILED) {
            return new Finish(RunStatus.FAILED, last.getType() + " step failed: " + last.getError());
        }

        int round = last.getRound();
        return switch (last.getType()) {
            case CODE -> exec(StepType.REVIEW, round);
            case FIX -> last.getTrigger() == StepTrigger.PLAYTEST
                    ? exec(StepType.PLAYTEST, round)
                    : exec(StepType.REVIEW, round);
            case REVIEW -> {
                if (Boolean.TRUE.equals(last.getOk()) || count(history, StepType.REVIEW, null) >= maxReviewRounds) {
                    yield exec(StepType.PLAYTEST, round);
                }
                yield fix(history, StepTrigger.REVIEW);
            }
            case PLAYTEST -> {
                if (Boolean.TRUE.equals(last.getOk())) yield exec(StepType.COMMIT, round);
                int used = count(history, StepType.FIX, StepTrigger.PLAYTEST);
                if (used < maxPlaytestFixes) yield fix(history, StepTrigger.PLAYTEST);
                yield new Finish(RunStatus.FAILED, "playtest still failing after " + used + " fix attempts");
            }
            case COMMIT -> new Finish(RunStatus.SUCCEEDED, "committed");
        };
    }

    private static Execute exec(StepType type, int round) {
        return new Execute(type, round, StepTrigger.NONE, false);
    }

    private static Execute fix(List<Step> history, StepTrigger trigger) {
        return new Execute(StepType.FIX, count(history, StepType.FIX, null) + 1, trigger, false);
    }

    private static int count(List<Step> history, StepType type, StepTrigger trigger) {
        return (int) history.stream()
                .filter(s -> s.getType() == type && (trigger == null || s.getTrigger() == trigger))
                .count();
    }
}
