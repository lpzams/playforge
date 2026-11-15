package dev.playforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The backend serves the built web UI, with client-side routes falling back to index.html. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebUiConfigTest {

    static final Path WEB;

    static {
        try {
            WEB = Files.createTempDirectory("pf-web-test");
            Files.writeString(WEB.resolve("index.html"), "<!doctype html><title>PlayForge</title>");
            Files.createDirectories(WEB.resolve("assets"));
            Files.writeString(WEB.resolve("assets/app.js"), "console.log('app')");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void webRoot(DynamicPropertyRegistry r) {
        r.add("playforge.web-root", WEB::toString);
    }

    @Autowired
    MockMvc mvc;

    /** MockMvc records forwards without executing them; the real container serves index.html (checked in acceptance). */
    @Test
    void rootForwardsToIndex() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>PlayForge</title>")));
    }

    @Test
    void servesAsset() throws Exception {
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("console.log('app')")));
    }

    @Test
    void spaFallback() throws Exception {
        mvc.perform(get("/projects/abcdefghijkl")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>PlayForge</title>")));
    }

    @Test
    void apiNotShadowed() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}"));
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }
}
