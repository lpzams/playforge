package dev.playforge.event;

public enum RunEventType {
    RUN_STARTED, STEP_STARTED, AGENT_TEXT, TOOL_CALL, TOOL_RESULT, USAGE,
    FILE_CHANGED, REVIEW_RESULT, REVIEW_UNPARSEABLE, PLAYTEST_RESULT, FUN_SCORE,
    RETRY_SCHEDULED, STEP_FINISHED, RUN_FINISHED, LOG,
    /** Outbound requests the sandbox gateway refused during a step: {"requests":[{"host","port","count"}]}. */
    NETWORK_BLOCKED
}
