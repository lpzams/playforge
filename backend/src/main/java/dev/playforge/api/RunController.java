package dev.playforge.api;

import dev.playforge.api.dto.RunView;
import dev.playforge.orchestration.RunExecutor;
import dev.playforge.run.Run;
import dev.playforge.run.MarkupImages;
import dev.playforge.run.RunStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunStore store;
    private final RunExecutor executor;
    private final MarkupImages images;

    public RunController(RunStore store, RunExecutor executor, MarkupImages images) {
        this.store = store;
        this.executor = executor;
        this.images = images;
    }

    @GetMapping("/{runId}")
    public RunView get(@PathVariable long runId) {
        Run run = store.getRun(runId);
        return RunView.of(run, store.listSteps(runId));
    }

    /** The marked screenshot of a follow-up run (immutable once stored). */
    @GetMapping("/{runId}/markup.png")
    public ResponseEntity<byte[]> markup(@PathVariable long runId) {
        store.getRun(runId); // 404 if unknown
        byte[] png = images.load(runId).orElseThrow(() -> new NoSuchElementException("run " + runId + " has no marked screenshot"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long runId) {
        store.getRun(runId); // 404 if unknown
        if (!executor.cancel(runId)) throw new ConflictException("run " + runId + " is already finished");
        return ResponseEntity.accepted().build();
    }
}
