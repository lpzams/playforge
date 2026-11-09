package dev.playforge.engine;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.Sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Test sandbox whose exec replays canned stdout lines and records what it was asked to run. */
public class FakeExecSandbox implements Sandbox {

    private final List<String> lines;
    private final int exitCode;
    private final String stderr;
    public final List<List<String>> cmds = new ArrayList<>();
    public final List<String> stdins = new ArrayList<>();

    public FakeExecSandbox(List<String> lines, int exitCode, String stderr) {
        this.lines = lines;
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public static FakeExecSandbox replaying(String fixture) {
        try {
            Path p = Path.of("src/test/resources/fixtures").resolve(fixture);
            return new FakeExecSandbox(Files.readAllLines(p, StandardCharsets.UTF_8), 0, "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path workdir() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void syncIn(Path hostDir) {
    }

    @Override
    public void syncOut(Path hostDir) {
    }

    @Override
    public ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel,
                           Consumer<String> stdoutLine) {
        cmds.add(cmd);
        stdins.add(stdin);
        lines.forEach(stdoutLine);
        return new ExecResult(exitCode, stderr);
    }

    @Override
    public void close() {
    }
}
