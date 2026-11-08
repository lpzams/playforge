package dev.playforge.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.common.CancellationToken;
import dev.playforge.common.CancelledException;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.engine.AgentEngine;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.EngineException;
import dev.playforge.engine.EngineRegistry;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.orchestration.PlanDecision.Execute;
import dev.playforge.orchestration.PlanDecision.Finish;
import dev.playforge.playtest.PlaytestReport;
import dev.playforge.playtest.Playtester;
import dev.playforge.review.ReviewParser;
import dev.playforge.review.ReviewReport;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.run.Step;
import dev.playforge.run.StepStatus;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import dev.playforge.workspace.GitWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a run step by step: asks {@link RunPlanner} for the next step, runs it, records the result,
 * and emits events. Re-entrant: calling execute on a half-finished run resumes it.
 */
@Service
public class RunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);
    private static final String SYSTEM = "system";

    private final RunStore store;
    private final RunEventPublisher events;
    private final EngineRegistry engines;
    private final SandboxProvider sandboxes;
    private final Playtester playtester;
    private final PlayforgeProperties props;
    private final ObjectMapper json;
    private final RunPlanner planner;

    public RunOrchestrator(RunStore store, RunEventPublisher events, EngineRegistry engines, SandboxProvider sandboxes,
                           Playtester playtester, PlayforgeProperties props, ObjectMapper json) {
        this.store = store;
        this.events = events;
        this.engines = engines;
        this.sandboxes = sandboxes;
        this.playtester = playtester;
        this.props = props;
        this.json = json;
        PlayforgeProperties.Orchestration o = props.orchestration();
        this.planner = new RunPlanner(o.maxReviewRounds(), o.maxPlaytestFixes());
    }

    /** Context of one execution; lives for the duration of {@link #execute}. */
    private record Ctx(Run run, String baseCommit, Path hostDir, GitWorkspace git, Sandbox sandbox, CancellationToken cancel) {}

    public void execute(long runId, CancellationToken cancel) {
        Run run = store.getRun(runId);
        if (run.getStatus() != RunStatus.QUEUED && run.getStatus() != RunStatus.RUNNING) return;
        Path hostDir = props.workspaceRoot().resolve(run.getProjectId());
        try (GitWorkspace git = GitWorkspace.openOrInit(hostDir)) {
            boolean resumed = run.getStartedAt() != null;
            String base = resumed ? run.getBaseCommit() : git.head().orElse(null);
            if (!resumed) store.markRunStarted(runId, base);
            events.publish(runId, RunEventType.RUN_STARTED, SYSTEM, mapOf("resumed", resumed, "baseCommit", base));

            Instant startedAt = resumed ? run.getStartedAt() : Instant.now();
            try (Sandbox sandbox = sandboxes.acquire(run.getProjectId())) {
                sandbox.syncIn(hostDir);
                loop(new Ctx(run, base, hostDir, git, sandbox, cancel), startedAt);
            }
        } catch (CancelledException e) {
            finish(runId, RunStatus.CANCELLED, "cancelled", null);
        } catch (RuntimeException e) {
            log.error("run {} crashed", runId, e);
            finish(runId, RunStatus.FAILED, "internal error: " + e.getMessage(), null);
        }
    }

    private void loop(Ctx ctx, Instant startedAt) {
        long runId = ctx.run().getId();
        while (true) {
            List<Step> history = store.listSteps(runId);
            PlanDecision decision = planner.next(history);
            if (decision instanceof Finish f) {
                String commit = f.status() == RunStatus.SUCCEEDED ? ctx.git().head().orElse(null) : null;
                finish(runId, f.status(), f.status() == RunStatus.SUCCEEDED ? null : f.reason(), commit);
                return;
            }
            ctx.cancel().throwIfCancelled();
            if (Duration.between(startedAt, Instant.now()).compareTo(props.orchestration().runTimeout()) > 0) {
                finish(runId, RunStatus.FAILED, "run timeout", null);
                return;
            }

            Execute ex = (Execute) decision;
            Step step = ex.resume() ? history.getLast() : store.startStep(runId, ex.type(), ex.round(), ex.trigger());
            events.publish(runId, RunEventType.STEP_STARTED, SYSTEM, mapOf("stepId", step.getId(), "type", ex.type(),
                    "round", ex.round(), "trigger", ex.trigger(), "resume", ex.resume()));
            try {
                runStep(ctx, step, history);
                events.publish(runId, RunEventType.STEP_FINISHED, SYSTEM, mapOf("stepId", step.getId(), "status", StepStatus.DONE));
            } catch (CancelledException e) {
                store.failStep(step.getId(), "cancelled");
                throw e;
            } catch (EngineException | RuntimeException e) {
                store.failStep(step.getId(), e.getMessage());
                events.publish(runId, RunEventType.STEP_FINISHED, SYSTEM,
                        mapOf("stepId", step.getId(), "status", StepStatus.FAILED, "error", e.getMessage()));
            }
            ctx.cancel().throwIfCancelled();
        }
    }

    private void runStep(Ctx ctx, Step step, List<Step> history) throws EngineException {
        String instruction = ctx.run().getInstruction();
        switch (step.getType()) {
            case CODE -> {
                AgentResult r = callEngine(ctx, engines.coder(), PromptTemplates.code(instruction));
                ctx.sandbox().syncOut(ctx.hostDir());
                store.completeStep(step.getId(), null, write(Map.of("finalText", r.finalText())));
            }
            case FIX -> {
                String prompt = step.getTrigger() == StepTrigger.PLAYTEST
                        ? PromptTemplates.fixFromPlaytest(instruction, read(lastDone(history, StepType.PLAYTEST), PlaytestReport.class).failures())
                        : PromptTemplates.fixFromReview(instruction, read(lastDone(history, StepType.REVIEW), ReviewReport.class).blockers());
                AgentResult r = callEngine(ctx, engines.coder(), prompt);
                ctx.sandbox().syncOut(ctx.hostDir());
                store.completeStep(step.getId(), null, write(Map.of("finalText", r.finalText())));
            }
            case REVIEW -> {
                String diff = ctx.git().diffWorkingTree(ctx.baseCommit());
                AgentResult r = callEngine(ctx, engines.reviewer(), PromptTemplates.review(instruction, diff));
                ReviewReport report = ReviewParser.parse(r.finalText());
                long runId = ctx.run().getId();
                if (!report.parsed()) {
                    events.publish(runId, RunEventType.REVIEW_UNPARSEABLE, SYSTEM, mapOf("raw", truncate(r.finalText(), 4000)));
                }
                events.publish(runId, RunEventType.REVIEW_RESULT, SYSTEM,
                        mapOf("parsed", report.parsed(), "blockers", report.blockers().size(), "issues", report.issues()));
                store.completeStep(step.getId(), !report.hasBlockers(), write(report));
            }
            case PLAYTEST -> {
                PlaytestReport report = playtester.run(ctx.sandbox(), ctx.cancel());
                events.publish(ctx.run().getId(), RunEventType.PLAYTEST_RESULT, SYSTEM,
                        mapOf("passed", report.passed(), "failures", report.failures()));
                store.completeStep(step.getId(), report.passed(), write(report));
            }
            case COMMIT -> {
                String hash = ctx.git().commitAll("run " + ctx.run().getId() + ": " + firstLine(instruction, 72));
                store.completeStep(step.getId(), null, write(Map.of("commit", hash)));
            }
        }
    }

    /** Calls an engine, retrying retryable failures with exponential backoff. */
    private AgentResult callEngine(Ctx ctx, AgentEngine engine, String prompt) throws EngineException {
        PlayforgeProperties.Orchestration o = props.orchestration();
        long runId = ctx.run().getId();
        AgentRequest req = new AgentRequest(engine.role(), prompt, o.stepTimeout());
        for (int attempt = 1; ; attempt++) {
            ctx.cancel().throwIfCancelled();
            try {
                return engine.run(req, ctx.sandbox(), (type, payload) -> events.publish(runId, type, engine.name(), payload), ctx.cancel());
            } catch (EngineException e) {
                if (!e.retryable() || attempt > o.maxEngineRetries()) throw e;
                Duration delay = e.retryAfter().orElse(o.retryBaseDelay().multipliedBy(1L << (attempt - 1)));
                events.publish(runId, RunEventType.RETRY_SCHEDULED, engine.name(),
                        mapOf("attempt", attempt, "delayMs", delay.toMillis(), "error", e.getMessage()));
                sleep(delay, ctx.cancel());
            }
        }
    }

    private static void sleep(Duration d, CancellationToken cancel) {
        long until = System.nanoTime() + d.toNanos();
        while (System.nanoTime() < until) {
            cancel.throwIfCancelled();
            try {
                Thread.sleep(Math.min(50, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }

    private void finish(long runId, RunStatus status, String reason, String commit) {
        store.finishRun(runId, status, reason, commit);
        events.publish(runId, RunEventType.RUN_FINISHED, SYSTEM, mapOf("status", status, "reason", reason, "commit", commit));
    }

    private static Step lastDone(List<Step> history, StepType type) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Step s = history.get(i);
            if (s.getType() == type && s.getStatus() == StepStatus.DONE) return s;
        }
        throw new IllegalStateException("no completed " + type + " step to fix from");
    }

    private <T> T read(Step step, Class<T> type) {
        try {
            return json.readValue(step.getOutputJson(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt output of step " + step.getId(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s, int max) {
        String line = s.lines().findFirst().orElse("");
        return line.length() <= max ? line : line.substring(0, max);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Map.of that tolerates null values (event payloads often carry optional fields). */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
