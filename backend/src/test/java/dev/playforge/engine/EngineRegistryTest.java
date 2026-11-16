package dev.playforge.engine;

import dev.playforge.common.CancellationToken;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.sandbox.Sandbox;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineRegistryTest {

    record Fake(String name, AgentRole role, Set<AgentRole> extra) implements AgentEngine {
        @Override
        public boolean supports(AgentRole r) {
            return r == role || extra.contains(r);
        }

        @Override
        public AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) {
            return new AgentResult("", 0, 0, Duration.ZERO);
        }
    }

    static PlayforgeProperties props(String coder, String reviewer, String player, String judge) {
        return new PlayforgeProperties("w", null, new PlayforgeProperties.Engines(coder, reviewer, player, judge),
                null, "p", "web", null);
    }

    final Fake claude = new Fake("claude", AgentRole.CODER, Set.of(AgentRole.PLAYER, AgentRole.JUDGE));
    final Fake codex = new Fake("codex", AgentRole.REVIEWER, Set.of());

    @Test
    void playerAndJudgeDefaultToTheCoderEngine() {
        EngineRegistry reg = new EngineRegistry(List.of(claude, codex), props("claude", "codex", null, null));
        assertThat(reg.player()).isSameAs(claude);
        assertThat(reg.judge()).isSameAs(claude);
        assertThat(reg.reviewer()).isSameAs(codex);
    }

    @Test
    void anEngineMustSupportTheRoleItIsConfiguredFor() {
        EngineRegistry reg = new EngineRegistry(List.of(claude, codex), props("claude", "codex", "codex", "claude"));
        assertThatThrownBy(reg::player).hasMessageContaining("no PLAYER engine named codex");
    }

    @Test
    void defaultSupportIsTheEnginesOwnRole() {
        AgentEngine plain = new AgentEngine() {
            public String name() { return "x"; }
            public AgentRole role() { return AgentRole.REVIEWER; }
            public AgentResult run(AgentRequest r, Sandbox s, AgentEventSink k, CancellationToken c) { return null; }
        };
        assertThat(plain.supports(AgentRole.REVIEWER)).isTrue();
        assertThat(plain.supports(AgentRole.JUDGE)).isFalse();
    }
}
