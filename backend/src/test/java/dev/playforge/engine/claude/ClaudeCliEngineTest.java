package dev.playforge.engine.claude;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.AgentRole;
import dev.playforge.engine.EngineException;
import dev.playforge.engine.FakeExecSandbox;
import dev.playforge.event.RunEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCliEngineTest {

    final ClaudeCliEngine engine = new ClaudeCliEngine();
    final List<RunEventType> types = new ArrayList<>();
    final List<Map<String, Object>> payloads = new ArrayList<>();

    AgentResult run(FakeExecSandbox sandbox, String prompt) throws EngineException {
        return engine.run(new AgentRequest(AgentRole.CODER, prompt, Duration.ofMinutes(1)), sandbox,
                (t, p) -> {
                    types.add(t);
                    payloads.add(p);
                }, new CancellationToken());
    }

    @Test
    void identity() {
        assertThat(engine.name()).isEqualTo("claude");
        assertThat(engine.role()).isEqualTo(AgentRole.CODER);
    }

    @Test
    void replaysFixtureIntoEvents() throws Exception {
        AgentResult r = run(FakeExecSandbox.replaying("claude-stream.jsonl"), "make hello.js");

        assertThat(r.finalText()).isEqualTo("done");
        assertThat(r.inputTokens()).isPositive();
        assertThat(r.outputTokens()).isEqualTo(86);
        assertThat(types).contains(RunEventType.TOOL_CALL, RunEventType.TOOL_RESULT, RunEventType.FILE_CHANGED,
                RunEventType.AGENT_TEXT, RunEventType.USAGE);
        assertThat(payloads.get(types.indexOf(RunEventType.FILE_CHANGED))).containsEntry("path", "hello.js");
    }

    @Test
    void promptGoesToStdinNotArgs() throws Exception {
        FakeExecSandbox sandbox = FakeExecSandbox.replaying("claude-stream.jsonl");
        String prompt = "做一个\"游戏\"\n$HOME";
        run(sandbox, prompt);

        assertThat(sandbox.stdins).containsExactly(prompt);
        assertThat(sandbox.cmds.getFirst()).containsExactly("claude", "-p", "--output-format", "stream-json",
                "--verbose", "--dangerously-skip-permissions");
    }

    @Test
    void rateLimitIsRetryable() {
        FakeExecSandbox sandbox = new FakeExecSandbox(
                List.of("{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true,\"result\":\"Claude usage limit reached\"}"), 1, "");
        assertThatThrownBy(() -> run(sandbox, "p"))
                .isInstanceOfSatisfying(EngineException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void otherErrorResultIsNotRetryable() {
        FakeExecSandbox sandbox = new FakeExecSandbox(
                List.of("{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true,\"result\":\"Invalid API key\"}"), 1, "");
        assertThatThrownBy(() -> run(sandbox, "p"))
                .isInstanceOfSatisfying(EngineException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("Invalid API key");
                });
    }

    @Test
    void crashWithoutResultIsNotRetryable() {
        FakeExecSandbox sandbox = new FakeExecSandbox(List.of("not json"), 1, "segfault");
        assertThatThrownBy(() -> run(sandbox, "p"))
                .isInstanceOfSatisfying(EngineException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("segfault");
                });
    }
}
