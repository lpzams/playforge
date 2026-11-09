package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.common.CancelledException;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Docker + real sandbox image; no credentials needed. Skipped when Docker is unavailable. */
@Tag("docker")
class DockerSandboxIntegrationTest {

    @TempDir
    Path host;

    CliDockerOps ops;
    ContainerPool pool;

    @BeforeEach
    void setUp() {
        ops = new CliDockerOps(new ProcessRunner(), "docker", System::getenv);
        assumeTrue(ops.available(), "Docker is not available");
        pool = new ContainerPool(ops, new DockerRunSpec("playforge-sandbox:p2", Map.of(), List.of(), "2", "4g", 512), 1);
        pool.start();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdown();
    }

    boolean containerExists(String id) {
        List<String> out = new CopyOnWriteArrayList<>();
        new ProcessRunner().run(List.of("docker", "ps", "-aq", "--no-trunc", "--filter", "id=" + id), Map.of(), null,
                Duration.ofSeconds(20), new CancellationToken(), out::add);
        return out.stream().anyMatch(l -> !l.isBlank());
    }

    @Test
    void syncRoundTripAndExec() throws Exception {
        Files.writeString(host.resolve("a.txt"), "你好", StandardCharsets.UTF_8);
        DockerSandbox sandbox = new DockerSandbox(pool.acquire(Duration.ofSeconds(60)), pool, ops);
        String id = sandbox.containerId();
        try {
            sandbox.syncIn(host);
            List<String> out = new CopyOnWriteArrayList<>();
            ExecResult r = sandbox.exec(List.of("cat", "a.txt"), null, Duration.ofSeconds(20), new CancellationToken(), out::add);
            assertThat(r.exitCode()).isZero();
            assertThat(out).containsExactly("你好");

            sandbox.exec(List.of("sh", "-c", "echo x > b.txt && rm a.txt"), null, Duration.ofSeconds(20), new CancellationToken(), l -> { });
            sandbox.syncOut(host);
            assertThat(host.resolve("b.txt")).exists();
            assertThat(host.resolve("a.txt")).doesNotExist();
        } finally {
            sandbox.close();
        }
        // close() hands the container back to the pool, which destroys it in the background
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !containerExists(id));
    }

    @Test
    void syncInMirrorsAndDropsContainerOnlyFiles() throws Exception {
        Files.writeString(host.resolve("a.txt"), "1");
        DockerSandbox sandbox = new DockerSandbox(pool.acquire(Duration.ofSeconds(60)), pool, ops);
        try {
            sandbox.syncIn(host);
            sandbox.exec(List.of("sh", "-c", "echo reviewer-edit > extra.txt"), null, Duration.ofSeconds(20), new CancellationToken(), l -> { });
            sandbox.syncIn(host);
            List<String> out = new CopyOnWriteArrayList<>();
            sandbox.exec(List.of("ls", "-A"), null, Duration.ofSeconds(20), new CancellationToken(), out::add);
            assertThat(out).containsExactly("a.txt");
        } finally {
            sandbox.close();
        }
    }

    @Test
    void cancelStopsExecAndContainerIsRemoved() {
        DockerSandbox sandbox = new DockerSandbox(pool.acquire(Duration.ofSeconds(60)), pool, ops);
        String id = sandbox.containerId();
        CancellationToken token = new CancellationToken();
        try (var ex = Executors.newSingleThreadScheduledExecutor()) {
            ex.schedule(token::cancel, 1, TimeUnit.SECONDS);
            assertThatThrownBy(() -> sandbox.exec(List.of("sleep", "30"), null, Duration.ofSeconds(60), token, l -> { }))
                    .isInstanceOf(CancelledException.class);
        } finally {
            sandbox.close();
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !containerExists(id));
    }
}
