package dev.playforge.engine.claude;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentEngine;
import dev.playforge.engine.AgentEventSink;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.AgentRole;
import dev.playforge.engine.EngineErrors;
import dev.playforge.engine.EngineException;
import dev.playforge.engine.StreamEvent;
import dev.playforge.event.RunEventType;
import dev.playforge.process.ExecResult;
import dev.playforge.process.ProcessTimeoutException;
import dev.playforge.sandbox.Sandbox;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Coder engine: Claude Code CLI in headless mode inside the sandbox. Prompt goes through stdin. */
@Component
public class ClaudeCliEngine implements AgentEngine {

    static final List<String> COMMAND = List.of("claude", "-p", "--output-format", "stream-json", "--verbose",
            "--dangerously-skip-permissions");
    private static final String WORKSPACE_PREFIX = "/workspace/";

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public AgentRole role() {
        return AgentRole.CODER;
    }

    /** Claude also plays the game (writes the playthrough) and judges screenshots (it can read images). */
    @Override
    public boolean supports(AgentRole role) {
        return role == AgentRole.CODER || role == AgentRole.PLAYER || role == AgentRole.JUDGE;
    }

    @Override
    public AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel)
            throws EngineException {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        StreamEvent.Final[] fin = new StreamEvent.Final[1];
        StreamEvent.Usage[] usage = new StreamEvent.Usage[1];
        long t0 = System.nanoTime();
        ExecResult exec;
        try {
            exec = sandbox.exec(COMMAND, req.prompt(), req.timeout(), cancel, line -> {
                for (StreamEvent e : parser.parse(line)) {
                    switch (e) {
                        case StreamEvent.Text t -> sink.emit(RunEventType.AGENT_TEXT, Map.of("text", t.text()));
                        case StreamEvent.ToolCall c -> {
                            sink.emit(RunEventType.TOOL_CALL, Map.of("name", c.name(), "input", c.inputSummary()));
                            if (c.filePath() != null) {
                                sink.emit(RunEventType.FILE_CHANGED, Map.of("path", relative(c.filePath())));
                            }
                        }
                        case StreamEvent.ToolResult r -> sink.emit(RunEventType.TOOL_RESULT,
                                Map.of("summary", r.summary(), "isError", r.isError()));
                        case StreamEvent.Usage u -> {
                            usage[0] = u;
                            sink.emit(RunEventType.USAGE, Map.of("inputTokens", u.inputTokens(), "outputTokens", u.outputTokens()));
                        }
                        case StreamEvent.Final f -> fin[0] = f;
                    }
                }
            });
        } catch (ProcessTimeoutException e) {
            throw new EngineException("claude timed out after " + req.timeout().toMillis() + " ms", false, null);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);

        if (fin[0] == null) {
            throw new EngineException("claude exited with code " + exec.exitCode() + " without a result: "
                    + exec.stderrTail().strip(), false, null);
        }
        if (fin[0].isError()) throw EngineErrors.fromErrorText("claude", fin[0].text());
        long in = usage[0] == null ? 0 : usage[0].inputTokens();
        long out = usage[0] == null ? 0 : usage[0].outputTokens();
        return new AgentResult(fin[0].text(), in, out, elapsed);
    }

    private static String relative(String path) {
        return path.startsWith(WORKSPACE_PREFIX) ? path.substring(WORKSPACE_PREFIX.length()) : path;
    }
}
