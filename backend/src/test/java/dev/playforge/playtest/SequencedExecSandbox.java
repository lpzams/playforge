package dev.playforge.playtest;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.Sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Test sandbox: each exec call consumes the next scripted (stdout lines, exit code, stderr). */
class SequencedExecSandbox implements Sandbox {

    record Result(List<String> stdout, int exitCode, String stderr, RuntimeException toThrow) {
        Result(List<String> stdout, int exitCode, String stderr) {
            this(stdout, exitCode, stderr, null);
        }
    }

    SequencedExecSandbox thenThrow(RuntimeException e) {
        results.add(new Result(List.of(), 0, "", e));
        return this;
    }

    final Deque<Result> results = new ArrayDeque<>();
    final List<List<String>> cmds = new ArrayList<>();

    SequencedExecSandbox then(List<String> stdout, int exitCode, String stderr) {
        results.add(new Result(stdout, exitCode, stderr));
        return this;
    }

    @Override public Path workdir() { throw new UnsupportedOperationException(); }
    @Override public void syncIn(Path hostDir) { }
    @Override public void syncOut(Path hostDir) { }
    @Override public void exportDir(String relativePath, Path hostDir) { }
    @Override public void close() { }

    @Override
    public ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel, Consumer<String> out) {
        cmds.add(cmd);
        Result r = results.poll();
        if (r == null) throw new AssertionError("unexpected exec " + cmd);
        if (r.toThrow() != null) throw r.toThrow();
        r.stdout().forEach(out);
        return new ExecResult(r.exitCode(), r.stderr());
    }
}
