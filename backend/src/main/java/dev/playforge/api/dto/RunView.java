package dev.playforge.api.dto;

import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.Step;
import dev.playforge.run.StepStatus;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;

import java.time.Instant;
import java.util.List;

public record RunView(long id, String projectId, String instruction, RunStatus status, String error,
                      String baseCommit, String resultCommit, Instant createdAt, Instant startedAt,
                      Instant finishedAt, List<StepView> steps) {

    public record StepView(long id, StepType type, int round, StepTrigger trigger, StepStatus status, Boolean ok) {
        public static StepView of(Step s) {
            return new StepView(s.getId(), s.getType(), s.getRound(), s.getTrigger(), s.getStatus(), s.getOk());
        }
    }

    public static RunView of(Run r, List<Step> steps) {
        return new RunView(r.getId(), r.getProjectId(), r.getInstruction(), r.getStatus(), r.getError(),
                r.getBaseCommit(), r.getResultCommit(), r.getCreatedAt(), r.getStartedAt(), r.getFinishedAt(),
                steps.stream().map(StepView::of).toList());
    }
}
