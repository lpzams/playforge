package dev.playforge.api;

import dev.playforge.api.dto.RunView;
import dev.playforge.orchestration.RunExecutor;
import dev.playforge.run.Run;
import dev.playforge.run.RunStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunStore store;
    private final RunExecutor executor;

    public RunController(RunStore store, RunExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    @GetMapping("/{runId}")
    public RunView get(@PathVariable long runId) {
        Run run = store.getRun(runId);
        return RunView.of(run, store.listSteps(runId));
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long runId) {
        store.getRun(runId); // 404 if unknown
        if (!executor.cancel(runId)) throw new ConflictException("run " + runId + " is already finished");
        return ResponseEntity.accepted().build();
    }
}
