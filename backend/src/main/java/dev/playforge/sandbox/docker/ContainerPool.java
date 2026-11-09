package dev.playforge.sandbox.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps {@code size} started, idle containers ready. A container is used by exactly one sandbox and is
 * destroyed afterwards ({@link #discard}); the pool refills itself in the background.
 */
public class ContainerPool {

    private static final Logger log = LoggerFactory.getLogger(ContainerPool.class);

    private final DockerOps ops;
    private final DockerRunSpec spec;
    private final int size;
    private final LinkedBlockingQueue<String> idle = new LinkedBlockingQueue<>();
    private final Set<String> all = ConcurrentHashMap.newKeySet();
    private final AtomicInteger creating = new AtomicInteger();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final boolean sweepOnStart;
    private volatile boolean stopped;

    public ContainerPool(DockerOps ops, DockerRunSpec spec, int size) {
        this(ops, spec, size, false);
    }

    /**
     * @param sweepOnStart remove containers carrying this pool's labels before warming up. They were left by
     *                     a previous process that was killed without shutdown (e.g. an IDE hard stop).
     */
    public ContainerPool(DockerOps ops, DockerRunSpec spec, int size, boolean sweepOnStart) {
        this.ops = ops;
        this.spec = spec;
        this.size = size;
        this.sweepOnStart = sweepOnStart;
    }

    /** Starts warming up asynchronously. Never throws: Docker may come up later. */
    public void start() {
        background.submit(() -> {
            if (!ops.available()) {
                log.warn("Docker is not available; sandbox pool will warm up on first use");
                return;
            }
            if (sweepOnStart && !spec.labels().isEmpty()) {
                try {
                    int n = ops.removeLabeled(spec.labels());
                    if (n > 0) log.info("removed {} sandbox container(s) left by a previous run", n);
                } catch (RuntimeException e) {
                    log.warn("could not remove leftover sandbox containers: {}", e.getMessage());
                }
            }
            replenish();
        });
    }

    public String acquire(Duration timeout) {
        String id = idle.poll();
        if (id == null) {
            if (!ops.available()) throw new DockerUnavailableException("Docker is not available");
            replenish();
            try {
                id = idle.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DockerUnavailableException("interrupted while waiting for a sandbox container");
            }
            if (id == null) {
                throw new DockerUnavailableException("no sandbox container became ready within " + timeout.toSeconds() + "s");
            }
        }
        background.submit(this::replenish);
        return id;
    }

    /** Destroys a used container and refills the pool. */
    public void discard(String containerId) {
        all.remove(containerId);
        background.submit(() -> {
            safeRemove(containerId);
            replenish();
        });
    }

    /** Removes idle containers that no longer respond, then refills. */
    public void healthCheck() {
        for (String id : List.copyOf(idle)) {
            if (!ops.healthy(id) && idle.remove(id)) {
                log.warn("sandbox container {} is unhealthy; replacing it", id);
                all.remove(id);
                safeRemove(id);
            }
        }
        replenish();
    }

    /**
     * Stops refilling, lets in-flight {@code docker run}s finish (interrupting one would create a container
     * whose id we never learn), then removes every container this pool started.
     */
    public void shutdown() {
        stopped = true;
        background.shutdown();
        try {
            if (!background.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("sandbox pool background tasks did not finish within 60s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String id : List.copyOf(all)) safeRemove(id);
        all.clear();
        idle.clear();
    }

    public int idleCount() {
        return idle.size();
    }

    private void replenish() {
        while (!stopped) {
            synchronized (this) {
                if (idle.size() + creating.get() >= size) return;
                creating.incrementAndGet();
            }
            try {
                String id = ops.runDetached(spec);
                all.add(id);
                if (stopped) {
                    safeRemove(id);
                    return;
                }
                idle.add(id);
            } catch (RuntimeException e) {
                log.warn("could not start a sandbox container: {}", e.getMessage());
                return;
            } finally {
                creating.decrementAndGet();
            }
        }
    }

    private void safeRemove(String id) {
        try {
            ops.remove(id);
        } catch (RuntimeException e) {
            log.warn("could not remove container {}: {}", id, e.getMessage());
        }
    }
}
