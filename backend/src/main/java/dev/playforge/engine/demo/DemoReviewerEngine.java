package dev.playforge.engine.demo;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentEngine;
import dev.playforge.engine.AgentEventSink;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.AgentRole;
import dev.playforge.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Stand-in reviewer for P1: always approves. Replaced by the Codex CLI engine in P2. */
@Component
public class DemoReviewerEngine implements AgentEngine {

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public AgentRole role() {
        return AgentRole.REVIEWER;
    }

    @Override
    public AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) {
        cancel.throwIfCancelled();
        return new AgentResult("{\"issues\":[]}", 0, 0, Duration.ZERO);
    }
}
