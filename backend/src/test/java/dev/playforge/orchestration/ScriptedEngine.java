package dev.playforge.orchestration;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentEngine;
import dev.playforge.engine.AgentEventSink;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.AgentRole;
import dev.playforge.engine.EngineException;
import dev.playforge.event.RunEventType;
import dev.playforge.sandbox.Sandbox;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test engine: each call pops the next scripted behavior; when none is queued it falls back to a default
 * (coder writes a file, reviewer returns a clean review). Records every request it receives.
 */
public class ScriptedEngine implements AgentEngine {

    @FunctionalInterface
    public interface Behavior {
        AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws Exception;
    }

    private final String name;
    private final AgentRole role;
    public final Queue<Behavior> script = new ConcurrentLinkedQueue<>();
    public final List<AgentRequest> calls = new CopyOnWriteArrayList<>();

    public ScriptedEngine(String name, AgentRole role) {
        this.name = name;
        this.role = role;
    }

    public void reset() {
        script.clear();
        calls.clear();
    }

    public static final String DEFAULT_PLAY_SCRIPT = "export default async function play(t) { t.expect(true, 'ok'); }";
    public static final String DEFAULT_PLAY_REPLY = "```js\n" + DEFAULT_PLAY_SCRIPT + "\n```";
    public static final String DEFAULT_JUDGE_REPLY =
            "{\"scores\":{\"fit\":4,\"difficulty\":3,\"visuals\":3,\"feedback\":4},\"summary\":\"能玩\",\"suggestions\":[\"加音效\"]}";

    public static AgentResult text(String finalText) {
        return new AgentResult(finalText, 1, 1, Duration.ZERO);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws EngineException {
        calls.add(req);
        Behavior b = script.poll();
        try {
            if (b != null) return b.run(req, sandbox, sink, cancel);
            if (role == AgentRole.PLAYER) return text(DEFAULT_PLAY_REPLY);
            if (role == AgentRole.JUDGE) return text(DEFAULT_JUDGE_REPLY);
            if (role == AgentRole.CODER) {
                Files.writeString(sandbox.workdir().resolve("game-" + calls.size() + ".js"), "// " + calls.size());
                sink.emit(RunEventType.FILE_CHANGED, Map.of("path", "game-" + calls.size() + ".js"));
                return text("done");
            }
            return text("{\"issues\":[]}");
        } catch (EngineException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
