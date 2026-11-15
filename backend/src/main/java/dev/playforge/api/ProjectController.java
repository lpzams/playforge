package dev.playforge.api;

import dev.playforge.api.dto.CreateProjectRequest;
import dev.playforge.api.dto.CreateRunRequest;
import dev.playforge.api.dto.CreatedResponse;
import dev.playforge.api.dto.ProjectView;
import dev.playforge.api.dto.RunView;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.orchestration.RunExecutor;
import dev.playforge.run.Project;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final int TITLE_MAX = 40;

    private final RunStore store;
    private final RunExecutor executor;
    private final PlayforgeProperties props;

    public ProjectController(RunStore store, RunExecutor executor, PlayforgeProperties props) {
        this.props = props;
        this.store = store;
        this.executor = executor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse create(@Valid @RequestBody CreateProjectRequest req) {
        Project p = store.createProject(title(req.prompt()), req.prompt());
        Run run = store.createRun(p.getId(), req.prompt());
        executor.submit(run.getId());
        return new CreatedResponse(p.getId(), run.getId());
    }

    @PostMapping("/{projectId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse createRun(@PathVariable String projectId, @Valid @RequestBody CreateRunRequest req) {
        store.getProject(projectId);
        boolean busy = store.listRuns(projectId).stream()
                .anyMatch(r -> r.getStatus() == RunStatus.QUEUED || r.getStatus() == RunStatus.RUNNING);
        if (busy) throw new ConflictException("project " + projectId + " already has an active run");
        Run run = store.createRun(projectId, req.instruction());
        executor.submit(run.getId());
        return new CreatedResponse(projectId, run.getId());
    }

    @GetMapping
    public List<ProjectView> list() {
        return store.listProjects().stream().map(this::view).toList();
    }

    @GetMapping("/{projectId}")
    public ProjectView get(@PathVariable String projectId) {
        return view(store.getProject(projectId));
    }

    private ProjectView view(Project p) {
        List<RunView> runs = store.listRuns(p.getId()).stream()
                .map(r -> RunView.of(r, store.listSteps(r.getId())))
                .toList();
        boolean preview = Files.isRegularFile(props.previewDir().resolve(p.getId()).resolve("index.html"));
        return new ProjectView(p.getId(), p.getTitle(), p.getPrompt(), p.getCreatedAt(), preview, runs);
    }

    private static String title(String prompt) {
        String line = prompt.strip().lines().findFirst().orElse("").strip();
        return line.length() <= TITLE_MAX ? line : line.substring(0, TITLE_MAX);
    }
}
