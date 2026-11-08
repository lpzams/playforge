package dev.playforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param workspaceRoot directory holding one git repo per project; relative paths resolve against the
 *                      working directory. Bound as a String because Spring's String-to-Path conversion
 *                      treats relative paths as classpath resources and rejects {@code ../workspaces}.
 */
@ConfigurationProperties("playforge")
public record PlayforgeProperties(String workspaceRoot, Orchestration orchestration, Engines engines) {

    public record Orchestration(int maxReviewRounds, int maxPlaytestFixes, Duration stepTimeout,
                                Duration runTimeout, int maxEngineRetries, Duration retryBaseDelay) {}

    public record Engines(String coder, String reviewer) {}

    /** {@link #workspaceRoot} as an absolute, normalized path. */
    public Path workspaceDir() {
        return Path.of(workspaceRoot.trim()).toAbsolutePath().normalize();
    }
}
