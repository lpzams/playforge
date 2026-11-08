package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentRole;
import dev.playforge.playtest.PlaytestReport;
import dev.playforge.playtest.Playtester;
import dev.playforge.sandbox.Sandbox;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Swaps in scripted engines (name "scripted") and a scripted playtester. */
@TestConfiguration
public class OrchestrationTestConfig {

    @Bean
    ScriptedEngine scriptedCoder() {
        return new ScriptedEngine("scripted", AgentRole.CODER);
    }

    @Bean
    ScriptedEngine scriptedReviewer() {
        return new ScriptedEngine("scripted", AgentRole.REVIEWER);
    }

    @Bean
    @Primary
    ScriptedPlaytester scriptedPlaytester() {
        return new ScriptedPlaytester();
    }

    public static class ScriptedPlaytester implements Playtester {
        public final Queue<PlaytestReport> script = new ConcurrentLinkedQueue<>();
        public final AtomicInteger calls = new AtomicInteger();

        public void reset() {
            script.clear();
            calls.set(0);
        }

        @Override
        public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
            calls.incrementAndGet();
            PlaytestReport r = script.poll();
            return r != null ? r : new PlaytestReport(true, List.of(), Map.of());
        }
    }
}
