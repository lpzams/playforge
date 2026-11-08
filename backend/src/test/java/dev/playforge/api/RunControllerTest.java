package dev.playforge.api;

import dev.playforge.orchestration.OrchestrationTestConfig;
import dev.playforge.orchestration.OrchestrationTestConfig.ScriptedPlaytester;
import dev.playforge.orchestration.ScriptedEngine;
import dev.playforge.run.Project;
import dev.playforge.run.Run;
import dev.playforge.run.RunStatus;
import dev.playforge.run.RunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.playforge.orchestration.ScriptedEngine.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"playforge.engines.coder=scripted", "playforge.engines.reviewer=scripted"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrchestrationTestConfig.class)
class RunControllerTest {

    @Autowired MockMvc mvc;
    @Autowired RunStore store;
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
    void unknownRunIs404() throws Exception {
        mvc.perform(get("/api/runs/987654321")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/runs/987654321/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void cancelFinishedRunIs409() throws Exception {
        Run run = store.createRun(store.createProject("t", "p").getId(), "p");
        store.finishRun(run.getId(), RunStatus.SUCCEEDED, null, "abc");
        mvc.perform(post("/api/runs/" + run.getId() + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void secondRunWhileActiveIs409ThenCancel() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        scriptedCoder.script.add((r, s, k, c) -> {
            started.countDown();
            CountDownLatch cancelled = new CountDownLatch(1);
            c.onCancel(cancelled::countDown);
            cancelled.await(10, TimeUnit.SECONDS);
            c.throwIfCancelled();
            return text("unreachable");
        });
        String body = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"p\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String projectId = body.replaceAll(".*\"projectId\":\"([a-z0-9]+)\".*", "$1");
        long runId = Long.parseLong(body.replaceAll(".*\"runId\":(\\d+).*", "$1"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        mvc.perform(post("/api/projects/" + projectId + "/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"敌人再快一点\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/runs/" + runId + "/cancel")).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(10)).until(() -> store.getRun(runId).getStatus() == RunStatus.CANCELLED);
    }

    @Test
    void followUpRunAfterFinish() throws Exception {
        Project p = store.createProject("t", "p");
        Run first = store.createRun(p.getId(), "p");
        store.finishRun(first.getId(), RunStatus.SUCCEEDED, null, null);

        mvc.perform(post("/api/projects/" + p.getId() + "/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"敌人再快一点\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(p.getId()))
                .andExpect(jsonPath("$.runId").isNumber());
    }
}
