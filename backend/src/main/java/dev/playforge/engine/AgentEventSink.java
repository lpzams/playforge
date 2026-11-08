package dev.playforge.engine;

import dev.playforge.event.RunEventType;

import java.util.Map;

public interface AgentEventSink {
    void emit(RunEventType type, Map<String, Object> payload);
}
