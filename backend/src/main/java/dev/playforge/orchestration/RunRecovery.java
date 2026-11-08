package dev.playforge.orchestration;

import dev.playforge.run.Run;
import dev.playforge.run.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** On startup, resubmits every run that was queued or in flight when the process stopped. */
@Component
@ConditionalOnProperty(name = "playforge.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class RunRecovery {

    private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

    private final RunStore store;
    private final RunExecutor executor;

    public RunRecovery(RunStore store, RunExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (Run run : store.findUnfinishedRuns()) {
            log.info("recovering run {} ({})", run.getId(), run.getStatus());
            executor.submit(run.getId());
        }
    }
}
