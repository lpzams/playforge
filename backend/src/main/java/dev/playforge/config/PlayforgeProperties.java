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
public record PlayforgeProperties(String workspaceRoot, Orchestration orchestration, Engines engines,
                                  SandboxConfig sandbox, String previewRoot, String webRoot) {

    public record Orchestration(int maxReviewRounds, int maxPlaytestFixes, Duration stepTimeout,
                                Duration runTimeout, int maxEngineRetries, Duration retryBaseDelay) {}

    public record Engines(String coder, String reviewer) {}

    /**
     * @param provider       {@code local} (host directory, demo engines) or {@code docker}
     * @param codexAuthFile  host path of Codex credentials, mounted read-only when it exists
     */
    public record SandboxConfig(String provider, String image, int poolSize, Duration acquireTimeout,
                                String codexAuthFile, String dockerCommand) {}

    /** Where built games are published for /preview (one directory per project, outside any git repo). */
    public Path previewDir() {
        return Path.of(previewRoot.trim()).toAbsolutePath().normalize();
    }

    /** Built web UI served at / (web/dist); ignored when the directory does not exist. */
    public Path webDir() {
        return Path.of(webRoot.trim()).toAbsolutePath().normalize();
    }

    /** {@link #workspaceRoot} as an absolute, normalized path. */
    public Path workspaceDir() {
        return Path.of(workspaceRoot.trim()).toAbsolutePath().normalize();
    }
}
