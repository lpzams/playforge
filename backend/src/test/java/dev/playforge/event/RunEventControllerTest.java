package dev.playforge.event;

import dev.playforge.run.RunStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunEventControllerTest {

    @LocalServerPort
    int port;
    @Autowired
    RunEventPublisher publisher;
    @Autowired
    RunStore store;

    final HttpClient http = HttpClient.newHttpClient();

    long newRun() {
        return store.createRun(store.createProject("t", "p").getId(), "p").getId();
    }

    /** Reads the whole SSE stream until the server closes it, returning the event ids in order. */
    CompletableFuture<List<String>> readIds(long runId, String lastEventId) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runs/" + runId + "/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10));
        if (lastEventId != null) req.header("Last-Event-ID", lastEventId);
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofLines())
                .thenApply(HttpResponse::body)
                .thenApply((Stream<String> lines) -> lines
                        .filter(l -> l.startsWith("id:"))
                        .map(l -> l.substring(3).trim())
                        .toList());
    }

    @Test
    void replaysFromLastEventId() throws Exception {
        long run = newRun();
        for (int i = 0; i < 3; i++) publisher.publish(run, RunEventType.LOG, "system", Map.of());
        publisher.publish(run, RunEventType.RUN_FINISHED, "system", Map.of("status", "SUCCEEDED"));

        assertThat(readIds(run, "2").get(10, TimeUnit.SECONDS)).containsExactly("3", "4");
    }

    @Test
    void streamsLiveThenCompletes() throws Exception {
        long run = newRun();
        CompletableFuture<List<String>> ids = readIds(run, null);
        Thread.sleep(300);
        publisher.publish(run, RunEventType.LOG, "system", Map.of());
        publisher.publish(run, RunEventType.RUN_FINISHED, "system", Map.of("status", "SUCCEEDED"));

        assertThat(ids.get(10, TimeUnit.SECONDS)).containsExactly("1", "2");
    }
}
