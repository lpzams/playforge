package dev.playforge.engine;

import java.time.Duration;

public record AgentResult(String finalText, long inputTokens, long outputTokens, Duration elapsed) {}
