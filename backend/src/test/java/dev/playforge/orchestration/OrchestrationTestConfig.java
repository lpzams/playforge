package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentRole;
import dev.playforge.playtest.AiPlayResult;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
    ScriptedEngine scriptedPlayer() {
        return new ScriptedEngine("scripted", AgentRole.PLAYER);
    }

    @Bean
    ScriptedEngine scriptedJudge() {
        return new ScriptedEngine("scripted", AgentRole.JUDGE);
    }

    public static final String TEST_SECRET = "test-secret-token-0123456789";

    @Bean
    @Primary
    dev.playforge.engine.SecretRedactor testSecretRedactor() {
        return new dev.playforge.engine.SecretRedactor(List.of(TEST_SECRET));
    }

    @Bean
    @Primary
    ScriptedPlaytester scriptedPlaytester() {
        return new ScriptedPlaytester();
    }

    public static class ScriptedPlaytester implements Playtester {
        public final Queue<PlaytestReport> script = new ConcurrentLinkedQueue<>();
        public final AtomicInteger calls = new AtomicInteger();
        /** Optional hook run at the start of each playtest (e.g. to simulate a hang). */
        public volatile Runnable onRun;

        /** AI play: off unless a test turns it on; each play() pops the next result (default: one passing assertion). */
        public volatile boolean aiPlay;
        public final Queue<AiPlayResult> plays = new ConcurrentLinkedQueue<>();
        public final List<String> playedScripts = new CopyOnWriteArrayList<>();

        public void reset() {
            script.clear();
            calls.set(0);
            onRun = null;
            aiPlay = false;
            plays.clear();
            playedScripts.clear();
        }

        @Override
        public PlaytestReport run(Sandbox sandbox, CancellationToken cancel) {
            calls.incrementAndGet();
            Runnable hook = onRun;
            if (hook != null) hook.run();
            PlaytestReport r = script.poll();
            return r != null ? r : new PlaytestReport(true, List.of(), Map.of());
        }

        @Override
        public boolean supportsAiPlay() {
            return aiPlay;
        }

        @Override
        public AiPlayResult play(Sandbox sandbox, String playScript, CancellationToken cancel) {
            playedScripts.add(playScript);
            AiPlayResult r = plays.poll();
            return r != null ? r : passingPlay();
        }

        public static AiPlayResult passingPlay() {
            return new AiPlayResult(true, null, List.of(new AiPlayResult.Assertion("coins can be caught", true)),
                    List.of(new AiPlayResult.Shot("playtest/01-start.png", "start"), new AiPlayResult.Shot("playtest/02-end.png", "end")),
                    List.of("caught 2 coins"), 900);
        }

        public static AiPlayResult failingPlay(String message) {
            return new AiPlayResult(true, null, List.of(new AiPlayResult.Assertion(message, false)),
                    List.of(new AiPlayResult.Shot("playtest/01-start.png", "start")), List.of(), 900);
        }
    }
}
