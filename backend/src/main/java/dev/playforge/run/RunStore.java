package dev.playforge.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RunStore {

    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectMapper projects;
    private final RunMapper runs;
    private final StepMapper steps;

    public RunStore(ProjectMapper projects, RunMapper runs, StepMapper steps) {
        this.projects = projects;
        this.runs = runs;
        this.steps = steps;
    }

    public Project createProject(String title, String prompt) {
        Project p = new Project();
        p.setId(randomId());
        p.setTitle(title);
        p.setPrompt(prompt);
        p.setCreatedAt(Instant.now());
        projects.insert(p);
        return p;
    }

    public Project getProject(String projectId) {
        Project p = projects.selectById(projectId);
        if (p == null) throw new NoSuchElementException("project " + projectId + " not found");
        return p;
    }

    public List<Project> listProjects() {
        return projects.selectList(new LambdaQueryWrapper<Project>().orderByDesc(Project::getCreatedAt));
    }

    public Run createRun(String projectId, String instruction) {
        return createRun(projectId, instruction, null);
    }

    public Run createRun(String projectId, String instruction, Markup markup) {
        Run r = new Run();
        r.setMarkup(markup == null ? null : markup.toJson());
        r.setProjectId(projectId);
        r.setInstruction(instruction);
        r.setStatus(RunStatus.QUEUED);
        r.setCreatedAt(Instant.now());
        runs.insert(r);
        return r;
    }

    public Run getRun(long runId) {
        Run r = runs.selectById(runId);
        if (r == null) throw new NoSuchElementException("run " + runId + " not found");
        return r;
    }

    public List<Run> listRuns(String projectId) {
        return runs.selectList(new LambdaQueryWrapper<Run>()
                .eq(Run::getProjectId, projectId).orderByAsc(Run::getId));
    }

    public List<Run> findUnfinishedRuns() {
        return runs.selectList(new LambdaQueryWrapper<Run>()
                .in(Run::getStatus, RunStatus.QUEUED, RunStatus.RUNNING)
                .orderByAsc(Run::getId));
    }

    public void markRunStarted(long runId, String baseCommit) {
        runs.update(new LambdaUpdateWrapper<Run>().eq(Run::getId, runId)
                .set(Run::getStatus, RunStatus.RUNNING)
                .set(Run::getBaseCommit, baseCommit)
                .set(Run::getStartedAt, Instant.now()));
    }

    public void finishRun(long runId, RunStatus status, String error, String resultCommit) {
        runs.update(new LambdaUpdateWrapper<Run>().eq(Run::getId, runId)
                .set(Run::getStatus, status)
                .set(Run::getError, error)
                .set(Run::getResultCommit, resultCommit)
                .set(Run::getFinishedAt, Instant.now()));
    }

    public Step startStep(long runId, StepType type, int round, StepTrigger trigger) {
        Step s = new Step();
        s.setRunId(runId);
        s.setType(type);
        s.setRound(round);
        s.setTrigger(trigger);
        s.setStatus(StepStatus.RUNNING);
        s.setStartedAt(Instant.now());
        steps.insert(s);
        return s;
    }

    public void completeStep(long stepId, Boolean ok, String outputJson) {
        steps.update(new LambdaUpdateWrapper<Step>().eq(Step::getId, stepId)
                .set(Step::getStatus, StepStatus.DONE)
                .set(Step::getOk, ok)
                .set(Step::getOutputJson, outputJson)
                .set(Step::getFinishedAt, Instant.now()));
    }

    public void failStep(long stepId, String error) {
        steps.update(new LambdaUpdateWrapper<Step>().eq(Step::getId, stepId)
                .set(Step::getStatus, StepStatus.FAILED)
                .set(Step::getError, error)
                .set(Step::getFinishedAt, Instant.now()));
    }

    public List<Step> listSteps(long runId) {
        return steps.selectList(new LambdaQueryWrapper<Step>()
                .eq(Step::getRunId, runId).orderByAsc(Step::getId));
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
