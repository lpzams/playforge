package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ContainerPoolTest {

    static class FakeDockerOps implements DockerOps {
        volatile boolean available = true;
        final AtomicInteger created = new AtomicInteger();
        final List<String> removed = new CopyOnWriteArrayList<>();
        final Set<String> unhealthy = ConcurrentHashMap.newKeySet();

        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override public boolean available() { return available; }
        @Override public String runDetached(DockerRunSpec spec) {
            calls.add("runDetached");
            return "c" + created.incrementAndGet();
        }
        @Override public int removeLabeled(Map<String, String> labels) {
            calls.add("removeLabeled " + labels);
            return 0;
        }
        @Override public List<String> findRunning(Map<String, String> labels) { return List.of(); }
        @Override public void ensureNetwork(String name, boolean internal) { }
        @Override public void connect(String network, String containerId) { }
        @Override public void removeNamed(String name) { calls.add("removeNamed " + name); }
        @Override public String containerIp(String containerId, String network) { return ""; }
        @Override public ExecResult exec(String id, List<String> cmd, byte[] stdin, Duration t, CancellationToken c, Consumer<String> out) {
            return new ExecResult(0, "");
        }
        @Override public void copyIn(String id, Path hostDir) { }
        @Override public void copyOut(String id, Path hostDir) { }
        @Override public void copyDirOut(String id, String rel, Path hostDir) { }
        @Override public void remove(String id) { removed.add(id); }
        @Override public boolean healthy(String id) { return !unhealthy.contains(id); }
    }

    static final DockerRunSpec SPEC = new DockerRunSpec("img", Map.of(), List.of(), "2", "4g", 512, Map.of("playforge.pool", "app"));

    final FakeDockerOps ops = new FakeDockerOps();
    final ContainerPool pool = new ContainerPool(ops, SPEC, 2);

    @AfterEach
    void stop() {
        pool.shutdown();
    }

    @Test
    void prepareRunsBeforeEachContainerAndItsFailureIsTheCreateError() {
        List<String> order = new CopyOnWriteArrayList<>();
        FakeDockerOps recording = new FakeDockerOps() {
            @Override public String runDetached(DockerRunSpec spec) {
                order.add("run");
                return super.runDetached(spec);
            }
        };
        ContainerPool prepared = new ContainerPool(recording, SPEC, 2, false, () -> order.add("prepare"));
        try {
            prepared.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> prepared.idleCount() == 2);
            assertThat(order).containsExactly("prepare", "run", "prepare", "run");
        } finally {
            prepared.shutdown();
        }

        ContainerPool broken = new ContainerPool(new FakeDockerOps(), SPEC, 1, false, () -> {
            throw new IllegalStateException("gateway down");
        });
        try {
            assertThatThrownBy(() -> broken.acquire(Duration.ofSeconds(2))).hasMessageContaining("gateway down");
        } finally {
            broken.shutdown();
        }
    }

    @Test
    void startSweepsOrphansBeforeWarmupWhenEnabled() {
        ContainerPool p = new ContainerPool(ops, SPEC, 2, true);
        try {
            p.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> p.idleCount() == 2);
            assertThat(ops.calls.getFirst()).isEqualTo("removeLabeled {playforge.pool=app}");
            assertThat(ops.calls).filteredOn(c -> c.startsWith("removeLabeled")).hasSize(1);
        } finally {
            p.shutdown();
        }
    }

    @Test
    void noSweepByDefault() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        assertThat(ops.calls).noneMatch(c -> c.startsWith("removeLabeled"));
    }

    /** After a Docker Desktop restart, warmed containers are gone; acquire must not hand them out. */
    @Test
    void acquireSkipsDeadIdleContainers() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        ops.unhealthy.addAll(Set.of("c1", "c2"));

        String id = pool.acquire(Duration.ofSeconds(5));

        assertThat(id).isNotIn("c1", "c2");
        await().atMost(Duration.ofSeconds(5)).until(() -> ops.removed.containsAll(List.of("c1", "c2")));
    }

    @Test
    void dockerStoppedAfterWarmupSaysUnavailable() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        ops.unhealthy.addAll(Set.of("c1", "c2"));
        ops.available = false;

        assertThatThrownBy(() -> pool.acquire(Duration.ofSeconds(60)))
                .isInstanceOf(DockerUnavailableException.class)
                .hasMessage("Docker is not available");
    }

    @Test
    void containerStartFailureIsReportedFast() {
        FakeDockerOps failing = new FakeDockerOps() {
            @Override
            public String runDetached(DockerRunSpec spec) {
                throw new IllegalStateException("Unable to find image 'img:latest' locally");
            }
        };
        ContainerPool p = new ContainerPool(failing, SPEC, 2);
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> p.acquire(Duration.ofSeconds(60)))
                    .isInstanceOf(DockerUnavailableException.class)
                    .hasMessageContaining("Unable to find image");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        } finally {
            p.shutdown();
        }
    }

    @Test
    void startWarmsUpToSize() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        assertThat(ops.created).hasValue(2);
    }

    @Test
    void acquireReturnsWarmContainerAndReplenishes() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);

        String id = pool.acquire(Duration.ofSeconds(5));

        assertThat(id).startsWith("c");
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        assertThat(ops.created).hasValue(3);
    }

    @Test
    void discardRemovesContainer() {
        pool.start();
        String id = pool.acquire(Duration.ofSeconds(5));
        pool.discard(id);
        await().atMost(Duration.ofSeconds(5)).until(() -> ops.removed.contains(id));
    }

    @Test
    void unavailableDockerFailsFastWithClearMessage() {
        ops.available = false;
        pool.start();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> pool.acquire(Duration.ofSeconds(60)))
                .isInstanceOf(DockerUnavailableException.class)
                .hasMessage("Docker is not available");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void recoversWhenDockerStartsLater() {
        ops.available = false;
        pool.start();
        ops.available = true;
        assertThat(pool.acquire(Duration.ofSeconds(5))).startsWith("c");
    }

    @Test
    void healthCheckReplacesDeadContainer() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        ops.unhealthy.add("c1");

        pool.healthCheck();

        await().atMost(Duration.ofSeconds(5)).until(() -> ops.removed.contains("c1") && pool.idleCount() == 2);
        assertThat(ops.created).hasValue(3);
    }

    /** Container exists server-side as soon as docker run starts; interrupting the client loses its id. */
    static final class SlowCreateDockerOps extends FakeDockerOps {
        final Set<String> createdIds = ConcurrentHashMap.newKeySet();

        @Override
        public String runDetached(DockerRunSpec spec) {
            String id = "slow" + created.incrementAndGet();
            createdIds.add(id);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IllegalStateException("docker client killed; id lost");
            }
            return id;
        }
    }

    @Test
    void shutdownDuringWarmupLeavesNoContainers() {
        SlowCreateDockerOps slow = new SlowCreateDockerOps();
        ContainerPool p = new ContainerPool(slow, SPEC, 2);
        p.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !slow.createdIds.isEmpty());

        p.shutdown();

        assertThat(slow.removed).containsAll(slow.createdIds);
    }

    @Test
    void shutdownRemovesAllIncludingLeased() {
        pool.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);
        String leased = pool.acquire(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.idleCount() == 2);

        pool.shutdown();

        assertThat(ops.removed).contains(leased).hasSize(3);
    }
}
