package dev.playforge.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviewControllerTest {

    static final Path BASE;
    static final Path PREVIEWS;

    static {
        try {
            BASE = Files.createTempDirectory("pf-preview-test");
            PREVIEWS = Files.createDirectories(BASE.resolve("previews"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void previewRoot(DynamicPropertyRegistry r) {
        r.add("playforge.preview-root", PREVIEWS::toString);
    }

    @BeforeAll
    static void files() throws IOException {
        Path game = Files.createDirectories(PREVIEWS.resolve("abcdefghijkl/assets"));
        Files.writeString(game.getParent().resolve("index.html"), "<!doctype html><title>game</title>");
        Files.writeString(game.resolve("main.js"), "console.log('game')");
        Path vite = Files.createDirectories(PREVIEWS.resolve("zyxwvutsrqpo/sub"));
        Files.writeString(vite.getParent().resolve("index.html"),
                "<!doctype html><html><head><title>vite</title>\n    <script type=\"module\" crossorigin src=\"./assets/index.js\"></script>\n</head><body><canvas></canvas></body></html>");
        Files.writeString(vite.resolve("index.html"), "<html><head><script type=\"module\" src=\"x.js\"></script></head></html>");
        Files.writeString(PREVIEWS.resolve("secret.txt"), "TOP-SECRET-1");
        Files.writeString(BASE.resolve("secret.txt"), "TOP-SECRET-2");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void servesIndexForTrailingSlash() throws Exception {
        MvcResult r = mvc.perform(get("/preview/abcdefghijkl/")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("text/html");
        assertThat(r.getResponse().getContentAsString()).contains("<title>game</title>");
    }

    /** The game's own index.html loads the hi-DPI shim before its module, so its canvas fills the frame sharply. */
    @Test
    void injectsTheHiDpiShimIntoTheGamePage() throws Exception {
        MvcResult r = mvc.perform(get("/preview/zyxwvutsrqpo/")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", PreviewController.PREVIEW_CSP))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String html = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getContentType()).startsWith("text/html");
        assertThat(html).contains("<script defer src=\"/hidpi.js\"></script>");
        assertThat(html.indexOf("/hidpi.js")).isLessThan(html.indexOf("type=\"module\""));
        assertThat(html).contains("<canvas></canvas>");
    }

    @Test
    void shimGoesBeforeHeadEndWithoutAModuleAndNeverIntoOtherFiles() throws Exception {
        assertThat(mvc.perform(get("/preview/abcdefghijkl/")).andReturn().getResponse().getContentAsString())
                .startsWith("<!doctype html><script defer src=\"/hidpi.js\"></script>").contains("<title>game</title>");
        assertThat(mvc.perform(get("/preview/zyxwvutsrqpo/sub/index.html")).andReturn().getResponse().getContentAsString())
                .doesNotContain("hidpi");
        assertThat(mvc.perform(get("/preview/abcdefghijkl/assets/main.js")).andReturn().getResponse().getContentAsString())
                .isEqualTo("console.log('game')");
    }

    /** Generated, untrusted JS must not run with the API's origin (it could call /api/** same-origin). */
    @Test
    void previewIsSandboxedAwayFromTheApiOrigin() throws Exception {
        for (String url : new String[]{"/preview/abcdefghijkl/", "/preview/abcdefghijkl/assets/main.js"}) {
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox allow-scripts")))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("allow-same-origin"))))
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }

    @Test
    void redirectsWithoutTrailingSlash() throws Exception {
        mvc.perform(get("/preview/abcdefghijkl")).andExpect(status().isFound())
                .andExpect(header().string("Location", "/preview/abcdefghijkl/"));
    }

    @Test
    void servesAssetWithContentType() throws Exception {
        MvcResult r = mvc.perform(get("/preview/abcdefghijkl/assets/main.js")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).contains("javascript");
        assertThat(r.getResponse().getContentAsString()).contains("console.log('game')");
    }

    @Test
    void missingFileIs404() throws Exception {
        mvc.perform(get("/preview/abcdefghijkl/nope.js")).andExpect(status().isNotFound());
        mvc.perform(get("/preview/zzzzzzzzzzzz/")).andExpect(status().isNotFound());
    }

    @Test
    void traversalIs404() throws Exception {
        for (String url : new String[]{
                "/preview/abcdefghijkl/../secret.txt",
                "/preview/abcdefghijkl/%2e%2e/secret.txt",
                "/preview/abcdefghijkl/..%5csecret.txt",
                "/preview/abcdefghijkl/assets/../../secret.txt",
                "/preview/abcdefghijkl/%2e%2e/%2e%2e/secret.txt"}) {
            MvcResult r = mvc.perform(get(URI.create(url))).andReturn();
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).as(url).doesNotContain("TOP-SECRET");
        }
    }

    @Test
    void badProjectIdIs404() throws Exception {
        for (String url : new String[]{"/preview/..%2f/secret.txt", "/preview/ABCDEFGHIJKL/", "/preview/abc/"}) {
            MvcResult r = mvc.perform(get(URI.create(url))).andReturn();
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).as(url).doesNotContain("TOP-SECRET");
        }
    }
}
