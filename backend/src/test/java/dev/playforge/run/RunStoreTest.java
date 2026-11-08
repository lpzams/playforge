package dev.playforge.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunStoreTest {

    @Autowired
    RunStore store;

    @Test
    void createRunIsQueued() {
        Project p = store.createProject("t", "躲陨石");
        assertThat(p.getId()).matches("[a-z0-9]{12}");
        Run run = store.createRun(p.getId(), "躲陨石");
        assertThat(store.getRun(run.getId()).getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void stepsListedInOrder() {
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        Step code = store.startStep(run.getId(), StepType.CODE, 0, StepTrigger.NONE);
        store.completeStep(code.getId(), null, "{}");
        store.startStep(run.getId(), StepType.REVIEW, 0, StepTrigger.NONE);

        List<Step> steps = store.listSteps(run.getId());
        assertThat(steps).extracting(Step::getType).containsExactly(StepType.CODE, StepType.REVIEW);
        assertThat(steps).extracting(Step::getStatus).containsExactly(StepStatus.DONE, StepStatus.RUNNING);
    }

    @Test
    void unfinishedRunsExcludeTerminal() {
        String pid = store.createProject("t", "p").getId();
        Run running = store.createRun(pid, "a");
        store.markRunStarted(running.getId(), null);
        Run done = store.createRun(pid, "b");
        store.markRunStarted(done.getId(), null);
        store.finishRun(done.getId(), RunStatus.SUCCEEDED, null, "abc");
        Run queued = store.createRun(pid, "c");

        List<Long> ids = store.findUnfinishedRuns().stream().map(Run::getId).toList();
        assertThat(ids).contains(running.getId(), queued.getId()).doesNotContain(done.getId());
        assertThat(ids.indexOf(running.getId())).isLessThan(ids.indexOf(queued.getId()));
    }
}
