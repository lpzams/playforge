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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DockerSandboxProvider.class);

    static final String CODEX_AUTH_TARGET = "/mnt/creds/codex/auth.json";
    /** Marks containers owned by the application's pool, so a restart can remove ones a hard stop left behind. */
    static final String POOL_LABEL = "playforge.pool";

    private final PlayforgeProperties.SandboxConfig config;
    private final DockerOps ops;
    private final ContainerPool pool;
    /** Null when the sandbox network is open (egress disabled). */
    private final EgressGateway egress;

    public DockerSandboxProvider(PlayforgeProperties props, ProcessRunner runner) {
        this.config = props.sandbox();
        this.ops = new CliDockerOps(runner, config.dockerCommand(), System::getenv);
        List<DockerRunSpec.Mount> mounts = Files.isRegularFile(Path.of(config.codexAuthFile()))
                ? List.of(new DockerRunSpec.Mount(Path.of(config.codexAuthFile()).toAbsolutePath().toString(), CODEX_AUTH_TARGET))
                : List.of();
        DockerRunSpec spec = new DockerRunSpec(config.image(),
                Map.of("CLAUDE_CODE_OAUTH_TOKEN", "PLAYFORGE_CLAUDE_TOKEN"), mounts, "2", "4g", 512,
                Map.of(POOL_LABEL, "app"));
        if (config.egressEnabled()) {
            PlayforgeProperties.Egress e = config.egress();
            this.egress = new EgressGateway(ops, config.image(), Path.of(e.script().trim()),
                    e.allow() == null ? List.of() : e.allow(), e.upstream());
            this.pool = new ContainerPool(ops, spec.withNetwork(EgressGateway.NETWORK, egress.sandboxEnv()),
                    config.poolSize(), true, egress::ensure);
        } else {
            this.egress = null;
            this.pool = new ContainerPool(ops, spec, config.poolSize(), true);
        }
    }

    @PostConstruct
    void start() {
        pool.start();
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
        if (egress != null) egress.remove();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    void healthCheck() {
        if (egress != null && ops.available()) {
            try {
                egress.ensure(); // Docker Desktop restarts wipe the gateway
            } catch (RuntimeException e) {
                log.warn("egress gateway check failed: {}", e.getMessage());
            }
        }
        pool.healthCheck();
    }

    @Override
    public Sandbox acquire(String projectId) {
        return new DockerSandbox(pool.acquire(config.acquireTimeout()), pool, ops, egress);
    }
}
