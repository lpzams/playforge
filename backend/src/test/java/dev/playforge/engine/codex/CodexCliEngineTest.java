package dev.playforge.engine.codex;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexCliEngineTest {

    final CodexCliEngine engine = new CodexCliEngine();
    final List<RunEventType> types = new ArrayList<>();

    AgentResult run(FakeExecSandbox sandbox, String prompt) throws EngineException {
        return engine.run(new AgentRequest(AgentRole.REVIEWER, prompt, Duration.ofMinutes(1)), sandbox,
                (t, p) -> types.add(t), new CancellationToken());
    }

    @Test
    void identity() {
        assertThat(engine.name()).isEqualTo("codex");
        assertThat(engine.role()).isEqualTo(AgentRole.REVIEWER);
    }

    @Test
    void returnsLastAgentMessage() throws Exception {
        AgentResult r = run(FakeExecSandbox.replaying("codex-exec.jsonl"), "review");
        assertThat(r.finalText()).isEqualTo("{\"issues\":[]}");
        assertThat(r.inputTokens()).isEqualTo(28449);
        assertThat(r.outputTokens()).isEqualTo(64);
        assertThat(types).contains(RunEventType.AGENT_TEXT, RunEventType.TOOL_CALL, RunEventType.TOOL_RESULT, RunEventType.USAGE);
    }

    @Test
    void promptGoesToStdinNotArgs() throws Exception {
        FakeExecSandbox sandbox = FakeExecSandbox.replaying("codex-exec.jsonl");
        String prompt = "审查\"游戏\"\n$HOME";
        run(sandbox, prompt);
        assertThat(sandbox.stdins).containsExactly(prompt);
        assertThat(sandbox.cmds.getFirst()).containsExactly("codex", "exec", "--json", "--sandbox", "danger-full-access",
                "--skip-git-repo-check", "-");
    }

    @Test
    void rateLimitIsRetryable() {
        FakeExecSandbox sandbox = new FakeExecSandbox(
                List.of("{\"type\":\"turn.failed\",\"error\":{\"message\":\"You've hit your usage limit\"}}"), 1, "");
        assertThatThrownBy(() -> run(sandbox, "p"))
                .isInstanceOfSatisfying(EngineException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void crashWithoutResultIsNotRetryable() {
        FakeExecSandbox sandbox = new FakeExecSandbox(List.of("{\"type\":\"thread.started\"}"), 2, "auth missing");
        assertThatThrownBy(() -> run(sandbox, "p"))
                .isInstanceOfSatisfying(EngineException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("auth missing");
                });
    }
}
