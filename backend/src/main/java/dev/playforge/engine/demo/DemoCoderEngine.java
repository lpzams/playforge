package dev.playforge.engine.demo;

import dev.playforge.common.CancellationToken;
import dev.playforge.engine.AgentEngine;
import dev.playforge.engine.AgentEventSink;
import dev.playforge.engine.AgentRequest;
import dev.playforge.engine.AgentResult;
import dev.playforge.engine.AgentRole;
import dev.playforge.engine.EngineException;
import dev.playforge.event.RunEventType;
import dev.playforge.sandbox.Sandbox;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Stand-in coder for P1: writes a placeholder index.html. Replaced by the Claude CLI engine in P2. */
@Component
public class DemoCoderEngine implements AgentEngine {

    private volatile AgentRequest lastRequest;

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public AgentRole role() {
        return AgentRole.CODER;
    }

    /** Last request received; exposed so tests can check prompts arrive unmodified. */
    public AgentRequest lastRequest() {
        return lastRequest;
    }

    @Override
    public AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws EngineException {
        lastRequest = req;
        cancel.throwIfCancelled();
        String firstLine = req.prompt().lines().findFirst().orElse("");
        Path file = sandbox.workdir().resolve("index.html");
        try {
            Files.createDirectories(sandbox.workdir());
            Files.writeString(file, "<!doctype html><title>demo</title><p>" + HtmlUtils.htmlEscape(firstLine) + "</p>\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EngineException("demo coder could not write: " + e.getMessage(), false, null);
        }
        sink.emit(RunEventType.FILE_CHANGED, Map.of("path", "index.html"));
        return new AgentResult("wrote index.html", 0, 0, Duration.ZERO);
    }
}
