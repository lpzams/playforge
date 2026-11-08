package dev.playforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("playforge")
public record PlayforgeProperties(Path workspaceRoot, Orchestration orchestration, Engines engines) {

    public record Orchestration(int maxReviewRounds, int maxPlaytestFixes, Duration stepTimeout,
                                Duration runTimeout, int maxEngineRetries, Duration retryBaseDelay) {}

    public record Engines(String coder, String reviewer) {}
}
