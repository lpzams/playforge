package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs each run on its own virtual thread; at most one execution per run at a time. */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final RunOrchestrator orchestrator;
    private final RunStore store;
    private final RunEventPublisher events;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, CancellationToken> active = new ConcurrentHashMap<>();

    public RunExecutor(RunOrchestrator orchestrator, RunStore store, RunEventPublisher events) {
        this.orchestrator = orchestrator;
        this.store = store;
        this.events = events;
    }

    public void submit(long runId) {
        CancellationToken token = new CancellationToken();
        if (active.putIfAbsent(runId, token) != null) return;
        pool.submit(() -> {
            try {
                orchestrator.execute(runId, token);
            } catch (RuntimeException e) {
                log.error("run {} execution failed", runId, e);
            } finally {
                active.remove(runId, token);
            }
        });
    }

    public boolean cancel(long runId) {
        CancellationToken token = active.get(runId);
        if (token != null) {
            token.cancel();
            return true;
        }
        Run run;
        try {
            run = store.getRun(runId);
        } catch (NoSuchElementException e) {
            return false;
        }
        if (run.getStatus() != RunStatus.QUEUED) return false;
        store.finishRun(runId, RunStatus.CANCELLED, "cancelled", null);
        events.publish(runId, RunEventType.RUN_FINISHED, "system", Map.of("status", RunStatus.CANCELLED));
        return true;
    }

    public boolean isActive(long runId) {
        return active.containsKey(runId);
    }

    /**
     * Process shutdown: signal in-flight runs with {@link CancellationToken#shutdown()} so they stop without
     * writing a terminal state (RunRecovery resumes them on next start), and wait briefly so their last DB
     * writes land before the datasource closes.
     */
    @PreDestroy
    void shutdown() throws InterruptedException {
        active.values().forEach(CancellationToken::shutdown);
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("{} run(s) did not stop within 10s of shutdown", active.size());
        }
    }
}
