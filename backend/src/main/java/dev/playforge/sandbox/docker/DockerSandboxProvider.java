package dev.playforge.sandbox.docker;

import dev.playforge.config.PlayforgeProperties;
import dev.playforge.process.ProcessRunner;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Sandboxes from a pool of pre-started Docker containers (provider = docker). */
@Component
@ConditionalOnProperty(name = "playforge.sandbox.provider", havingValue = "docker")
public class DockerSandboxProvider implements SandboxProvider {

    static final String CODEX_AUTH_TARGET = "/mnt/creds/codex/auth.json";
    /** Marks containers owned by the application's pool, so a restart can remove ones a hard stop left behind. */
    static final String POOL_LABEL = "playforge.pool";

    private final PlayforgeProperties.SandboxConfig config;
    private final DockerOps ops;
    private final ContainerPool pool;

    public DockerSandboxProvider(PlayforgeProperties props, ProcessRunner runner) {
        this.config = props.sandbox();
        this.ops = new CliDockerOps(runner, config.dockerCommand(), System::getenv);
        List<DockerRunSpec.Mount> mounts = Files.isRegularFile(Path.of(config.codexAuthFile()))
                ? List.of(new DockerRunSpec.Mount(Path.of(config.codexAuthFile()).toAbsolutePath().toString(), CODEX_AUTH_TARGET))
                : List.of();
        DockerRunSpec spec = new DockerRunSpec(config.image(),
                Map.of("CLAUDE_CODE_OAUTH_TOKEN", "PLAYFORGE_CLAUDE_TOKEN"), mounts, "2", "4g", 512,
                Map.of(POOL_LABEL, "app"));
        this.pool = new ContainerPool(ops, spec, config.poolSize(), true);
    }

    @PostConstruct
    void start() {
        pool.start();
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    void healthCheck() {
        pool.healthCheck();
    }

    @Override
    public Sandbox acquire(String projectId) {
        return new DockerSandbox(pool.acquire(config.acquireTimeout()), pool, ops);
    }
}
