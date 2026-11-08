package dev.playforge.review;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record ReviewReport(boolean parsed, List<ReviewIssue> issues) {

    @JsonIgnore
    public boolean hasBlockers() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.BLOCKER);
    }

    @JsonIgnore
    public List<ReviewIssue> blockers() {
        return issues.stream().filter(i -> i.severity() == Severity.BLOCKER).toList();
    }
}
