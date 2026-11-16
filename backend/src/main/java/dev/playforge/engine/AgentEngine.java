package dev.playforge.engine;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;

/** A coding/reviewing agent (Claude CLI, Codex CLI, demo...). Selected by (name, role). */
public interface AgentEngine {
    String name();

    /** The engine's primary role. */
    AgentRole role();

    /** Whether the engine can act in {@code role}; by default only its primary role. */
    default boolean supports(AgentRole role) {
        return role == role();
    }

    AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws EngineException;
}
