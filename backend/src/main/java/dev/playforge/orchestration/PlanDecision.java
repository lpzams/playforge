package dev.playforge.orchestration;

import dev.playforge.run.RunStatus;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;

public sealed interface PlanDecision {

    /** Run this step next. {@code resume=true}: re-run the interrupted (still RUNNING) last step. */
    record Execute(StepType type, int round, StepTrigger trigger, boolean resume) implements PlanDecision {}

    record Finish(RunStatus status, String reason) implements PlanDecision {}
}
