package dev.playforge.event;

import java.time.Instant;
import java.util.Map;

public record RunEvent(long runId, long seq, Instant ts, RunEventType type, String source, Map<String, Object> payload) {}
