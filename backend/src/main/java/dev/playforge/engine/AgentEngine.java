package dev.playforge.engine;

import dev.playforge.common.CancellationToken;
import dev.playforge.sandbox.Sandbox;

/** A coding/reviewing agent (Claude CLI, Codex CLI, demo...). Selected by (name, role). */
public interface AgentEngine {
    String name();

    AgentRole role();

    AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws EngineException;
}
