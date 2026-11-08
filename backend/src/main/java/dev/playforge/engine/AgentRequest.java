package dev.playforge.engine;

import java.time.Duration;

public record AgentRequest(AgentRole role, String prompt, Duration timeout) {}
