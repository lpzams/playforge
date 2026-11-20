package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.event.RunEvent;
import dev.playforge.event.RunEventPublisher;
import dev.playforge.event.RunEventType;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.process.ExecResult;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.sandbox.BlockedRequest;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import dev.playforge.sandbox.local.LocalSandboxProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** After every step the orchestrator reports the outbound requests the sandbox's gateway refused. */
@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import({OrchestrationTestConfig.class, NetworkBlockedTest.BlockingSandboxConfig.class})
class NetworkBlockedTest {

    /** Answer of blockedSince for the n-th call (0 = the CODE step). */
    static volatile Function<Integer, List<BlockedRequest>> blocked = n -> List.of();
    static final AtomicInteger calls = new AtomicInteger();

    @TestConfiguration
    static class BlockingSandboxConfig {
        @Bean
        @Primary
        SandboxProvider blockingSandboxProvider(LocalSandboxProvider local) {
            return projectId -> {
                Sandbox inner = local.acquire(projectId);
                return new Sandbox() {
                    @Override public Path workdir() { return inner.workdir(); }
                    @Override public void syncIn(Path hostDir) { inner.syncIn(hostDir); }
                    @Override public void syncOut(Path hostDir) { inner.syncOut(hostDir); }
                    @Override public ExecResult exec(List<String> cmd, String stdin, Duration timeout,
                                                     CancellationToken cancel, Consumer<String> out) {
                        return inner.exec(cmd, stdin, timeout, cancel, out);
                    }
                    @Override public void exportDir(String rel, Path hostDir) { inner.exportDir(rel, hostDir); }
                    @Override public List<BlockedRequest> blockedSince(Instant since) {
                        return blocked.apply(calls.getAndIncrement());
                    }
                    @Override public void close() { inner.close(); }
                };
            };
        }
    }

    @Autowired RunOrchestrator orchestrator;
    @Autowired RunStore store;
    @Autowired RunEventPublisher events;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        playtester.reset();
        calls.set(0);
    }

    Run run() {
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        orchestrator.execute(run.getId(), new CancellationToken());
        return store.getRun(run.getId());
    }

    @Test
    void refusalsOfAStepAreReportedBeforeItFinishes() {
        blocked = n -> n == 0 ? List.of(new BlockedRequest("evil.com", 443, 2)) : List.of();
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        List<RunEvent> all = events.history(run.getId(), 0);
        List<RunEvent> reported = all.stream().filter(e -> e.type() == RunEventType.NETWORK_BLOCKED).toList();
        assertThat(reported).hasSize(1);
        assertThat(reported.getFirst().payload()).isEqualTo(
                Map.of("requests", List.of(Map.of("host", "evil.com", "port", 443, "count", 2))));
        int at = all.indexOf(reported.getFirst());
        RunEvent started = all.stream().filter(e -> e.type() == RunEventType.STEP_STARTED).findFirst().orElseThrow();
        RunEvent finished = all.stream().filter(e -> e.type() == RunEventType.STEP_FINISHED).findFirst().orElseThrow();
        assertThat(all.indexOf(started)).isLessThan(at);
        assertThat(all.indexOf(finished)).isGreaterThan(at);
        assertThat(started.payload()).containsEntry("type", "CODE");
    }

    @Test
    void unreadableRefusalsNeverFailARun() {
        blocked = n -> {
            throw new IllegalStateException("gateway gone");
        };
        Run run = run();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(events.history(run.getId(), 0)).noneMatch(e -> e.type() == RunEventType.NETWORK_BLOCKED);
    }
}
