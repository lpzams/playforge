package dev.playforge.orchestration;

import dev.playforge.config.PlayforgeProperties;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import dev.playforge.run.Step;
import dev.playforge.run.StepTrigger;
import dev.playforge.run.StepType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class RunRecoveryTest {

    @Autowired RunStore store;
    @Autowired RunExecutor executor;
    @Autowired PlayforgeProperties props;
    @Autowired ScriptedEngine scriptedCoder;
    @Autowired ScriptedEngine scriptedReviewer;
    @Autowired ScriptedPlaytester playtester;

    @BeforeEach
    void reset() {
        scriptedCoder.reset();
        scriptedReviewer.reset();
        playtester.reset();
    }

    @Test
    void resumesInterruptedRunWithoutRedoingCompletedSteps() throws Exception {
        // Crash scene: CODE finished and wrote its file, REVIEW was in flight when the process died.
        Run run = store.createRun(store.createProject("t", "躲陨石").getId(), "躲陨石");
        store.markRunStarted(run.getId(), null);
        Step code = store.startStep(run.getId(), StepType.CODE, 0, StepTrigger.NONE);
        Path dir = props.workspaceRoot().resolve(run.getProjectId());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("main.js"), "// from before the crash");
        store.completeStep(code.getId(), null, "{\"finalText\":\"done\"}");
        store.startStep(run.getId(), StepType.REVIEW, 0, StepTrigger.NONE);
        // The in-memory DB is shared with other test classes; leave only this crash scene unfinished.
        store.findUnfinishedRuns().stream()
                .filter(r -> !r.getId().equals(run.getId()))
                .forEach(r -> store.finishRun(r.getId(), RunStatus.CANCELLED, "test isolation", null));

        new RunRecovery(store, executor).recover();

        await().atMost(Duration.ofSeconds(20))
                .until(() -> store.getRun(run.getId()).getStatus() == RunStatus.SUCCEEDED);
        assertThat(scriptedCoder.calls).isEmpty();
        assertThat(store.listSteps(run.getId()).stream().filter(s -> s.getType() == StepType.REVIEW).count()).isEqualTo(1);
        assertThat(scriptedReviewer.calls).singleElement().satisfies(r -> assertThat(r.prompt()).contains("main.js"));
        try (Git git = Git.open(dir.toFile())) {
            assertThat(StreamSupport.stream(git.log().call().spliterator(), false).count()).isEqualTo(1);
        }
    }
}
