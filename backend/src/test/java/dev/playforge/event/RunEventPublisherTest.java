package dev.playforge.event;

import dev.playforge.run.RunStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RunEventPublisherTest {

    @Autowired
    RunEventPublisher publisher;
    @Autowired
    RunStore store;

    long newRun() {
        return store.createRun(store.createProject("t", "p").getId(), "p").getId();
    }

    @Test
    void seqIsContiguousPerRun() {
        long a = newRun(), b = newRun();
        for (int i = 0; i < 3; i++) publisher.publish(a, RunEventType.LOG, "system", Map.of("i", i));
        for (int i = 0; i < 2; i++) publisher.publish(b, RunEventType.LOG, "system", Map.of("i", i));

        assertThat(publisher.history(a, 0)).extracting(RunEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(publisher.history(b, 0)).extracting(RunEvent::seq).containsExactly(1L, 2L);
    }

    @Test
    void concurrentPublishNoGaps() throws Exception {
        long run = newRun();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < 10; t++) {
                fs.add(ex.submit(() -> {
                    for (int i = 0; i < 50; i++) publisher.publish(run, RunEventType.LOG, "system", Map.of());
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        assertThat(publisher.history(run, 0)).extracting(RunEvent::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 500).boxed().toList());
    }

    @Test
    void historyAfterSeq() {
        long run = newRun();
        for (int i = 0; i < 5; i++) publisher.publish(run, RunEventType.LOG, "system", Map.of());
        assertThat(publisher.history(run, 3)).extracting(RunEvent::seq).containsExactly(4L, 5L);
    }

    @Test
    void payloadRoundTrips() {
        long run = newRun();
        publisher.publish(run, RunEventType.AGENT_TEXT, "claude", Map.of("text", "做一个\"游戏\"\n第二行"));
        RunEvent e = publisher.history(run, 0).getFirst();
        assertThat(e.type()).isEqualTo(RunEventType.AGENT_TEXT);
        assertThat(e.source()).isEqualTo("claude");
        assertThat(e.payload()).containsEntry("text", "做一个\"游戏\"\n第二行");
    }

    @Test
    void subscriberReceivesLive() throws Exception {
        long run = newRun();
        List<RunEvent> got = new CopyOnWriteArrayList<>();
        AutoCloseable sub = publisher.subscribe(run, got::add);
        publisher.publish(run, RunEventType.LOG, "system", Map.of());
        publisher.publish(run, RunEventType.LOG, "system", Map.of());
        sub.close();
        publisher.publish(run, RunEventType.LOG, "system", Map.of());
        assertThat(got).extracting(RunEvent::seq).containsExactly(1L, 2L);
    }
}
