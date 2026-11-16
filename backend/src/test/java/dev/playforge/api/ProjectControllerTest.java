package dev.playforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.playforge.engine.demo.DemoCoderEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end through the API with the default demo engines. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoCoderEngine demoCoder;
    @Autowired dev.playforge.config.PlayforgeProperties props;

    JsonNode createProject(String prompt) throws Exception {
        String body = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("prompt", prompt))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void createProjectRunsToSuccess() throws Exception {
        JsonNode created = createProject("躲陨石的太空飞船游戏");
        long runId = created.get("runId").asLong();
        assertThat(created.get("projectId").asText()).matches("[a-z0-9]{12}");

        await().atMost(Duration.ofSeconds(20))
                .until(() -> getJson("/api/runs/" + runId).get("status").asText().equals("SUCCEEDED"));
        JsonNode run = getJson("/api/runs/" + runId);
        List<String> types = new java.util.ArrayList<>();
        run.get("steps").forEach(s -> types.add(s.get("type").asText()));
        assertThat(types).containsExactly("CODE", "REVIEW", "PLAYTEST", "COMMIT");
        assertThat(run.get("resultCommit").asText()).matches("[0-9a-f]{40}");
    }

    @Test
    void promptPreservedExactly() throws Exception {
        String prompt = "做一个\"躲陨石\"游戏\n第二行 C:\\path 'x'";
        JsonNode created = createProject(prompt);
        String projectId = created.get("projectId").asText();
        long runId = created.get("runId").asLong();

        JsonNode project = getJson("/api/projects/" + projectId);
        assertThat(project.get("prompt").asText()).isEqualTo(prompt);
        assertThat(project.get("title").asText()).isEqualTo("做一个\"躲陨石\"游戏");
        assertThat(project.get("runs")).hasSize(1);

        await().atMost(Duration.ofSeconds(20))
                .until(() -> getJson("/api/runs/" + runId).get("status").asText().equals("SUCCEEDED"));
        assertThat(demoCoder.lastRequest().prompt()).contains(prompt);
    }

    @Test
    void listProjectsIncludesNewProject() throws Exception {
        JsonNode created = createProject("list me");
        String projectId = created.get("projectId").asText();
        long runId = created.get("runId").asLong();
        // Let the run finish so it can't race with other tests that inspect the demo engine.
        await().atMost(Duration.ofSeconds(20))
                .until(() -> getJson("/api/runs/" + runId).get("status").asText().equals("SUCCEEDED"));
        JsonNode list = getJson("/api/projects");
        List<String> ids = new java.util.ArrayList<>();
        list.forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).contains(projectId);
    }

    @Test
    void projectViewHasPreviewFlagAndTimes() throws Exception {
        JsonNode created = createProject("预览标记");
        String projectId = created.get("projectId").asText();
        long runId = created.get("runId").asLong();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> getJson("/api/runs/" + runId).get("status").asText().equals("SUCCEEDED"));

        JsonNode project = getJson("/api/projects/" + projectId);
        assertThat(project.get("previewAvailable").asBoolean()).isFalse();
        assertThat(project.get("createdAt").asText()).isNotBlank();
        JsonNode run = project.get("runs").get(0);
        assertThat(run.get("createdAt").asText()).isNotBlank();
        assertThat(run.get("startedAt").asText()).isNotBlank();
        assertThat(run.get("finishedAt").asText()).isNotBlank();

        java.nio.file.Path dir = java.nio.file.Files.createDirectories(props.previewDir().resolve(projectId));
        java.nio.file.Files.writeString(dir.resolve("index.html"), "<!doctype html>");
        assertThat(getJson("/api/projects/" + projectId).get("previewAvailable").asBoolean()).isTrue();
    }

    @Test
    void blankPromptIs400() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    Map<String, Object> markup(String image, double w) {
        return Map.of("image", image, "canvas", Map.of("width", 480, "height", 320),
                "boxes", List.of(Map.of("x", 0.1, "y", 0.2, "w", w, "h", 0.3)));
    }

    long finishedProject(String prompt) throws Exception {
        JsonNode created = createProject(prompt);
        long runId = created.get("runId").asLong();
        await().atMost(Duration.ofSeconds(20)).until(() -> !List.of("QUEUED", "RUNNING")
                .contains(getJson("/api/runs/" + runId).get("status").asText()));
        return runId;
    }

    @Test
    void followUpWithMarkupStoresBoxesAndServesTheScreenshot() throws Exception {
        String projectId = getJson("/api/runs/" + finishedProject("接金币")).get("projectId").asText();
        String image = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(PNG);
        String body = mvc.perform(post("/api/projects/" + projectId + "/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("instruction", "框 1 里的篮子大一点", "markup", markup(image, 0.4)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long runId = json.readTree(body).get("runId").asLong();

        JsonNode run = getJson("/api/runs/" + runId);
        assertThat(run.at("/markup/canvas/width").asInt()).isEqualTo(480);
        assertThat(run.at("/markup/boxes/0/w").asDouble()).isEqualTo(0.4);
        mvc.perform(get("/api/runs/" + runId + "/markup.png"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(PNG));
        await().atMost(Duration.ofSeconds(20)).until(() -> getJson("/api/runs/" + runId).get("status").asText().equals("SUCCEEDED"));
    }

    @Test
    void invalidMarkupIs400AndCreatesNoRun() throws Exception {
        String projectId = getJson("/api/runs/" + finishedProject("躲陨石")).get("projectId").asText();
        String png = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(PNG);
        String svg = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString("<svg/>".getBytes());
        for (Map<String, Object> bad : List.of(markup(svg, 0.4), markup(png, 0.95))) {
            mvc.perform(post("/api/projects/" + projectId + "/runs").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("instruction", "改一下", "markup", bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("markup.")));
        }
        assertThat(getJson("/api/projects/" + projectId).get("runs")).hasSize(1);
    }

    @Test
    void plainRunHasNoMarkupScreenshot() throws Exception {
        long runId = finishedProject("打砖块");
        assertThat(getJson("/api/runs/" + runId).get("markup").isNull()).isTrue();
        mvc.perform(get("/api/runs/" + runId + "/markup.png")).andExpect(status().isNotFound());
    }

    @Test
    void unknownProjectIs404() throws Exception {
        mvc.perform(get("/api/projects/doesnotexist")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
