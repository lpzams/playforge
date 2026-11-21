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
                                  SandboxConfig sandbox, String previewRoot, String webRoot, Playtest playtest) {

    public record Orchestration(int maxReviewRounds, int maxPlaytestFixes, Duration stepTimeout,
                                Duration runTimeout, int maxEngineRetries, Duration retryBaseDelay) {}

    /** Engine names per role; the player and the judge default to the coder engine. */
    public record Engines(String coder, String reviewer, String player, String judge) {
        public String playerOrCoder() {
            return player == null || player.isBlank() ? coder : player;
        }

        public String judgeOrCoder() {
            return judge == null || judge.isBlank() ? coder : judge;
        }
    }

    /**
     * @param aiPlay after the hard checks, an AI player writes and the harness runs a playthrough of the game
     * @param judge  a model scores screenshots of the playthrough (report only)
     */
    public record Playtest(Boolean aiPlay, Boolean judge) {}

    public boolean aiPlayEnabled() {
        return playtest == null || playtest.aiPlay() == null || playtest.aiPlay();
    }

    public boolean judgeEnabled() {
        return playtest == null || playtest.judge() == null || playtest.judge();
    }

    /**
     * @param provider       {@code local} (host directory, demo engines) or {@code docker}
     * @param codexAuthFile  host path of Codex credentials, mounted read-only when it exists
     * @param egress         outbound network of docker sandboxes (allow list through a gateway)
     */
    public record SandboxConfig(String provider, String image, int poolSize, Duration acquireTimeout,
                                String codexAuthFile, String dockerCommand, Egress egress) {

        /** On unless explicitly disabled. */
        public boolean egressEnabled() {
            return egress != null && !Boolean.FALSE.equals(egress.enabled());
        }
    }

    /**
     * @param upstream optional HTTP proxy the gateway connects through (e.g. http://host.docker.internal:7899)
     * @param allow    hosts sandboxes may reach over HTTPS; "*.example.com" allows strict subdomains
     * @param script   host path of sandbox/egress/egress.mjs
     */
    public record Egress(Boolean enabled, String upstream, java.util.List<String> allow, String script) {}

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
